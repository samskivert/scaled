//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled.impl

import java.lang.reflect.Field
import scala.collection.mutable.{Map => MMap}
import scaled._

abstract class EnvImpl (val log :Logger, val exec :Executor, val editor :Editor,
                        val view :RBufferView, val disp :Dispatcher) extends Env

abstract class ModeResolver (log :Logger, exec :Executor, editor :Editor) {

  /** Returns the names of all known modes, major if `major`, minor if not. */
  def modes (major :Boolean) :Set[String] = Set()

  /** Returns the names of all minor modes with tags that overlap `tags`. */
  def minorModes (tags :Array[String]) :Set[String] = Set()

  /** Resolves and instantiates the major mode `mode` with the supplied environment. */
  def resolveMajor (mode :String, view :BufferViewImpl, disp :DispatcherImpl,
                    args :List[Any]) :MajorMode =
    resolve(mode, view, disp, args, requireMajor(mode))

  /** Resolves and instantiates the minor mode `mode` with the supplied environment. */
  def resolveMinor (mode :String, view :BufferViewImpl, disp :DispatcherImpl, major :MajorMode,
                    args :List[Any]) :MinorMode =
    resolve(mode, view, disp, major :: args, requireMinor(mode))

  protected def locate (major :Boolean, mode :String) :Class[_]
  protected def resolveConfig (mode :String, defs :List[Config.Defs]) :Config
  protected def injectInstance[T] (clazz :Class[T], args :List[Any]) :T

  private def requireMajor (mode :String) = reqType(mode, classOf[MajorMode])
  private def requireMinor (mode :String) = reqType(mode, classOf[MinorMode])
  private def reqType[T] (mode :String, mclass :Class[T]) = {
    val isMajor = mclass == classOf[MajorMode]
    val clazz = locate(isMajor, mode)
    if (mclass.isAssignableFrom(clazz)) clazz.asInstanceOf[Class[T]]
    else throw new IllegalArgumentException(s"$mode is not a ${mclass.getSimpleName}.")
  }

  private def resolve[T] (mode :String, view :BufferViewImpl, disp :DispatcherImpl,
                          args :List[Any], modeClass :Class[T]) :T = {
    val envargs = new EnvImpl(log, exec, editor, view, disp) {
      def resolveConfig (mode :String, defs :List[Config.Defs]) =
        ModeResolver.this.resolveConfig(mode, defs)
    } :: args
    injectInstance(modeClass, envargs)
  }
}

class AppModeResolver (app :Main, editor :Editor)
    extends ModeResolver(app.logger, app.exec, editor) {

  override def modes (major :Boolean) = Set() ++ app.pkgMgr.modes(major)
  override def minorModes (tags :Array[String]) = app.pkgMgr.minorModes(tags)

  override protected def locate (major :Boolean, mode :String) =
    app.pkgMgr.mode(major, mode)
  override protected def resolveConfig (mode :String, defs :List[Config.Defs]) =
    app.cfgMgr.resolveConfig(mode, defs)
  override protected def injectInstance[T] (clazz :Class[T], args :List[Any]) =
    app.svcMgr.injectInstance(clazz, args)
}