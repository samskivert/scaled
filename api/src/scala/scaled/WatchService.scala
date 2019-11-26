//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled

import java.nio.file.Path

/** A callback interface that is notified when watch events occur. */
abstract class Watcher {

  /** Notification that a file or directory named `child` was created in `dir`. */
  def onCreate (dir :Path, child :String) :Unit = {}

  /** Notification that a file or directory named `child` was deleted in `dir`. */
  def onDelete (dir :Path, child :String) :Unit = {}

  /** Notification that a file or directory named `child` was modified in `dir`. */
  def onModify (dir :Path, child :String) :Unit = {}
}

/** Provides notifications when files or directories are modified. */
@Service(name="watch", impl="impl.WatchManager",
         desc="Provides notifications when files and directories are modified.")
trait WatchService {

  /** Registers a watch on `file`. `watcher` will be invoked (on the main JavaFX thread) when `file`
    * is modified or deleted.
    * @return a handle that can be used to terminate the watch. */
  def watchFile (file :Path, watcher :Path => Unit) :Closeable

  /** Registers a watch on `dir`. `watcher` will be invoked (on the main JavaFX thread) when any
    * files are created, modified or deleted in `dir`.
    * @return a handle that can be used to terminate the watch. */
  def watchDir (dir :Path, watcher :Watcher) :Closeable
}
