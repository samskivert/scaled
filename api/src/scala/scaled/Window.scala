//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled

/** Represents a top-level editor window. Every editor window contains one or more frames.
  * A window starts with a single frame, consuming all of its space, but those frames can be split
  * horizontally or vertically to introduce more frames into a window.
  */
trait Window {

  /** A single frame in a window. */
  trait Frame {

    /** Returns the current geometry of this frame. */
    def geometry :Geometry

    /** Returns the window that contains this frame. */
    def window :Window = Window.this

    /** Returns the buffer view that currently occupies this frame. */
    def view :BufferView

    /** Returns the store for the buffer edited previous to the current buffer in this frame.
      * This mainly exists to make it easy to bounce between two buffers in a given frame via
      * `switch-to-buffer`. */
    def prevStore :Option[Store]

    /** Makes the specified buffer the active buffer for this frame.
      * @return the view for the buffer. */
    def visit (buffer :Buffer) :BufferView

    /** Opens a buffer for `store` and visits it.
      * @return the view for the buffer. */
    def visitFile (store :Store) = visit(window.workspace.openBuffer(store))
  }

  /** A reactive mapping of window-wide state. */
  val state :State = new State()

  /** The active [[Visit.List]] (if any). */
  val visits = OptValue[Visit.List]()

  /** A stack of visits made from within this window. */
  val visitStack = new Visit.Stack()

  /** A signal emitted when this window is closed. */
  def onClose :SignalV[Window]

  /** Returns the current geometry of this window. */
  def geometry :Geometry

  /** Returns the of list frames currently in this window. */
  def frames :SeqV[Frame]

  /** Returns the frame that currently has the focus. */
  def focus :Frame

  /** The workspace which owns this window. */
  def workspace :Workspace

  /** Closes this window. When all windows in all workspaces are closed, the process will exit. */
  def close () :Unit

  /** Reports an unexpected error to the user.
    * The message will also be appended to the `*messages*` buffer. */
  def emitError (err :Throwable) :Unit

  /** Briefly displays a status message to the user in a popup.
    * The status message will also be appended to the `*messages*` buffer. */
  def popStatus (msg :String, subtext :String = "") :Unit

  /** Briefly displays a status message to the user.
    * @param ephemeral if false, the status message will also be appended to the `*messages*`
    * buffer; if true, it disappears forever in a poof of quantum decoherence. */
  def emitStatus (msg :String, ephemeral :Boolean = false) :Unit

  /** Clears any lingering status message. A status message usually remains visible until the user
    * types the next key, so this allows any buffer which receives key input to clear the last
    * status message. */
  def clearStatus () :Unit

  /** Provides access to the overlay popup minibuffer. Prefer this for most interactions. */
  def mini :Minibuffer

  /** Provides access to the status-line minibuffer. Use this only when the minibuffer interaction
    * requires the user to see the contents of the main buffer, and hence the popup minibuffer
    * would potentially obscure important data. */
  def statusMini :Minibuffer

  /** Requests that this window be brought to the front of the window stack by the OS. */
  def toFront () :Unit
}

/** Describes the geometry of a [[Window]] or [[Frame]].
  * @param width the width in characters of the referent.
  * @param height the height in characters of the referent.
  * @param x in the case of a window, this is the number of pixels from the left side of the
  * screen at which the window is positioned; in the case of a frame, this is the number of
  * characters from the left side of the containing window at which the frame is positioned.
  * @param y in the case of a window, this is the number of pixels from the top of the screen at
  * which the window is positioned; in the case of a frame, this is the number of characters from
  * the top of the containing window at which the frame is positioned.
  */
case class Geometry (width :Int, height :Int, x :Int, y :Int) {
  override def toString = s"${width}x$height+$x+$y"
}
