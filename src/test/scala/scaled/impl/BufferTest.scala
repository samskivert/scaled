//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled.impl

import java.io.{File, StringReader}

import org.junit._
import org.junit.Assert._

import scaled._

class BufferTest {

  val text = """Who was that man?
    |Now is the time for all good citizens to come to the aid of their country.
    |Every good boy deserves fudge.
    |The quick brown fox jumped over the lazy dog.
    |""".stripMargin

  @Test def testBasics () {
    val buffer = BufferImpl("test", new File(""), new StringReader(text))
    assertEquals(4, buffer.lines.size)
  }

  @Test def testLoc () {
    val buffer = BufferImpl("test", new File(""), new StringReader(text))
    assertEquals(Loc(5, 0, 5), buffer.loc(5))
    assertEquals(Loc(18, 1, 0), buffer.loc(18))
    assertEquals(Loc(22, 1, 4), buffer.loc(22))
    // any offset greater than or equal to the buffer length should resolve to a new blank link at
    // the end of the buffer
    assertEquals(Loc(text.length, 4, 0), buffer.loc(text.length))
    assertEquals(Loc(text.length, 4, 0), buffer.loc(text.length+20))
  }
}
