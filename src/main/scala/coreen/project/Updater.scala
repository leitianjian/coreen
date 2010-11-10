//
// $Id$

package coreen.project

import java.io.{File, StringReader}
import java.sql.BatchUpdateException
import java.net.URI
import java.util.concurrent.Callable

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.{Map => MMap, Set => MSet}
import scala.io.Source
import scala.xml.{XML, Elem}

import org.squeryl.PrimitiveTypeMode._

import coreen.model.SourceModel._
import coreen.model.{Convert, DefInfo, Kind, SourceModel, SigDef, Use => JUse}
import coreen.persist.{CompUnit, DB, Decode, Def, DefMap, Doc, Project, Sig, Super, Use}
import coreen.server.{Log, Exec, Dirs, Console}

/** Provides project updating services. */
trait Updater {
  this :Log with Exec with DB with Dirs with Console =>

  /** Handles updating projects. */
  object _updater {
    /**
     * (Re)imports the contents of the specified project. This includes:
     * <ul>
     *  <li>scanning the root path of the supplied project for compilation units</li>
     *  <li>grouping them by language</li>
     *  <li>running the appropriate readers to convert them to name-resolved form</li>
     *  <li>clearing the current project contents from the database</li>
     *  <li>loading the name-resolved metadata into the database</li>
     * </ul>
     * This is very disk and compute intensive and should be done on a background thread.
     */
    def update (p :Project) {
      val ulog = _console.start("project:" + p.id)
      ulog.append("Finding compilation units...")

      try {
        // first figure out what sort of source files we see in the project
        val types = collectFileTypes(new File(p.rootPath))

        // fire up readers to handle all types of files we find in the project
        val readers = Map() ++ (types flatMap(t => readerForType(t) map(r => (t -> r))))
        ulog.append("Processing compilation units of type " + readers.keySet.mkString(", ") + "...")
        readers.values map(_.invoke(p, ulog))
      } catch {
        case t => ulog.append("Update failed.", t)
      }

      ulog.close
    }

