//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled

/** Visualizes a single line of text, potentially with style information. */
trait LineView {

  /** The line being displayed by this view. */
  def line :LineV

  // TODO: margin decorations
  // TOOD: access to the JavaFX scene graph Node on which to anchor bits?
}

/** The visualization of a text buffer. It also manages the UX for manipulating and editing the
  * buffer. This includes:
  * - a series of [LineView] instances visualizing each line of text
  * - the point, which defines the cursor/insertion point and the point end of the point/mark
  * - the scroll position of the view, which indicates which lines of the buffer are visible
  * Anything other than the data model for the buffer itself (which is encapsulated in [Buffer])
  * will be handled by this view.
  */
abstract class BufferView {

  /** The buffer being displayed by this view. */
  def buffer :Buffer

  /** The window in which this buffer view is displayed. */
  def window :Window

  /** Views for the lines in this buffer. */
  def lines :SeqV[LineView]

  /** The current point (aka the cursor position). */
  def point :Property[Loc]

  /** The width of the view, in characters. */
  def width :Property[Int]

  /** The height of the view, in characters. */
  def height :Property[Int]

  /** The index of the line at the top of the view. */
  def scrollTop :Property[Int]

  /** The column index of the character at the left of the view. */
  def scrollLeft :Property[Int]

  /** Adjusts the scroll position of this view by `delta` lines. The scroll position will be bounded
    * based on the size of the buffer. The point will then be bounded into the visible area of the
    * buffer. */
  def scrollVert (delta :Int) :Unit = {
    val ctop = scrollTop()
    val h = height()
    // bound bottom first, then top; this snaps buffers that are less than one screen tall to top
    // TODO: nix buffer.lines.length, use lines.length when lines is implemented
    val ntop = math.max(math.min(ctop + delta, buffer.lines.length - h), 0)
    // println(s"Updating scroll top ($delta ${lines.length} $height) $ctop => $ntop")
    scrollTop() = ntop

    val p = point()
    if (p.row < ntop) point() = p.atRow(ntop)
    else if (p.row >= ntop + h) point() = p.atRow(ntop + h - 1)
  }

  /** Displays `popup` over this buffer, making it the primary popup.
    * Any previous primary popup will be cleared. */
  def showPopup (popup :Popup) :Unit

  /** Adds `popup` over this buffer. Does not affect the primary popup. Can be updated by the
    * caller by updating the returned `OptValue`, or cleared (caller must manually clear) by
    * calling `clear()`. */
  def addPopup (popup :Popup) :OptValue[Popup]
}

/** `BufferView` related types and utilities. */
object BufferView {

  /** An event emitted when lines are added to or removed from the buffer view. The removed lines
    * will have already been removed and the added lines added when this edit is dispatched. */
  case class Change (
    /** The row at which the additions or removals start. */
    row :Int,
    /** If positive, the number of rows added, if negative the number of rows deleted. */
    delta :Int,
    /** The buffer view that was edited. */
    view :BufferView)
}

/** A reactive version of [BufferView], used by modes. */
abstract class RBufferView (initWidth :Int, initHeight :Int) extends BufferView {

  /** The (reactive) buffer being displayed by this view. */
  override def buffer :RBuffer

  /** A signal emitted when lines are added to or removed from this view. */
  def changed :SignalV[BufferView.Change]

  /** The current point (aka the cursor position). */
  val point :Value[Loc] = new Value(Loc(0, 0)) {
    override def update (loc :Loc) :Loc = super.update(buffer.bound(loc))
    override def updateForce (loc :Loc) :Loc = super.updateForce(buffer.bound(loc))
  }

  /** The width of the buffer view, in characters. */
  val width :Value[Int] = Value(initWidth)

  /** The height of the buffer view, in characters. */
  val height :Value[Int] = Value(initHeight)

  /** The index of the line at the top of the view. */
  val scrollTop :Value[Int] = Value(0)

  /** The column index of the character at the left of the view. */
  val scrollLeft :Value[Int] = Value(0)

  /** The popup being displayed by this buffer, if any. */
  val popup :OptValue[Popup] = OptValue()

  override def showPopup (popup :Popup) = this.popup() = popup
}
