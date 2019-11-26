//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.major

import scaled._

@Major(name="log", tags=Array("log"), desc="""
  A major mode for displaying log text. Motion commands are available but editing commands are not.
""")
class LogMode (env :Env) extends ReadingMode(env) {

  // mark our buffer as uneditable
  buffer.editable = false

  // start the point at the bottom of the buffer
  view.point() = buffer.end

  override def keymap = super.keymap.
    bind("clear-log", "M-k");

  @Fn("Clears the contents of this log buffer.")
  def clearLog () :Unit = {
    buffer.delete(buffer.start, buffer.end)
  }
}
