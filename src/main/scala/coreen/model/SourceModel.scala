//
// $Id$

package coreen.model

import scala.xml.{Node, NodeSeq, Elem}

import coreen.model.{Def => JDef}

/**
 * Models a source file as a nested collection of definitions and uses.
 */
object SourceModel
{
  /** Tracks a span of characters in a source file. */
  trait Span {
    val name :String
    val start :Int

    def toEdit (source :String) = {
      val nstart = source.indexOf(name, start)
      assert(nstart != -1, "Unable to find name in source: " + name + "@" + start)
      Edit(nstart, nstart + name.length)
    }

    override def toString = name + ":" + start
  }

  case class Edit (start :Int, end :Int)

  /** Models a compilation unit. */
  case class CompUnitElem (var src :String, defs :Seq[DefElem]) {
    def getDef (path :String) :Option[DefElem] = getDef(path split("\\.") toList)
    def getDef (path :List[String]) :Option[DefElem] = defs flatMap(_.getDef(path)) headOption

    /** Returns a sequence containing all defs in this unit. */
    def allDefs :Seq[DefElem] = {
      def flatten (df :DefElem) :Seq[DefElem] = df +: df.defs.flatMap(flatten)
      defs flatMap(flatten)
    }
  }

  /** Models a definition (e.g. class, field, function, method, variable). */
  case class DefElem (name :String, id :String, sig :String, doc :String, kind :Kind,
                      flavor :Flavor, flags :Int, supers :Seq[String],
                      start :Int, bodyStart :Int, bodyEnd :Int,
                      defs :Seq[DefElem], uses :Seq[UseElem]) extends Span {
    def getDef (path :List[String]) :Option[DefElem] = path match {
      case h :: Nil => if (h == name) Some(this) else None
      case h :: t => if (h == name) defs flatMap(_.getDef(t)) headOption else None
      case _ => None
    }

    def toEdits (source :String) :Seq[Edit] =
      defs.flatMap(_.toEdits(source)) ++ uses.map(_.toEdit(source)) :+ toEdit(source)
  }

  /** Models the use of a name somewhere in a source file. */
  case class UseElem (name :String, target :String, start :Int) extends Span {
    override def toString = "@" + super.toString
  }

  def parse (elem :Elem) :CompUnitElem = {
    assert(elem.size == 1 && elem.head.label == "compunit",
           "DOM must be rooted in a single <compunit> element")
    CompUnitElem(
      (elem \ "@src").text,
      parse0(elem.head.child) filter(_.isInstanceOf[DefElem]) map(_.asInstanceOf[DefElem]))
  }

  // TODO: clean up this ugly hack
  protected def parse0 (elem :NodeSeq) :Seq[AnyRef] = {
    elem map(e => e.label match {
      case "def" => mkDef(e, parse0(e.child))
      case "use" => UseElem((e \ "@name").text, (e \ "@target").text, intAttr(e, "start"))
      case x => null // will be #PCDATA or <sig> or <doc> which are handled elsewhere
    })
  }

  protected def mkDef (elem :Node, children :Seq[AnyRef]) :DefElem =
    DefElem((elem \ "@name").text, (elem \ "@id").text, (elem \ "sig").text, (elem \ "doc").text,
            parseKind(elem), parseFlavor(elem), parseFlags(elem), parseSupers(elem),
            intAttr(elem, "start"), intAttr(elem, "bodyStart"), intAttr(elem, "bodyEnd"),
            children filter(_.isInstanceOf[DefElem]) map(_.asInstanceOf[DefElem]),
            children filter(_.isInstanceOf[UseElem]) map(_.asInstanceOf[UseElem]))

  protected def parseKind (elem :Node) = {
    val text = (elem \ "@kind").text
    try {
      Enum.valueOf(classOf[Kind], text.toUpperCase)
    } catch {
        case e => println(elem + " -> " + e); Kind.UNKNOWN
    }
  }

  protected def parseFlavor (elem :Node) = {
    val text = (elem \ "@flavor").text
    try {
      Enum.valueOf(classOf[Flavor], text.toUpperCase)
    } catch {
        case e => e.printStackTrace; println(elem + " -> " + e); Flavor.NONE
    }
  }

  protected def parseFlags (elem :Node) = {
    if ((elem \ "@access").text.equalsIgnoreCase("public")) JDef.PUBLIC else 0
  }

  protected def parseSupers (elem :Node) = (elem \ "@supers").text.trim match {
    case "" => List()
    case ids => ids split(" ") map(_.trim) toList
  }

  protected def intAttr (elem :Node, attr :String) = {
    val text = (elem \ ("@" + attr)).text.trim
    try if (text.length == 0) 0 else text.toInt
    catch {
      case e :NumberFormatException => println(
        "Bogus element? " + attr + " -> " + text + " in " + elem); 0
    }
  }
}