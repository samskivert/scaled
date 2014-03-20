//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled.impl

import scala.annotation.tailrec

import reactual.{Signal, SignalV}

import scaled._

/** [MutableLine] related types and utilities. */
object MutableLine {

  /** The number of extra characters padded onto an expanded line. */
  final val ExpandN = 32

  /** An empty character array used for edits that delete no characters. */
  final val NoChars = Array[Char]()

  /** Creates a mutable line with a copy of the contents of `line`. */
  def apply (buffer :BufferImpl, line :LineV) = {
    val cs = new Array[Char](line.length)
    val ss = new Array[Styles](line.length)
    line.sliceInto(0, line.length, cs, ss, 0)
    new MutableLine(buffer, cs, ss)
  }
}

/** [LineV] with mutable internals so that `BufferImpl` can efficiently edit it.
  *
  * Note: most of the mutation methods take a [[Loc]] instead of an `Int` to indicate the offset
  * into the line at which the edit is to take place. This loc contains the to-be-edited line's
  * current `row` in addition to the line offset (`col`). This is because edits must emit an edited
  * event which contains the row at which the line in question resides. The buffer knows the line's
  * current row at the time that it makes the edit, so it passes that information in, along with
  * the line position at which the edit is to take place. This also often allows the `Loc` that
  * initiated an edit to be reused all the way through, without further allocation. We also prefer
  * that the line emit this event rather than the buffer, so that the caller can't "forget" to emit
  * an event along with a line edit.
  *
  * @param initChars The initial characters in this line. Ownership of this array is taken by this
  * line instance and the array may subsequently be mutated thereby.
  */
class MutableLine (buffer :BufferImpl, initCs :Array[Char], initSs :Array[Styles]) extends LineV {
  def this (buffer :BufferImpl, initCs :Array[Char]) =
    this(buffer, initCs, Array.fill(initCs.length)(Styles.None))

  require(initCs != null && initSs != null)

  private var _chars = initCs
  private var _styles = initSs
  private var _end = initCs.size

  override def length = _end
  override def charAt (pos :Int) = if (pos < _end) _chars(pos) else 0
  override def stylesAt (pos :Int) = if (pos < _end) _styles(pos) else Styles.None
  override def view (start :Int, until :Int) = new Line(_chars, _styles, start, until-start)
  override def slice (start :Int, until :Int) =
    new Line(_chars.slice(start, until), _styles.slice(start, until))
  override def sliceInto (start :Int, until :Int, cs :Array[Char], ss :Array[Styles], offset :Int) {
    System.arraycopy(_chars, start, cs, offset, until-start)
    System.arraycopy(_styles, start, ss, offset, until-start)
  }
  override def sliceString (start :Int, until :Int) = new String(_chars, start, until-start)
  override def asString :String = new String(_chars, 0, _end)

  /** The array that contains this line's characters. It's size may exceed `length` for reasons of
    * efficiency. Be sure to use [length], not `chars.length`. */
  def chars :Array[Char] = _chars

  /** Splits this line at `loc`. Deletes the data from `loc.col` onward from this line.
    * @return a new line which contains the data from `loc.col` onward. */
  def split (loc :Loc) :MutableLine = {
    // TODO: if loc.col is close to zero, just give our internals to the new line and create new
    // internals for ourselves?
    MutableLine(buffer, delete(loc, _end-loc.col))
  }

  /** Inserts `c` into this line at `loc` with styles `styles`. */
  def insert (loc :Loc, c :Char, styles :Styles) {
    prepInsert(loc.col, 1)
    _chars(loc.col) = c
    _styles(loc.col) = styles
    _end += 1
    buffer.noteLineEdited(loc, Line.Empty, 1)
  }

  /** Inserts `[offset, offset+count)` slice of `line` into this line at `pos` <em>without emitting
    * an edited event</em>. */
  def insertSilent (pos :Int, line :LineV, offset :Int, count :Int) {
    prepInsert(pos, count)
    line.sliceInto(offset, offset+count, _chars, _styles, pos)
    _end += count
  }

