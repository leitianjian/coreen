//
// $Id$

package coreen.java

import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

/**
 * Tests the Java to name-resolved source translator.
 */
class ReaderSpec extends FlatSpec with ShouldMatchers
{
  val testA = """
    public class TestA {
        public static class A {
            public int value;
        }
        public static class B {
            public void noop () {
            }
        }
        public static void main (String[] args) {
            int av = new A().value;
            B b = new B();
            b.noop();
        }
    }"""

  "Reader" should "handle this code" in {
    val outer = Reader.process("TestA.java", testA).head
    println(outer)
    (outer \ "@name").text should equal("TestA")

    val innerA  = (outer \ "def").head
    (innerA \ "@name").text should equal("A")
    (innerA \ "def" \ "@name").text should equal("value")
    (innerA \ "def" \ "ref" \ "@target").text should equal("int")

    val innerB = (outer \ "def").tail.head
    (innerB \ "@name").text should equal("B")
    (innerB \ "def" \ "@name").text should equal("noop")
  }
}
