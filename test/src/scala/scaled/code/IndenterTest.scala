//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.code

import org.junit.Assert._
import org.junit._
import scaled._

class IndenterTest {

  val testJavaCode = Seq(
    //                1         2         3         4         5         6         7         8
    //      012345678901234567890123456789012345678901234567890123456789012345678901234567890123456
    /* 0*/ "package foo;",
    /* 1*/ "",
    /* 2*/ "/** This is some test Java code that we'll use to test things, like in this case",
    /* 3*/ " * the indenter state assignment code.",
    /* 4*/ " * @see jack run",
    /* 5*/ " */",
    /* 6*/ "public class Test {",
    /* 7*/ "   /**",
    /* 8*/ "    * A constructor, woo!",
    /* 9*/ "    * @param foo for fooing.",
    /*10*/ "    * @param bar for barring.",
    /*11*/ "    */",
    /*12*/ "   public Test () {",
    /*13*/ "     System.out.println(\"Now is the time for all good men to come \" +",
    /*14*/ "                        \"to the aid of their country.\");",
    /*15*/ "     System.out.println(",
    /*16*/ "       \"Every good boy deserves fudge. And perhaps other things too.\");",
    /*17*/ "   }",
    /*18*/ "",
    /*19*/ "   /**",
    /*20*/ "    * A method. How exciting. Let's {@link Test} to something.",
    /*21*/ "    * @throws IllegalArgumentException if we feel like it.",
    /*22*/ "    */",
    /*23*/ "   @Deprecated(\"Use peanuts\")",
    /*24*/ "   public void test (int count, int foo, int bar, int baz,",
    /*25*/ "                     int bingle, int bangle) {}",
    /*26*/ "}")

  @Test def testStateCalc () :Unit = {
    val buf = Buffer.scratch("Test.java")
    buf.append(testJavaCode.map(Line.apply))
    val indenter = new BlockIndenter(Config.testConfig, Seq())
    for (ll <- 0 until buf.lines.length) indenter.apply(buf, ll)
    val sstrs = buf.lines.map(line => line.lineTags.head.toString)
    // for (ll <- 0 until sstrs.length) println(s"$ll -> ${sstrs(ll)}")
    assertEquals("BlockS(}, -1)", sstrs(7))
    assertEquals("BlockS(}, -1) BlockS(}, -1)", sstrs(13))
    assertEquals("ExprS(), 23) BlockS(}, -1) BlockS(}, -1)", sstrs(14));
    assertEquals("ExprS(), 20) BlockS(}, -1)", sstrs(25))
    assertEquals("BlockS(}, -1)", sstrs(26))
  }
}