    abstract class Reader {
      def invoke (p :Project, ulog :Writer) {
        val extraOpts = p.readerOpts.map(_.split(" ").toList).getOrElse(List())
        val dirList = p.srcDirs.map(_.split(" ").toList).getOrElse(List())
        val argList = args(p.rootPath, extraOpts, dirList)
        ulog.append("Invoking reader: " + argList.mkString(" "))
        val proc = Runtime.getRuntime.exec(argList.toArray)

        // read stderr on a separate thread so that we can ensure that stdout and stderr are both
        // actively drained, preventing the process from blocking
        val errLines = _exec.submit(new Callable[Array[String]] {
          def call = Source.fromInputStream(proc.getErrorStream).getLines.toArray
        })

        // consume stdout from the reader, accumulating <compunit ...>...</compunit> into a buffer
        // and processing each complete unit that we receive; anything in between compunit elements
        // is reported verbatim to the status log
        val cus = time("parseCompUnits") {
          parseCompUnits(p, ulog, Source.fromInputStream(proc.getInputStream).getLines)
        }
        ulog.append("Parsed " + cus.size + " compunits.")

        // now that we've totally drained stdout, we can wait for stderr output and log it
        val errs = errLines.get

        // report any error status code (TODO: we probably don't really need to do this)
        val ecode = proc.waitFor
        if (ecode != 0) {
          ulog.append("Reader exited with status: " + ecode)
          ulog.append(errs)
          return // leave the project as is; TODO: maybe not if this is the first import...
        }

        // determine which CUs we knew about before
        val oldCUs = time("loadOldUnits") {
          transaction { _db.compunits where(cu => cu.projectId === p.id) toList }
        }

        // update compunit data, and construct a mapping from compunit path to id
        val newPaths = Set("") ++ (cus map(_.src))
        val toDelete = oldCUs filterNot(cu => newPaths(cu.path)) map(_.id) toSet
        val toAdd = newPaths -- (oldCUs map(_.path))
        val toUpdate = oldCUs filterNot(cu => toDelete(cu.id)) map(_.id) toSet
        val cuIds = MMap[String,Long]()
        val now = System.currentTimeMillis
        transaction {
          if (!toDelete.isEmpty) {
            _db.compunits.deleteWhere(cu => cu.id in toDelete)
            ulog.append("Removed " + toDelete.size + " obsolete compunits.")
          }
          if (!toAdd.isEmpty) {
            for (cu <- toAdd.map(CompUnit(p.id, _, now))) {
              _db.compunits.insert(cu)
              // add the id of the newly inserted unit to our (path -> id) mapping
              cuIds += (cu.path -> cu.id)
            }
            ulog.append("Added " + toAdd.size + " new compunits.")
          }
          if (!toUpdate.isEmpty) {
            _db.compunits.update(cu => where(cu.id in toUpdate) set(cu.lastUpdated := now))
            // add the ids of the updated units to our (path -> id) mapping
            oldCUs filter(cu => toUpdate(cu.id)) foreach { cu => cuIds += (cu.path -> cu.id) }
            ulog.append("Updated " + toUpdate.size + " compunits.")
          }
        }

        // (if necessary) create a fake comp unit which will act as a container for module
        // declarations (TODO: support package-info.java files, Scala package objects, and
        // languages that have a compunit which can be reasonably associated with a module
        // definition)
        val cuDef = transaction {
          _db.compunits.where(cu => cu.projectId === p.id and cu.path === "").headOption.
            getOrElse(_db.compunits.insert(CompUnit(p.id, "", now)))
        }

        val defMap = _db.newDefMap
        // this map will contain a mapping from fqName to defId for all referable defs (i.e. those
        // that are not internal to a function or initialization expression)
        // val defMap = MMap[String,Long]()
        val newDefs = MSet[String]()
        val dupDefs = MSet[String]()

        // load up a (defId to unitId) mapping for all defs in this project
        val defToUnit = MMap[Long, Long]()
        time("loadDefToUnit") {
          transaction {
            for ((defId, unitId) <- from(_db.defs, _db.compunits)(
              (d, cu) => where(cu.projectId === p.id and d.unitId === cu.id)
                         select(d.id, d.unitId))) {
              defToUnit.update(defId, unitId)
            }
          }
        }

        // We extract all of the module definitions, map them by id, and then select (arbitrarily)
        // the first occurance of a definition for the specified module id to represent that
        // module; we then strip those defs of their subdefs (which will be processed later) and
        // then process the whole list as if they were all part of one "declare all the modules in
        // this project" compilation unit
        val byId = cus.flatMap(cu => allDefs(cu.defs)) filter(_.kind == Kind.MODULE) groupBy(_.id)
        processDefs(cuDef.id, defMap, newDefs, dupDefs, defToUnit,
                    byId.values map(_.head) map(_.copy(defs = Nil)) toSeq)

        // first process all of the definitions in all of the compunits...
        for (cu <- cus) {
          ulog.append("Processing defs in " + cu.src + "...")
          // we want to filter out any defs for which we have no module id mapping; this filters
          // out code from modules that have already been claimed by some other project
          processDefs(cuIds(cu.src), defMap, newDefs, dupDefs, defToUnit,
                      cu.defs filter(d => defMap.contains(d.id)))
        }

        // then process all of the uses (which may reference the newly added defs)...
        for (cu <- cus) {
          ulog.append("Processing uses in " + cu.src + "...")
          processUses(cuIds(cu.src), defMap, newDefs, dupDefs, cu.defs)
        }

        // finally record supertype relationships (which may also reference newly added defs)...
        for (cu <- cus) {
          ulog.append("Processing supers in " + cu.src + "...")
          processSupers(ulog, cuIds(cu.src), defMap, cu.defs)
        }

        ulog.append("Processing complete!")
        _timings.toList sortBy(_._2) foreach(t => println(t._1 + " " + t._2))
        println("Total " + _timings.map(_._2).sum)
      }

      def parseCompUnits (p :Project, ulog :Writer, lines :Iterator[String]) = {
        // obtain a sane prefix we can use to relativize the comp unit source URIs
        val uriRoot = new File(p.rootPath).getCanonicalFile.toURI.getPath
        assert(uriRoot.endsWith("/"))

        var accmode = false
        var accum = new StringBuilder
        val cubuf = ArrayBuffer[CompUnitElem]()
        for (line <- lines) {
          accmode = accmode || line.trim.startsWith("<compunit")
          if (!accmode) ulog.append(line)
          else {
            accum.append(line).append("\n") // TODO: need line.separator?
            accmode = !line.trim.startsWith("</compunit>")
            if (!accmode) {
              try {
                val cu = SourceModel.parse(XML.load(new StringReader(accum.toString)))
                val curi = new URI(cu.src)
                if (curi.getPath.startsWith(uriRoot))
                  cu.src = curi.getPath.substring(uriRoot.length)
                cubuf += cu
              } catch {
                case e => ulog.append(
                  "Error parsing reader output: " + truncate(accum.toString, 100), e)
              }
              accum.setLength(0)
            }
          }
        }
        cubuf.toList
      }