  /** Inserts `[offset, offset+count)` slice of `line` into this line at `loc`. */
  def insert (loc :Loc, line :LineV, offset :Int, count :Int) :Loc = {
    insertSilent(loc.col, line, offset, count)
    buffer.noteLineEdited(loc, Line.Empty, count)
    loc + (0, count)
  }

  /** Inserts `line` into this line at `loc`. */
  def insert (loc :Loc, line :LineV) :Loc = insert(loc, line, 0, line.length)

  /** Appends `line` to this line. */
  def append (loc :Loc, line :LineV) :Unit = insert(loc.atCol(_end), line)

  /** Deletes `length` chars from this line starting at `loc`.
    * @return the deleted chars as a line. */
  def delete (loc :Loc, length :Int) :Line = {
    val pos = loc.col
    val last = pos + length
    require(pos >= 0 && last <= _end)
    val deleted = slice(pos, pos+length)
    System.arraycopy(_chars, last, _chars, pos, _end-last)
    System.arraycopy(_styles, last, _styles, pos, _end-last)
    _end -= length
    buffer.noteLineEdited(loc, deleted, 0)
    deleted
  }

  /** Replaces `delete` chars starting at `loc` with `line`. */
  def replace (loc :Loc, delete :Int, line :LineV) :Line = {
    val pos = loc.col
    val lastDeleted = pos + delete
    require(lastDeleted <= _end)
    val added = line.length
    val lastAdded = pos + added
    val replaced = if (delete > 0) slice(pos, pos+delete) else Line.Empty

    val deltaLength = lastAdded - lastDeleted
    // if we have a net increase in characters, shift tail right to make room
    if (deltaLength > 0) prepInsert(pos, deltaLength)
    // if we have a net decrease, shift tail left to close gap
    else if (deltaLength < 0) {
      val toShift = _end-lastDeleted
      System.arraycopy(_chars, lastDeleted, _chars, lastAdded, toShift)
      System.arraycopy(_styles, lastDeleted, _styles, lastAdded, toShift)
    }
    // otherwise, we've got a perfect match, no shifting needed

    line.sliceInto(0, added, _chars, _styles, pos)
    _end += deltaLength
    buffer.noteLineEdited(loc, replaced, added)
    replaced
  }

  /** Adds or removes `style` (based on `add`) starting at `loc` and continuing to column `last`. If
    * any characters actually change style, a call to [[BufferImpl.noteLineStyled]] will be made
    * after the style has been applied to the entire region. */
  def updateStyle (style :String, add :Boolean, loc :Loc, last :Int) {
    val end = math.min(length, last)
    @tailrec def loop (pos :Int, first :Int) :Int = {
      if (pos == end) first
      else {
        val ostyles = _styles(pos)
        val nstyles = if (add) ostyles + style else ostyles - style
        if (nstyles eq ostyles) loop(pos+1, first)
        else {
          _styles(pos) = nstyles
          loop(pos+1, if (first == -1) pos else first)
        }
      }
    }
    val first = loop(loc.col, -1)
    if (first != -1) buffer.noteLineStyled(loc.atCol(first))
  }

  override def toString () = s"$asString/${_end}/${_chars.length}"

  //
  // impl details

  private def prepInsert (pos :Int, length :Int) {
    require(pos >= 0 && pos <= _end)
    val curlen = _chars.length
    val curend = _end
    val tailpos = pos+length
    val taillen = curend-pos
    // if we need to expand our arrays...
    if (curend + length > curlen) {
      // ...tack on an extra N characters in expectation of future expansions
      val nchars = new Array[Char](_chars.length+length + MutableLine.ExpandN)
      System.arraycopy(_chars, 0, nchars, 0, pos)
      System.arraycopy(_chars, pos, nchars, tailpos, taillen)
      val nstyles = new Array[Styles](nchars.length)
      System.arraycopy(_styles, 0, nstyles, 0, pos)
      System.arraycopy(_styles, pos, nstyles, tailpos, taillen)
      _chars = nchars
      _styles = nstyles
    }
    // otherwise shift characters down, if necessary
    else if (pos < curend) {
      System.arraycopy(_chars, pos, _chars, tailpos, taillen)
      System.arraycopy(_styles, pos, _styles, tailpos, taillen)
    }
  }
}
