//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.impl

import java.io.Writer
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
    val xs = new Array[Syntax](line.length)
    val ts = new Tags()
    val lts = new Line.TagSet()
    line.sliceInto(0, line.length, cs, xs, ts, lts, 0)
    new MutableLine(buffer, cs, xs, ts, lts)
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
  * @param cs The initial characters in this line. Ownership of this array is taken by this line
  * instance and the array may subsequently be mutated thereby.
  */
class MutableLine (buffer :BufferImpl, cs :Array[Char], xs :Array[Syntax],
                   tags :Tags, ltags :Line.TagSet) extends LineV with Store.Writable {
  def this (buffer :BufferImpl, cs :Array[Char]) = this(
    buffer, cs, Syntax.mkArray(cs.length, Syntax.Default), new Tags(), new Line.TagSet())

  require(cs != null && xs != null && tags != null)

  protected var _chars = cs
  protected var _syns = xs
  protected def _tags = tags
  protected def _ltags = ltags
  private[this] var _end = cs.length

  override def length = _end
  override def view (start :Int, until :Int) = new Line(
    _chars, _syns, tags, ltags, start, until-start)
  override def slice (start :Int, until :Int) = new Line(
    _chars.slice(start, until), _syns.slice(start, until), tags.slice(start, until), ltags.copy())
  override protected def _offset = 0

  override def write (out :Writer) = out.write(_chars, 0, _end)

  /** Provides access to our tag set for adding/clearing tags. */
  def lineTagSet = ltags

  /** Splits this line at `loc`. Deletes the data from `loc.col` onward from this line.
    * @return a new line which contains the data from `loc.col` onward. */
  def split (loc :Loc) :MutableLine = {
    // TODO: if loc.col is close to zero, just give our internals to the new line and create new
    // internals for ourselves?
    MutableLine(buffer, delete(loc, _end-loc.col))
  }

  /** Inserts `c` into this line at `loc` with syntax `syntax`. */
  def insert (loc :Loc, c :Char, syntax :Syntax) :Unit = {
    prepInsert(loc.col, 1)
    _chars(loc.col) = c
    _syns(loc.col) = syntax
    _end += 1
    _ltags.clearEphemeral()
  }

  /** Inserts `[offset, offset+count)` slice of `line` into this line at `loc`. */
  def insert (loc :Loc, line :LineV, offset :Int, count :Int) :Loc = if (count == 0) loc else {
    prepInsert(loc.col, count)
    line.sliceInto(offset, offset+count, _chars, _syns, tags, ltags, loc.col)
    _end += count
    _ltags.clearEphemeral()
    loc + (0, count)
  }

  /** Inserts `line` into this line at `loc`. */
  def insert (loc :Loc, line :LineV) :Loc = insert(loc, line, 0, line.length)

  /** Appends `line` to this line. */
  def append (loc :Loc, line :LineV) :Unit = insert(loc.atCol(_end), line)

  /** Deletes `length` chars from this line starting at `loc`.
    * @return the deleted chars as a line. */
  def delete (loc :Loc, length :Int) :Line = {
    if (length == 0) Line.Empty
    else {
      val pos = loc.col
      val last = pos + length
      require(pos >= 0 && last <= _end, s"$pos >= 0 && $last <= ${_end}")
      val deleted = slice(pos, pos+length)
      System.arraycopy(_chars , last, _chars , pos, _end-last)
      System.arraycopy(_syns  , last, _syns  , pos, _end-last)
      tags.delete(pos, last)
      _end -= length
      _ltags.clearEphemeral()
      deleted
    }
  }

  /** Replaces `delete` chars starting at `loc` with `line`. */
  def replace (loc :Loc, delete :Int, line :LineV) :Line = {
    val pos = loc.col
    val lastDeleted = pos + delete
    require(lastDeleted <= _end, s"$lastDeleted <= ${_end} in replace($loc, $delete)")
    val added = line.length
    val lastAdded = pos + added
    val replaced = if (delete > 0) slice(pos, pos+delete) else Line.Empty

    val deltaLength = lastAdded - lastDeleted
    // if we have a net increase in characters, shift tail right to make room
    if (deltaLength > 0) prepInsert(pos, deltaLength)
    // if we have a net decrease, shift tail left to close gap
    else if (deltaLength < 0) {
      val toShift = _end-lastDeleted
      System.arraycopy(_chars , lastDeleted, _chars , lastAdded, toShift)
      System.arraycopy(_syns  , lastDeleted, _syns  , lastAdded, toShift)
    }
    // otherwise, we've got a perfect match, no shifting needed

    if (delete > 0) tags.clear(pos, lastDeleted)
    line.sliceInto(0, added, _chars, _syns, tags, ltags, pos)
    _end += deltaLength
    _ltags.clearEphemeral()
    replaced
  }

  /** Transforms chars with `fn` starting at `loc` and continuiing to column `last`. This results in
    * an edited event that reports the transformed characters as deleted and added, regardless of
    * whether `fn` actually changed them.
    * @return the location after the last transformed char. */
  def transform (fn :Char => Char, loc :Loc, last :Int = length) :Loc = {
    var p = loc.col
    while (p < last) { _chars(p) = fn(_chars(p)) ; p += 1 }
    loc.atCol(last)
  }

  /** Adds `tag` to this line. If `tag` is of type `String` then `noteLineStyled` is emitted. */
  def addTag[T] (tag :T, start :Loc, until :Int) :Unit = {
    val scol = start.col
    val ecol = if (until <= length) until else {
      println(s"!!! Truncating line tag: '$tag' $start to $until capped at $length")
      length
    }
    if (ecol > scol) {
      tags.add(tag, scol, ecol)
      if (tag.isInstanceOf[String]) buffer.noteLineStyled(start)
    } // else NOOP!
  }

  /** Removes `tag` to this line. If `tag` is of type `String` and a tag was found and removed, then
    * `noteLineStyled` is emitted. */
  def removeTag[T] (tag :T, start :Loc, until :Int) :Unit = {
    val scol = start.col
    if (until > scol && tags.remove(tag, scol, until) && tag.isInstanceOf[String]) {
      buffer.noteLineStyled(start)
    }
  }

  /** Removes matching tags from this line. If `class` is `String` and at least one tag is removed,
    * then `noteLineStyled` is emitted. */
  def removeTags[T] (tclass :Class[T], pred :T => Boolean, start :Loc, until :Int) :Unit = {
    if (tags.removeAll(tclass, pred, start.col, until) && tclass == classOf[String]) {
      buffer.noteLineStyled(start)
    }
  }

  /** Sets the syntax of chars in `[loc,last)` to `syntax`. */
  def setSyntax (syntax :Syntax, loc :Loc, last :Int = length) :Unit = {
    var p = loc.col ; while (p < last) { _syns(p) = syntax ; p += 1 }
  }

  override def toString () = s"$asString/${_end}/${_chars.length}"

  //
  // impl details

  private def prepInsert (pos :Int, length :Int) :Unit = {
    require(pos >= 0 && pos <= _end, s"0 <= $pos <= ${_end} ($length)")
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
      val nsyns = new Array[Syntax](nchars.length)
      System.arraycopy(_syns, 0, nsyns, 0, pos)
      System.arraycopy(_syns, pos, nsyns, tailpos, taillen)
      _chars = nchars
      _syns = nsyns
    }
    // otherwise shift characters down, if necessary
    else if (pos < curend) {
      System.arraycopy(_chars , pos, _chars , tailpos, taillen)
      System.arraycopy(_syns  , pos, _syns  , tailpos, taillen)
    }
    // we always shift our tags
    _tags.expand(pos, length)
  }
}