      def args (rootPath :String, extraOpts :List[String], srcDirs :List[String]) :List[String]
    }

    def processDefs (unitId :Long, defMap :DefMap, addedDefs :MSet[String], dupDefs :MSet[String],
                     defToUnit :MMap[Long, Long], defs :Seq[DefElem]) {
      transaction {
        // resolve (fqName -> id) for all defs in this unit (and all their children)
        time("loadDefIds") { defMap.resolveIds(defs map(_.id), true) }

        // determine which of said defs are currently defined by this compunit, which are currently
        // defined by some other compunit and which are currently undefined
        def allIds (ids :Set[String], defs :Seq[DefElem]) :Set[String] =
          (ids /: defs)((s, d) => allIds(s + d.id, d.defs))
        val unitIds = allIds(Set(), defs)
        val oldDefs = MSet[String]()
        val unknownDefs = MMap[Long, String]()
        for (fqName <- unitIds) {
          val ido = defMap.get(fqName)
          if (ido.isDefined) {
            defToUnit.get(ido.get) match {
              case Some(defUnitId) => if (unitId == defUnitId) oldDefs += fqName
                                      else dupDefs += fqName
              case None => unknownDefs.update(ido.get, fqName)
            }
          }
        }
        if (unknownDefs.size > 0) {
          println("Looking for " + unknownDefs.size + " unknown defs")
          time("identifyDefs") {
            for ((defId, dUnitId) <- from(_db.defs)(
              d => where(d.id in unknownDefs.keySet) select(d.id, d.unitId))) {
                println("Found def " + defId + " " + dUnitId)
                if (dUnitId == unitId) {
                  oldDefs += unknownDefs(defId)
                } else {
                  dupDefs += unknownDefs(defId)
                }
              }
          }
        }

        // figure out which defs to add, which to update, and which to delete
        val toDelete = oldDefs -- unitIds
        val (toAdd, toUpdate) = (unitIds -- oldDefs -- dupDefs, oldDefs -- toDelete)

        // add the new defs to the defname map to assign them ids
        time("insertNewNames") {
          defMap.assignIds(toAdd)
        }

        // now convert the defelems into defs using the fqName to id map
        def makeDefs (outerId :Long)(
          out :Map[String,Def], df :DefElem) :Map[String,Def] = defMap.get(df.id) match {
          case Some(defId) => {
            val ndef = Def(defId, outerId, 0L, unitId, df.name, Decode.kindToCode(df.kind),
                           Decode.flavorToCode(df.flavor), df.flags,
                           df.start, df.start+df.name.length, df.bodyStart, df.bodyEnd)
            ((out + (df.id -> ndef)) /: df.defs)(makeDefs(ndef.id))
          }
          case None => println("*** No mapping for " + df.id); out // TODO: still?
        }
        val ndefs = (Map[String,Def]() /: defs)(makeDefs(0L))

        // insert, update, and delete
        if (!toAdd.isEmpty) {
          addedDefs ++= toAdd
          val added = toAdd map(ndefs)
          time("addNewDefs") { _db.defs.insert(added) }
          added foreach { d => defToUnit.update(d.id, d.unitId) }
          // println("Inserted " + toAdd.size + " new defs")
        }
        if (!toUpdate.isEmpty) {
          val updated = toUpdate map(ndefs)
          _db.defs.update(updated)
          updated foreach { d => defToUnit.update(d.id, d.unitId) }
          // println("Updated " + toUpdate.size + " defs")
        }
        if (!toDelete.isEmpty) {
          val toDelIds = toDelete map(defMap)
          time("deleteOldDefs") { _db.defs.deleteWhere(d => d.id in toDelIds) }
          toDelIds foreach { id => defToUnit.remove(id) }
          // println("Deleted " + toDelete.size + " defs")
        }
      }
    }

