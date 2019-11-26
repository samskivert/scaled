//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.minor

import scala.collection.mutable.{Set => MSet}

import scaled._
import scaled.util.{Behavior, Chars}

object WhitespaceConfig extends Config.Defs {

  @Var("If true, trailing whitespace will be highlighted.")
  val showTrailingWhitespace = key(true)

  @Var("If true, trailing whitespace will be trimmed from a line when a newline is inserted.")
  val trimTrailingWhitespace = key(true)

  @Var("""If true and the buffer lacks a trailing newline, a newline will be appended to the buffer
          before it is saved.""")
  val requireTrailingNewline = key(true)

  /** The CSS style applied to trailing whitespace characters. */
  val trailingStyle = "whitespaceTrailingFace"
}

@Minor(name="whitespace", tags=Array("text", "code"),
       desc="""Provides whitespace manipulation fns; highlights undesirable whitespace.""")
class WhitespaceMode (env :Env) extends MinorMode(env) {
  import WhitespaceConfig._
  import Chars._

  addBehavior(showTrailingWhitespace, new Behavior() {
    private val _rethinkLines = MSet[Int]()

    override protected def activate () :Unit = {
      // respond to buffer edits
      note(buffer.edited onValue { edit =>
        queueRethink(edit.start.row until edit.end.row :_*)
      })
      // when the point moves, the line it left may now need highlighting and the line it moves to
      // may no longer need highlighting
      note(view.point onChange { (p, op) => queueRethink(op.row, p.row) })
      // note existing trailing whitespace
      0 until buffer.lines.size foreach tagTrailingWhitespace
      // TODO: defer marking trailing whitespace on non-visible lines until they're scrolled into
      // view, we can probably do this entirely in client code using RBufferView.scrollTop and
      // RBufferView.heightV; encapsulate it in a Colorizer helper class?
    }

    override protected def didDeactivate (bufferDisposing :Boolean) :Unit = {
      if (!bufferDisposing) buffer.removeStyle(trailingStyle, buffer.start, buffer.end)
    }

    private def queueRethink (row :Int*) :Unit = {
      val takeAction = _rethinkLines.isEmpty
      _rethinkLines ++= row
      if (takeAction) window.exec.runOnUI(rethink)
    }

    private def rethink () :Unit = {
      _rethinkLines foreach tagTrailingWhitespace
      _rethinkLines.clear()
    }

    // we might have queued a line for a rethink that then disappeared, so be sure that the line
    // we're rethinking is still part of the buffer
    private val tagTrailingWhitespace = (ii :Int) => if (ii < buffer.lines.length) {
      val line = buffer.lines(ii)
      val limit = if (view.point().row == ii) view.point().col else 0
      @tailrec def seek (col :Int) :Int = {
        if (col == limit || isNotWhitespace(line.charAt(col-1))) col
        else seek(col-1)
      }
      val last = line.length
      val first = seek(last)
      val floc = Loc(ii, first)
      if (first > 0) buffer.removeStyle(trailingStyle, Loc(ii, 0), floc)
      if (first < last) buffer.addStyle(trailingStyle, floc, Loc(ii, last))
    }
  })

  addBehavior(requireTrailingNewline, new Behavior() {
    override protected def activate () :Unit = {
      note(buffer.willSave onValue { buf =>
        if (buf.lines.last.length > 0) buf.split(buf.end)
      })
    }
  })

  override def keymap = super.keymap.
    bind("trim-whitespace-then-newline", "ENTER", "S-ENTER").
    bind("trim-buffer-trailing-whitespace", "C-M-]");
  override def configDefs = WhitespaceConfig :: super.configDefs
  override def stylesheets = stylesheetURL("/whitespace.css") :: super.stylesheets

  def trimTrailingWhitespaceAt (row :Int) :Unit = {
    val line = buffer.line(row) ; val len = line.length ; val last = len-1
    var pos = last ; while (pos >= 0 && isWhitespace(line.charAt(pos))) pos -= 1
    if (pos < last) buffer.delete(Loc(row, pos+1), Loc(row, len))
  }

  @Fn("""If `trim-trailing-whitespace` is enabled, trims whitespace from the current line after
         the current fn has completed.""")
  def trimWhitespaceThenNewline () :Boolean = {
    if (config(trimTrailingWhitespace)) {
      val row = view.point().row
      // defer the trim until the current fn dispatch chain is complete
      window.exec.runOnUI { trimTrailingWhitespaceAt(row) }
    }
    false // always continue dispatch
  }

  @Fn("Trims trailing whitespace from the line at the point.")
  def trimLineTrailingWhitespace () :Unit = {
    trimTrailingWhitespaceAt(view.point().row)
  }

  @Fn("Trims trailing whitespace from all lines in the buffer.")
  def trimBufferTrailingWhitespace () :Unit = {
    0 until buffer.lines.length foreach(trimTrailingWhitespaceAt)
  }
}
