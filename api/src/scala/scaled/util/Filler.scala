//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.util

import scala.annotation.tailrec
import scaled._

/** Simplifies the process of "filling" text. Filling is Emacsese for wrapping text at a particular
  * line width. Simply create a filler with your desired width, [[append]] the lines (or sublines)
  * that you wish to be filled, then do what you will with the [[result]].
  *
  * Note: styles are currently not preserved. Most modes automatically restyle the buffer when it
  * changes anyway, so that's usually not an issue.
  */
class Filler (width :Int) {

  private val accum = SeqBuffer(new StringBuilder())

  /** Appends `line` to this filler. */
  def append (line :CharSequence) :Unit = append(line, true)

  /** Appends `line` to this filler.
    * @param withSpace if true then a space will be inserted before `line` if it is added to a line
    * with existing text.
    */
  def append (line :CharSequence, withSpace :Boolean) :Unit = {
    @inline @tailrec def loop (into :StringBuilder, start :Int, withSpace :Boolean) :Unit = {
      // last break will indicate where we need to rebreak if we overflow
      var lastBreak = start
      // if we're appending to a non-empty line, we may want to insert a space
      var wantSpace = withSpace && (into.length > 0)
      // println(s"loop('$into' st=$start ll=${line.length} ws=$wantSpace)")
      // now append characters from line until we hit EOL or hit width
      var ii = start ; while (into.length < width && ii < line.length) {
        val c = line.charAt(ii)
        if (c == ' ') {
          // note that we want a space before we insert the next non-space char (except if into
          // is empty; then we're skipping leading whitespace)
          wantSpace = (into.length > 0)
          lastBreak = ii+1 // the next rebreak will be after this space
        } else {
          if (wantSpace) into.append(' ')
          into.append(c)
          wantSpace = false
        }
        ii += 1
      }

      // we may have just filled up into and be looking at whitespace; we need to skip that
      // whitespace now otherwise if it's trailing whitespace, we'll end up tacking on a new blank
      // accumulator only to discover that we have nothing to add to it
      var skipped = 0 ; while (ii < line.length && line.charAt(ii) == ' ') {
        skipped += 1 ; ii += 1
      }
      // println(s"looped('$into'/${into.length} ii=$ii sk=$skipped lb=$lastBreak)")

      // if we haven't yet hit EOL, determine whether we overran, lop off the excess, and loop; we
      // also rebreak if we have overflowed into by an extra character; it's possible that we have
      // room for one character but we go to append a character and see that we need a space, so we
      // append two, overflowing 'into', which we need to catch here
      if (ii < line.length || into.length > width) {
        val trim = ii-lastBreak
        // if we ended on a space, or we're trimming nothing or everything, skip trimming
        val next = if (skipped > 0 || trim == 0 || trim >= into.length) ii
                   // otherwise trim the trailing text (and the preceding space)
                   else { into.delete(into.length-trim-1, into.length) ; lastBreak }
        accum += new StringBuilder()
        loop(accum.last, next, true)
      }
    }
    loop(accum.last, 0, withSpace)
  }

  /** The current set of filled lines. */
  def filled :SeqV[StringBuilder] = accum

  /** Converts the accumulated text to a seq of `Line`s. */
  def toLines :Seq[Line] = accum.map(Line.apply)
}

/** [[Filler]] helpers. */
object Filler {

  /** Flattens `text` by replacing line separators with spaces and consolidates consecutive spaces
    * to single spaces. This is useful in preparing "ragged" text for filling.
    */
  def flatten (text :String) :String = {
    val sb = new StringBuilder(text.length)
    var wasSpace = false
    val ll = text.length ; var ii = 0 ; while (ii < ll) {
      val c = text.charAt(ii)
      val wc = if (Character.isWhitespace(c)) ' ' else c
      val isSpace = (wc == ' ')
      if (!isSpace || !wasSpace) sb.append(wc)
      wasSpace = isSpace
      ii += 1
    }
    sb.toString
  }
}