    def processUses (unitId :Long, defMap :DefMap, addedDefs :MSet[String],
                     dupDefs :MSet[String], defs :Seq[DefElem]) {
      transaction {
        // delete the old uses recorded for this compunit
        time("deleteOldUses") { _db.uses.deleteWhere(u => u.unitId === unitId) }

        // convert the useelems into (use, referentFqName) pairs
        def processUses (out :Vector[(Use,String)], df :DefElem) :Vector[(Use,String)] =
          defMap.get(df.id) match {
            case Some(defId) => {
              val nuses = df.uses.map(
                u => (Use(unitId, defId, -1, Decode.kindToCode(u.kind),
                          u.start, u.start + u.name.length), u.target))
              ((out ++ nuses) /: df.defs)(processUses)
            }
            case None => out
          }
        val nuses = (Vector[(Use,String)]() /: defs)(processUses)

        // look up the ids of referents that we don't already know about
        val refFqNames = Set() ++ (nuses map(_._2) filter(!defMap.contains(_)))
        if (!refFqNames.isEmpty) {
          time("loadRefIds") { defMap.resolveIds(refFqNames, false) }
          // TODO: generate placeholder defs for unknown referents
        }

        val (bound, unbound) = ((List[Use](),List[(Use,String)]()) /: nuses)((
          acc, up) => defMap.get(up._2) match {
          case Some(id) => ((up._1 copy (referentId = id)) :: acc._1, acc._2)
            case None => (acc._1, up :: acc._2)
        })
        time("insertUses") { _db.uses.insert(bound) }

        // now that all of our referents are loaded, process the signatures and docs
        def parseSigDefs (defs :Seq[SigDefElem]) =
          defs map(d => new SigDef(0, d.kind, d.start, d.name.length))
        def parseUses (uses :Seq[UseElem]) = uses flatMap(u => try {
          defMap.get(u.target) map(refId => new JUse(refId, u.kind, u.start, u.name.length))
        } catch {
          case e => println("Bad use! " + u + ": " + e); None
        })
        def parseSig (df :DefElem) = df.sig flatMap(se => defMap.get(df.id) match {
          case Some(defId) => Some(Sig(defId, se.text,
                                       Convert.encodeSigDefs(parseSigDefs(se.defs)),
                                       Convert.encodeUses(parseUses(se.uses))))
          case None => println("*** No def for sig? " + df.id); None
        })
        def parseDoc (df :DefElem) = df.doc flatMap(de => defMap.get(df.id) map(
          defId => Doc(defId, truncate(de.text, DefInfo.MAX_DOC_LENGTH-3),
                       Convert.encodeUses(parseUses(de.uses)))))
        val (ndefs, udefs) = allDefs(defs) partition(df => addedDefs(df.id))
        val (vndefs, vudefs) = (ndefs filter(d => !dupDefs(d.id)),
                                udefs filter(d => !dupDefs(d.id)))
        val (nsigs, usigs) = (vndefs.flatMap(parseSig), vudefs.flatMap(parseSig))
        val (ndocs, udocs) = (vndefs.flatMap(parseDoc), vudefs.flatMap(parseDoc))
        time("insertSigs") {
          _db.sigs.insert(nsigs)
        }
        time("updateSigs") {
          for (us <- usigs) {
            if (_db.sigs.update(s => where(s.defId === us.defId) set(
              s.text := us.text, s.defs := us.defs, s.uses := us.uses)) == 0) {
              _db.sigs.insert(us) // TEMP: while we migrate from the old world
            }
          }
        }
        time("insertDocs") { _db.docs.insert(ndocs) }
        time("updateDocs") {
          for (ud <- udocs) {
            if (_db.docs.update(d => where(d.defId === ud.defId) set(
              d.text := ud.text, d.uses := ud.uses)) == 0) {
              _db.docs.insert(ud) // TEMP: while we migrate from the old world
            }
          }
        }
      }
    }

