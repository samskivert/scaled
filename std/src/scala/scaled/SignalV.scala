//
// Reactual - an FRP-ish library for Scala
// Copyright (c) 2013, Michael Bayne - All rights reserved.
// http://github.com/samskivert/reactual/blob/master/LICENSE

package scaled

import java.util.concurrent.Executor

/** A view of a [[Signal]] on which one may listen but via which one cannot emit value. */
class SignalV[+T] extends Reactor {
  import Impl._

  /** Maps the output of this signal via `f`. When this signal emits a value, the mapped signal will
    * emit that value as transformed by `f`. The mapped value will retain a connection to this
    * signal for as long as it has connections of its own.
    */
  def map[M] (f :JFunction[T, M]) :SignalV[M] = new DelegateSignalV[T,M](this) {
    override def onParentValue (value :T) = notifyEmit(f(value));
  }

  /** Filters the output of this signal via `f`. When this signal emits a value, the filtered
    * signal will only emit a value if the value passes the supplied predicate. The filtered value
    * will retain a connection to this signal for as long as it has connections of its own.
    */
  def filter (f :JPredicate[T]) :SignalV[T] = new DelegateSignalV[T,T](this) {
    override def onParentValue (value :T) = if (f.test(value)) notifyEmit(value)
  }

  /** Returns a signal which emits whenever this signal emits except that listeners are notified
    * via the supplied executor. This is useful for ensuring that regardless of which thread from
    * which a signal is emitted, the listeners are always notified on a particular thread (or in a
    * particular execution context).
    */
  def via (exec :Executor) :SignalV[T] = new DelegateSignalV[T,T](this) {
    override def onParentValue (value :T) = exec.execute(new Runnable() {
      override def run ()  = notifyEmit(value)
    })
  }

  /** Returns a signal which emits values from `this` only when `timeout` milliseconds elapse
    * without subsequent values being emitted. If a new value is emitted in the timeout window, the
    * earlier value is dropped and the timeout is restarted with the new value.
    * @param scheduler used to schedule delays. Note that emissions of the debounced value will
    * necessarily happen on the thread(s) used by the scheduler. Use [via] to reroute to a
    * different thread if necessary.
    */
  def debounce (timeout :Long, scheduler :Scheduler) :SignalV[T] = new DelegateSignalV[T,T](this) {
    var pending :Closeable = null
    override def onParentValue (value :T) = {
      if (pending != null) pending.close()
      pending = scheduler.schedule(timeout, new Runnable() {
        override def run () :Unit = {
          pending = null
          notifyEmit(value)
        }
      })
    }
  }

  /** Returns a signal which emits batches of values from `this` when `timeout` milliseconds elapse
    * after the emission of the final value in a batch. If a new value is emitted in the timeout
    * window, it is added to the batch and the timeout is restarted.
    * @param scheduler used to schedule delays. Note that emissions of the batches will necessarily
    * happen on the thread(s) used by the scheduler. Use [via] to reroute to a different thread if
    * necessary.
    */
  def debounceBatched (timeout :Long, scheduler :Scheduler) :SignalV[Seq[T]] =
    new DelegateSignalV[T,Seq[T]](this) {
      var pending :Closeable = null
      var batch = Seq.builder[T]()
      override def onParentValue (value :T) = {
        if (pending != null) pending.close()
        batch.synchronized { batch += value }
        pending = scheduler.schedule(timeout, new Runnable() {
          override def run () :Unit = {
            pending = null
            notifyEmit(batch.synchronized {
              val built = batch.build()
              batch = Seq.builder[T]()
              built
            })
          }
        })
      }
    }

  /** Connects the supplied slot (side-effecting function) with priorty zero. When a value is
    * emitted, the slot will be invoked with the value.
    * @return $CONDOC
    */
  def onValue (slot :JConsumer[T]) :Connection = onValueAt(0)(slot)

  /** Connects the supplied "value agnostic" block of code with priority 0. When a value is emitted,
    * the block will be executed. Useful when you don't care about the value.
    * @return $CONDOC
    */
  def onEmit (block : =>Unit) :Connection = onEmitAt(0)(block)

  /** Connects the supplied slot (side-effecting function) at the specified priority. When a value is
    * emitted, the slot will be invoked with the value.
    * @param prio $PRIODOC
    * @return $CONDOC
    */
  def onValueAt (prio :Int)(slot :JConsumer[T]) :Connection = addLink(new Link(this, prio) {
    override def notify (value :Any) = slot.accept(value.asInstanceOf[T])
  })

  /** Connects the supplied "value agnostic" block of code at the specified priority. When a value is
    * emitted, the block will be executed. Useful when you don't care about the value.
    * @param prio $PRIODOC
    * @return $CONDOC
    */
  def onEmitAt (prio :Int)(block : =>Unit) :Connection = addLink(new Link(this, prio) {
    override def notify (value :Any) = block
  })

  /** Emits the supplied value to all connections. */
  protected def notifyEmit[U >: T] (value :U) :Unit = {
    val lners = prepareNotify()
    var err :ReactionException = null
    try {
      var cons = lners
      while (cons != null) {
        try {
          cons.notify(value)
        } catch {
          case t :Throwable =>
            if (err == null) err = new ReactionException()
            err addSuppressed t
        }
        if (cons.oneShot) cons.close()
        cons = cons.next
      }
    } finally {
      finishNotify(lners)
    }
    if (err != null) throw err
  }
}

private abstract class DelegateSignalV[D,T] (parent :SignalV[D]) extends SignalV[T] {

  /** Called when a value is emitted from our parent signal. */
  def onParentValue (value :D) :Unit

  // connectionAdded and connectionRemoved are only ever called with a lock held on this reactor,
  // so we're safe in checking and mutating _conn
  override protected def connectionAdded () :Unit = {
    super.connectionAdded()
    if (_conn == null) _conn = parent.onValue(onParentValue)
  }
  override protected def connectionRemoved () :Unit = {
    super.connectionRemoved()
    if (!hasConnections && _conn != null) {
      _conn.close()
      _conn = null
    }
  }
  protected var _conn :Connection = _
}
