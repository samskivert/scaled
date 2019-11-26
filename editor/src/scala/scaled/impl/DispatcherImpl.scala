//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.impl

import java.lang.reflect.InvocationTargetException
import javafx.scene.input.{KeyCode, KeyEvent}
import scaled._

/** Handles the conversion of key presses into execution of the appropriate fns. This includes
  * parsing text trigger sequences into efficient internal structures, mapping trigger sequences to
  * bound fns based on a mode's key bindings, and handling incoming key events and resolving them
  * into trigger sequences.
  *
  * @param majorMode the major mode, which defines our primary key mappings.
  * Minor modes are added (and removed) dynamically, but a dispatcher's major mode never changes.
  */
class DispatcherImpl (window :WindowImpl, resolver :ModeResolver, view :BufferViewImpl,
                      mline :ModeLine, majorMode :String, modeArgs :List[Any], tags :List[String])
    extends Dispatcher {

  private val isModifier = Set(KeyCode.SHIFT, KeyCode.CONTROL, KeyCode.ALT, KeyCode.META,
                               KeyCode.COMMAND, KeyCode.WINDOWS)

  private val _willInvoke = Signal[String]()
  private val _didInvoke = Signal[String]()
  private val _didHandle = Signal[String]()
  private var _curFn :String = _
  private var _prevFn :String = _

  private var _majorMeta :MajorModeMeta = _
  private val _minors = Value[Seq[MinorMode]](Seq.empty)
  private var _metas = List[ModeMeta]() // the stack of active modes (major last)
  private var _prefixes = Set[Seq[KeyPress]]() // union of cmd prefixes from active modes' keymaps

  private var _trigger = Seq[KeyPress]()
  private var _dispatchTyped = false
  private var _escapeNext = false

  private var _minorStateResolver :Connection = Connection.Noop

  /** The buffer area for which we're dispatching events. */
  val area = createBufferArea()

  /** The major mode with which we interact. */
  val major = Value[MajorMode](if (fallbackToText) resolveMajorOrText else resolveMajor)

  /** The current configured set of minor modes. */
  val minors :ValueV[Seq[MinorMode]] = _minors

  // configure our major mode
  major onValueNotify { major =>
    val hadMajor = (_majorMeta != null)
    // all old modes need to be cleaned out, and applicable minor modes re-resolved
    _metas foreach { _.dispose(false, false) }
    // set up our new major mode
    _majorMeta = new MajorModeMeta(major)
    _metas = List(_majorMeta)

    // activate any minor modes that match our major mode's tags and buffer state
    val allTags = (major.tags ++ tags).toSeq
    maybeAddMinors(resolver.tagMinorModes(allTags))
    modesChanged()

    // if we were replacing an existing major mode, give feedback to the user
    if (hadMajor) window.popStatus("${major.name} activated.")

    // if the buffer state changes, maybe add new minor modes
    _minorStateResolver.close()
    _minorStateResolver = view.buffer.state.keyAdded.onEmit {
      if (maybeAddMinors(resolver.tagMinorModes(allTags)) > 0) {
        modesChanged()
      }
    }
  }

  private def maybeAddMinors (minors :Unordered[String]) :Int = {
    var count = 0
    val activeModes = _metas.map(_.name).toSet
    val iter = minors.iterator ; while (iter.hasNext) {
      val mode = iter.next
      if (!activeModes.contains(mode)) try {
        resolver.resolveMinor(mode, view, mline, this, major(), Nil) ifDefined { minst =>
          addMode(false)(minst)
          count += 1
        }
      }
      catch {
        case e :Throwable =>
          window.emitError(e)
          window.emitStatus(s"Failed to resolve minor mode '$mode'. See *messages* for details.")
      }
    }
    count
  }

  /** Processes the supplied key event, dispatching a fn if one is triggered thereby. */
  def keyPressed (kev :KeyEvent) :Unit = {
    // this is a hacky workaround for LINUX where the CapsLock key is reported as a CAPS keypress
    // by JavaFX even if it has been remapped to be a Control key; sigh
    if (kev.getCode() == KeyCode.CAPS) return

    kev.getEventType match {
      case KeyEvent.KEY_PRESSED =>
        // if this is a modifier key press, ignore it; wait for the modified key press
        if (isModifier(kev.getCode)) ()
        // if we get a new press when we're expecting a typed, then the user is holding down a
        // non-printing key (like an arrow key) and we're getting repeated presses; so handle
        // reporting the missed fn here
        else if (_dispatchTyped) invokeMissed()
        else {
          val key = KeyPress.fromPressed(kev).metafy(_escapeNext)
          // we handle ESC specially; it causes the next key press to be modified with meta
          if (key.id == "ESC" && !key.isModified) {
            deferDisplayPrefix(_trigger :+ key)
            _escapeNext = true
          }
          else {
            // tack this key onto our currently accumulating trigger
            _trigger :+= key
            // if it matches a known command prefix, wait for the rest of the command to come in
            if (_prefixes(_trigger)) deferDisplayPrefix(_trigger)
            else {
              // otherwise resolve the fn(s) bound to this trigger (if any)
              val fns = resolve(_trigger, _metas)
              if (!fns.isEmpty && !fns.head.wantsTyped) invoke("pressed", fns, _trigger.last.text)
              // if we don't find one (or if the fn we found wants the typed character),
              // wait until the associated key typed event comes in
              else _dispatchTyped = true
            }
          }
        }

      case KeyEvent.KEY_TYPED =>
        if (_dispatchTyped) {
          // create a press from the typed key (which will use the UTF8 character provided by the OS
          // rather than the character "printed on the key" to to speak); in this case we ignore
          // modifiers because the modifiers were interpreted by the OS to determine the typed key
          // (except META which seems not to influence the typed character; TODO: learn more)
          val typed = kev.getCharacter
          if (typed.length > 0 && !Character.isISOControl(typed.charAt(0))) {
            val key = new KeyPress(typed, typed, shift=false, ctrl=false, alt=false,
                                   meta=kev.isMetaDown).metafy(_escapeNext)
            _trigger = _trigger.dropRight(1) :+ key
          }
          val defFn = if (_trigger.size > 1 || !_trigger.last.isPrintable) None
                      else _majorMeta.defaultFn
          val fns = resolve(_trigger, _metas)
          if (!fns.isEmpty) invoke("typed", fns, _trigger.last.text)
          else if (defFn.isDefined) invoke("typed", defFn.toList, _trigger.last.text)
          else invokeMissed()
        }

      case KeyEvent.KEY_RELEASED =>
        // if we get here and _dispatchTyped is true, that means we expected a KEY_TYPED event to
        // come in, but none did, so we need to treat the last key press like a missed fn
        if (_dispatchTyped) invokeMissed()
    }

    // consume all key events so that they don't percolate up and misbehave
    kev.consume()
  }

  /* Called when this dispatcher and all of its associated machinery should go away. */
  def dispose (bufferDisposing :Boolean) :Unit = {
    view.dispose()
    _metas foreach(_.dispose(true, bufferDisposing))
    _metas = Nil // render ourselves useless
    _prefixes = Set()
    _minorStateResolver.close()
  }

  override def willInvoke = _willInvoke
  override def didInvoke = _didInvoke
  override def didHandle = _didHandle
  override def curFn = _curFn
  override def prevFn = _prevFn

  override def fns = (Set[String]() /: _metas) { _ ++ _.fns.bindings.map(_.name) }
  override def describeFn (fn :String) = findFn(fn).headOption map(_.descrip)
  override def majorModes = resolver.modes(true)
  override def minorModes = resolver.modes(false)

  override def modes = _metas.map(_.mode)

  override def toggleMode (mode :String) :Unit = {
    _metas map(_.mode) find(_.name == mode) match {
      case Some(minor :MinorMode) =>
        removeMode(minor)
        window.popStatus(s"$mode mode deactivated.")
      case _ =>
        resolver.resolveMinor(mode, view, mline, this, major(), Nil) ifDefined addMode(true)
    }
  }

  override def invoke (fn :String) = findFn(fn) match {
    case Nil => false
    case fns => invoke("invoke", fns, "") ; true
  }

  override def press (trigger :String) :Unit = {
    KeyPress.toKeyPresses(trigger) match {
      case Right(kps) =>
        val fns = resolve(kps, _metas)
        if (fns.isEmpty) invokeMissed(trigger)
        else invoke("press", fns, kps.last.text)
      case Left(errs) => window.popStatus(
        s"Invalid keys in '$trigger': ${errs.mkString(", ")}")
    }
  }

  /** Factored out because we need some custom fiddling in places. Blah. */
  protected def createBufferArea () = new BufferArea(view, this)

  /** Whether or not we fall back to text-mode if we fail to resolve our desired major mode.
    * Usually this is desired, but in the case of the mini-buffer, that just causes new problems,
    * so we just want unexpected failure to propagate out to the mini-buffer creator. */
  protected def fallbackToText = true

  private def resolveMajorOrText = try resolveMajor catch {
    case t :Throwable =>
      window.emitError(t)
      resolver.resolveMajor("text", view, mline, this, Nil) // fall back to text mode
  }
  private def resolveMajor = resolver.resolveMajor(majorMode, view, mline, this, modeArgs)

  private def addMode (interactive :Boolean)(mode :MinorMode) :Unit = {
    _metas = new ModeMeta(mode) :: _metas
    if (interactive) {
      modesChanged()
      window.popStatus(s"${mode.name} mode activated.")
    }
  }

  private def removeMode (minor :MinorMode) :Unit = {
    _metas = _metas.filterNot { mm =>
      if (mm.mode == minor) { mm.dispose(false, false); true }
      else false
    }
    modesChanged()
  }

  private def modesChanged () :Unit = {
    // rebuild our command prefixes
    _prefixes = _metas.map(_.prefixes).reduce(_ ++ _)
    // rebuild and emit our list of active minor modes
    _minors() = _metas.map(_.mode).toSeq.collect { case mode :MinorMode => mode }
  }

  private def findFn (fn :String) :List[FnBinding] = _metas.flatMap(_.fns.binding(fn))
  private def resolve (trigger :Seq[KeyPress], modes :List[ModeMeta]) :List[FnBinding] =
    modes.flatMap(_.map.get(trigger))

  private def invoke (from :String, fns :List[FnBinding], typed :String) :Boolean = {
    var ll = fns ; while (!ll.isEmpty) {
      val fn = ll.head
      // println(s"invoking ($from) $fn on '$typed' (${typed.map(_.toInt)})")

      // prepare to invoke our fn
      _curFn = fn.name
      window.clearStatus()
      view.buffer.undoStack.delimitAction(view.point())
      _willInvoke.emit(fn.name)

      val res = try fn.invoke(typed)
      catch {
        case e :InvocationTargetException => window.emitError(e.getCause) ; false
      }

      // finish up after invoking our fn
      _prevFn = _curFn
      _curFn = null
      _didInvoke.emit(fn.name)
      view.buffer.undoStack.delimitAction(view.point())
      didInvokeFn()

      // if the fn returned a future, listen for errors on it and report them to the window
      res match {
        case future :Future[_] => future.onFailure(window.emitError)
        case _ => // never mind
      }

      // if the fn returns anything other than false, we assume it handled the key
      if (res != java.lang.Boolean.FALSE) {
        _didHandle.emit(fn.name)
        return true
      }

      // otherwise try the next fn in the list
      ll = ll.tail
    }
    false
  }

  private def invokeMissed () :Unit = invokeMissed(_trigger.mkString(" "))
  private def invokeMissed (trigger :String) :Unit = _majorMeta.missedFn match {
    case Some(fn) => invoke("missed", fn :: Nil, trigger)
    case None     => _prevFn = null ; didInvokeFn()
  }

  private def didInvokeFn () :Unit = {
    _trigger = Seq()
    _dispatchTyped = false
    _escapeNext = false
  }

  /** Sets a timer that displays the current command prefix in the minibuffer after a short delay.
    * Thus if a user types a command prefix, we wait for the rest of the command, but we also
    * eventually provide some feedback as to what's going on in case they did it unwittingly. */
  private def deferDisplayPrefix (trigger :Seq[KeyPress]) :Unit = {
    window.emitStatus(trigger.mkString(" "), true)
  }

  private class ModeMeta (val mode :Mode) {
    def name = mode.name
    val fns = new FnBindings(mode, m => window.emitStatus(m))
    val map :Map[Seq[KeyPress],FnBinding] = {
      // if fn has a "mode:" prefix, resolve fn via that named mode rather than via this mode
      val fnMap = Map(_metas.map(m => m.name -> m.fns))
      def binding (fn :String) = fn.indexOf(":") match {
        case -1 => fns.binding(fn) orLeft s"Unknown fn in '$name-mode' keymap: '$fn'"
        case ii => val tmode = fn.substring(0, ii) ; fnMap.get(tmode) match {
          case None =>
            Left(s"Unknown target mode in '$name-mode' key binding: '$tmode' in '$fn'")
          case Some(fns) =>
            val tfn = fn.substring(ii+1)
            fns.binding(tfn) orLeft s"Unknown fn in '$name-mode' key binding: '$tfn' in '$fn'"
        }
      }
      val mb = Map.builder[Seq[KeyPress],FnBinding]()
      for (kb <- mode.keymap.bindings) KeyPress.toKeyPresses(kb.trigger) match {
        case Left(kerrs) => window.emitStatus(
          s"Invalid key(s) in '$name-mode' keymap: '${kerrs.mkString(", ")}'")
        case Right (kps) => binding(kb.fn) match {
          case Left(err) => window.emitStatus(err)
          case Right(fb) => mb.put(kps, fb)
        }
      }
      mb.build()
    }

    // enumerate all prefix sequences (we use these when processing key input)
    val prefixes :Set[Seq[KeyPress]] = map.keySet.map(_.dropRight(1)).filter(!_.isEmpty)
    // TODO: report an error if a key prefix is bound to an fn? WDED?

    // add this mode's stylesheet (if any) to the window
    private def addSheets (sheets :List[String]) :Unit = if (!sheets.isEmpty) {
      addSheets(sheets.tail)
      area.getStylesheets.add(sheets.head)
    }
    // TODO: we want to remove the global user stylesheets, add these mode sheets, and then readd
    // the global user sheets (so that the global user sheets are always last)
    addSheets(mode.stylesheets)

    def dispose (windowDisposing :Boolean, bufferDisposing :Boolean) :Unit = {
      // if the window is not going away...
      if (!windowDisposing) {
        // remove this mode's stylesheet (if any) from the window
        mode.stylesheets.foreach { ss => area.getStylesheets.remove(ss) }
      }
      // if the buffer is not going away...
      if (!bufferDisposing) {
        // tell the mode that it's being deactivated before we dispose it
        mode.deactivate()
      }
      mode.dispose()
    }
  }

  private class MajorModeMeta (mode :MajorMode) extends ModeMeta(mode) {
    val defaultFn :Option[FnBinding] = mode.defaultFn.flatMap(fns.binding)
    val missedFn :Option[FnBinding] = mode.missedFn.flatMap(fns.binding)
  }
}