    def processSupers (ulog :Writer, unitId :Long, defMap :DefMap, defs :Seq[DefElem]) {
      val toAdd = transaction {
        // load up all existing super relationships for all defs in this compunit
        val oldSups = from(_db.supers, _db.defs)((s, d) =>
          where(d.unitId === unitId and s.defId === d.id) select(s))
        val superMap = oldSups groupBy(_.defId) mapValues(_ map(_.superId) toSet)

        // collect the fqNames of all super types so we can resolve their ids
        def getSuperNames (names :Set[String], d :DefElem) :Set[String] =
          ((names ++ d.supers) /: d.defs)(getSuperNames)
        val refFqNames = (Set[String]() /: defs)(getSuperNames) filter(!defMap.contains(_))
        if (!refFqNames.isEmpty) {
          time("loadSuperIds") { defMap.resolveIds(refFqNames, false) }
          // TODO: generate placeholder defs for unknown referents
        }

        // now we'll accumulate the supers that need adding and deleting
        val (toAdd, toDel) = (ArrayBuffer[Super](), ArrayBuffer[Super]())
        val toUpdate = MMap[Long,Long]()

        // note all of the super additions, deletions and updates that are needed
        def processDef (d :DefElem) :Unit = for (defId <- defMap.get(d.id)) {
          val osups = superMap getOrElse(defId, Set())
          val nsups = d.supers flatMap(s => defMap.get(s)) toSet; // grr!
          // note the deletions and additions to be made
          (osups -- nsups) foreach { id => toDel += Super(defId, id) }
          (nsups -- osups) foreach { id => toAdd += Super(defId, id) }
          // note the primary supertype
          if (!d.supers.isEmpty) {
            defMap.get(d.supers.head) match {
              case Some(superId) => toUpdate += (defId -> superId)
              case None =>
            }
          }
          // finally process this def's children
          d.defs foreach(processDef)
        }
        defs foreach(processDef)

        // do the adding, deleting and updating
        toDel foreach { s => _db.supers.delete(s.id) }
        for ((defId, superId) <- toUpdate) {
          _db.defs update(d => where(d.id === defId) set(d.superId := superId))
        }
        toAdd // returned to outer scope for processing
      }

      // try inserting our supers in a single batch, but if we run into problems, fall back to
      // inserting them individually
      if (!toAdd.isEmpty) try {
        transaction { _db.supers.insert(toAdd) }
      } catch {
        case e :BatchUpdateException => transaction {
          for (s <- toAdd) try {
            _db.supers.insert(s)
          } catch {
            case e => ulog.append("Failed to insert " + s + ": " + e)
          }
        }
      }
    }

    class JavaReader (
      classname :String, classpath :List[File], javaArgs :List[String]
    ) extends Reader {
      val javabin = mkFile(new File(System.getProperty("java.home")), "bin", "java")
      def args (rootPath :String, extraOpts :List[String], srcDirs :List[String]) = {
        val jvm :List[String] = javabin.getCanonicalPath :: "-classpath" ::
          classpath.map(_.getAbsolutePath).mkString(File.pathSeparator) :: extraOpts
        jvm ++ (classname :: javaArgs) ++ (rootPath :: srcDirs)
      }
    }

    // TEMP: profiling helper
    def time[T] (id :String)(action : => T) = {
      val start = System.nanoTime
      val r = action
      val elapsed = System.nanoTime - start
      _timings(id) = elapsed + _timings.getOrElse(id, 0L)
      r
    }
    val _timings = MMap[String,Long]()
    // END TEMP

    def stropt (text :String) = text match {
      case null | "" => None
      case str => Some(str)
    }

    def mkFile (root :File, path :String*) = (root /: path)(new File(_, _))

    def getToolsJar = {
      val jhome = new File(System.getProperty("java.home"))
      val tools = mkFile(jhome.getParentFile, "lib", "tools.jar")
      val classes = mkFile(jhome.getParentFile, "Classes", "classes.jar")
      if (tools.exists) tools
      else if (classes.exists) classes
      else error("Can't find tools.jar or classes.jar")
    }

    def createJavaJavaReader = _appdir match {
      case Some(appdir) => new JavaReader(
        "coreen.java.Main",
        List(getToolsJar, mkFile(appdir, "coreen-java-reader.jar")),
        List())
      case None => new JavaReader(
        "coreen.java.Main",
        List(getToolsJar,
             mkFile(new File("java-reader"), "target", "scala_2.8.0",
                    "coreen-java-reader_2.8.0-0.1.min.jar")),
        List())
    }

    def readerForType (typ :String) :Option[Reader] = typ match {
      case "java" => Some(createJavaJavaReader)
      case _ => None
    }

    def collectFileTypes (root :File) = {
      val rootPath = root.getCanonicalPath
      def suffix (name :String) = {
        val didx = name.lastIndexOf(".")
        if (didx == -1) ""
        else name.substring(didx+1)
      }
      def collect (file :File) :Set[String] = {
        // skip files that symlink out of the project root (half-assed way of avoiding loops)
        if (!file.getCanonicalPath.startsWith(rootPath)) Set()
        else if (file.isDirectory) file.listFiles.toSet flatMap(collect)
        else Set(suffix(file.getName))
      }
      collect(root)
    }

    def truncate (text :String, length :Int) =
      if (text.length <= length) text
      else text.substring(0, length) + "..."
  }
}
