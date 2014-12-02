//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled

import java.io.File
import java.nio.file.{FileSystems, Files, Path, Paths}
import java.util.stream.Stream
import scaled.util.FuzzyMatch

/** Represents a computed completion. */
abstract class Completion[+T] (
  /** The string used to generate this completion. */
  val glob :String,
  /** The strings to be displayed for completion, in display order. */
  val comps :SeqV[String]) {

  /** Returns the completion originally returned from a [[Completer]] regardless of how many times
    * it has been [[refine]]d. */
  def root :Completion[T]

  /** Returns `Some` value associated with the completion `comp`, or `None`. */
  def apply (comp :String) :Option[T]

  /** Refines this completion with the longer prefix `prefix`. This generally means filtering
    * `comps` to contain only those elements which [[FuzzyMatch]] `prefix`. */
  def refine (prefix :String) :Completion[T]

  /** Returns the default value to use when committed with a non-matching value. */
  def onNonMatch :Option[T] = comps.headOption.flatMap(apply)

  /** Returns the longest prefix of the completions that is the same for all completions. */
  def longestPrefix :String = if (comps.isEmpty) "" else comps reduce sharedPrefix

  override def toString = s"Completion($comps)"

  /** Returns the longest shared prefix of `a` and `b`. Matches case loosely, using uppercase
    * only when both strings have the character in uppercase, lowercase otherwise. */
  protected def sharedPrefix (a :String, b :String) = if (b startsWith a) a else {
    val buf = new StringBuilder
    @inline @tailrec def loop (ii :Int) {
      if (ii < a.length && ii < b.length) {
        val ra = a.charAt(ii) ; val la = Character.toLowerCase(ra)
        val rb = b.charAt(ii) ; val lb = Character.toLowerCase(rb)
        if (la == lb) {
          // if everyone uses uppercase here, keep the prefix uppercase, otherwise lower
          val c = if (Character.isUpperCase(ra) && Character.isUpperCase(rb)) ra else la
          buf.append(c)
          loop(ii+1)
        }
      }
    }
    loop(0)
    buf.toString
  }
}

/** Factory methods for standard completions. */
object Completion {

  /** Returns an empty completion of type `T`. */
  def empty[T] (prefix :String) :Completion[T] = new Completion[T](prefix, Seq()) {
    override def apply (curval :String) = None
    override def refine (prefix :String) :Completion[T] = this
    override def root = this
  }

  /** Returns a sorted completion over some strings. */
  def string (glob :String, ss :Iterable[String]) :Completion[String] =
    apply[String](glob, ss, true)(identity)

  /** Creates a completion over `values`, formatting them with `format`.
    * @param sort if true the values will be sorted lexically, if false they will be displayed in
    * iteration order.
    */
  def apply[T] (glob :String, values :Iterable[T], sort :Boolean)
               (format :T => String) :Completion[T] =
    apply(glob, values.iterator, sort)(format)

  /** Creates a completion over `values`, formatting them with `format`.
    * @param sort if true the values will be sorted lexically, if false they will be displayed in
    * iteration order.
    */
  def apply[T] (glob :String, viter :JIterator[T], sort :Boolean)
               (format :T => String) :Completion[T] = {
    val cb = Seq.builder[String]()
    val mb = Map.builder[String,T]()
    while (viter.hasNext) {
      val value = viter.next
      val comp = format(value)
      cb += comp
      mb += (comp, value)
    }
    // we need to turn our treeset into a seq immediately otherwise the sort ordering will be lost
    // further down the line when it is filtered or grouped or whatnot
    val cs = cb.build()
    apply(glob, if (sort) cs.sorted else cs, mb.build())
  }

  /** Creates a completion with `cs` and `map`.
    *
    * In general one will want to generate `cs` from an original set of objects and create the
    * mapping at the same time. However, in some cases, one may want to include completion
    * candidates which don't themselves map to completable objets, but instead trigger further
    * expansion of the completion process. This is used when completing files to allow intermediate
    * directories to be completed (which then triggers the completion of their contents), without
    * allowing the directories themselves to be chosen.
    *
    * @param cs the list of completion strings.
    * @param map a map from completion string back to completed object.
    */
  def apply[T] (glob :String, cs :Seq[String], map :Map[String,T]) :Completion[T] =
    new MapComp(glob, cs, map)

  private class MapComp[T] (gl :String, cs :SeqV[String], map :Map[String,T])
      extends Completion[T](gl, cs) {
    def apply (comp :String) = map.get(comp)
    def refine (prefix :String) = {
      val outer = this
      new MapComp[T](prefix, FuzzyMatch(prefix).filter(comps), map) {
        override def root = outer.root
      }
    }
    def root = this
    override def toString = map.toString
  }
}

/** Handles completions. */
abstract class Completer[T] {

  /** Generates a completion for the specified initial prefix. */
  def apply (prefix :String) :Completion[T] =
    if (prefix.length >= minPrefix) complete(prefix)
    else Completion.empty(prefix)

  /** Refines an existing completion given the supplied prefix. */
  def refine (comp :Completion[T], prefix :String) :Completion[T] = {
    val oglob = comp.glob
    // if prefix is (still) too short, return empty completion
    if (prefix.length < minPrefix) Completion.empty(prefix)
    // if the previous completion is empty, we need to generate a proper completion now that we
    // have a sufficiently long prefix
    else if (oglob.length < minPrefix) apply(prefix)
    // normal case: extending an existing match
    else if (refines(prefix, oglob)) comp.refine(prefix)
    // backspace case: new glob is prefix of old glob
    else if (oglob startsWith prefix) {
      // if we're still within our root glob, refine from there
      if (refines(prefix, comp.root.glob)) comp.root.refine(prefix)
      // otherwise we need to generate a new completion
      else apply(prefix)
    }
    // other case: new glob totally unrelated to old glob
    else apply(prefix)
  }

  /** Returns the value identified by `curval` given the current completion `comp`. */
  def commit (comp :Completion[T], curval :String) :Option[T] = comp(curval) orElse comp.onNonMatch

  /** If the value being completed is a path, this separator will be used to omit the path elements
    * shared by the currently displayed prefix and the current completions. */
  def pathSeparator :Option[String] = None

  /** Returns the minimum prefix length needed to generate completion. Most completers will return
    * zero for this method and supply all possible completions immediately. However, if that is
    * computationally infeasible, a completer can require a one or two character prefix to reduce
    * the completion space prior to generating a list of potential matches. */
  protected def minPrefix :Int = 0

  /** Returns true if `nglob` refines `oglob`. False if it's an unrelated query. */
  protected def refines (nglob :String, oglob :String) :Boolean = nglob startsWith oglob

  /** Generates a list of completions which start with `prefix`. `prefix` will be exactly
    * [[minPrefix]] characters long. */
  protected def complete (prefix :String) :Completion[T]
}

/** Some useful completion functions. */
object Completer {

  /** A noop completer (for strings). */
  val none :Completer[String] = new Completer[String]() {
    def complete (prefix :String) = new Completion[String](prefix, Seq(prefix)) {
      def root :Completion[String] = this
      def apply (prefix :String) = Some(prefix)
      def refine (prefix :String) = complete(prefix)
    }
  }

  /** Returns a completer over `names`.
    * @param requireComp whether to require a completion from the supplied set. */
  def from (names :Iterable[String]) = new Completer[String] {
    def complete (prefix :String) = Completion.string(prefix, names)
  }

  /** Returns a completer over `things` using `nameFn` to obtain each thing's name. */
  def from[T] (things :Iterable[T])(nameFn :T => String) = new Completer[T]() {
    def complete (prefix :String) = Completion(prefix, things, true)(nameFn)
  }

  /** Returns a completer on buffer name. */
  def buffer (wspace :Workspace, defbuf :Buffer) :Completer[Buffer] = buffer(wspace, defbuf, Set())

  /** Returns a completer on buffer name. This behaves specially in that the empty completion omits
    * transient buffers (buffers named `*foo*`).
    * @param except a set of buffers to exclude from completion.
    */
  def buffer (wspace :Workspace, defbuf :Buffer, except :Set[Buffer]) :Completer[Buffer] =
    new Completer[Buffer] {
      def complete (prefix :String) = {
        val bb = Seq.builder[Buffer]()
        bb += defbuf
        // add the non-scratch buffers, sorted by name
        bb ++= wspace.buffers.filter(b => !except(b) && !Buffer.isScratch(b.name) && b != defbuf).sortBy(_.name)
        // add the scratch buffers after the non-scratch buffers, also sorted by name
        bb ++= wspace.buffers.filter(b => !except(b) && Buffer.isScratch(b.name) && b != defbuf).sortBy(_.name)
        val bufs = bb.build()
        Completion(prefix, bufs.map(_.name), bufs.mapBy(_.name))
      }
    }

  /** Returns true if `full` starts with `prefix`, ignoring case, false otherwise. */
  def startsWithI (prefix :String)(full :String) :Boolean = {
    @inline @tailrec def loop (ii :Int) :Boolean = {
      if (ii == prefix.length) true
      else {
        val ra = full.charAt(ii) ; val rb = prefix.charAt(ii)
        if (ra == rb) loop(ii+1)
        else {
          val la = Character.toLowerCase(ra) ; val lb = Character.toLowerCase(rb)
          if (la == lb) loop(ii+1)
          else false
        }
      }
    }
    if (prefix.length > full.length) false else loop(0)
  }

  /** Replaces newlines with whitespace. This should be called on any string that will be used
    * as a completion key which may potentially contain newlines. File names are the primary
    * culprit here. */
  def defang (name :String) :String = name.replace('\n', ' ').replace('\r', ' ')

  /** A completer on file system files. */
  val file :Completer[Store] = new Completer[Store] {
    override def complete (path :String) = {
      val p = Paths.get(path)
      if (path endsWith File.separator) expand(path, p, "")
      else p.getParent match {
        case null => expand(path, rootPath /*TODO*/, path)
        case prnt => expand(path, prnt, p.getFileName.toString)
      }
    }

    override def refine (comp :Completion[Store], prefix :String) :Completion[Store] = {
      // if completion string ends in a path separator, and it references a real directory, then
      // descend into the directory and complete from there
      val ppath = Paths.get(prefix)
      if ((prefix endsWith File.separator) && Files.isDirectory(ppath)) expand(prefix, ppath, "")
      else super.refine(comp, prefix)
    }

    override def commit (comp :Completion[Store], curval :String) =
      comp.comps.headOption.flatMap(fromString)

    override def pathSeparator = Some(File.separator)
    override def toString = "FileCompleter"

    private def expand (path :String, dir :Path, prefix :String) :Completion[Store] = {
      val edir = massage(dir)
      val files = if (Files.exists(edir)) Files.list(edir) else Stream.empty[Path]()
      def defpath (path :Path) = defang(path.getFileName.toString)
      // TODO: sort .files below non-.files
      val matches = try FuzzyMatch.createI(prefix).filterBy(files.iterator.toSeq)(defpath)
                    finally files.close()
      class FileComp (gl :String, mstrs :Seq[String]) extends Completion[Store](gl, mstrs) {
        override def apply (comp :String) = None
        override def refine (prefix :String) = {
          val outer = this
          new FileComp(prefix, FuzzyMatch.createI(prefix).filter(mstrs)) {
            override def root = outer.root
          }
        }
        override def root = this
        override def toString = s"($dir, $prefix) => $mstrs"
      }
      new FileComp(path, matches.map(format))
    }

    private def fromString (value :String) = {
      val path = Paths.get(value)
      if (Files.exists(path) && Files.isDirectory(path)) None else Some(Store(path))
    }

    private def massage (dir :Path) = dir.getFileName match {
      case null => dir
      case name if (name.toString == "~") => Paths.get(System.getProperty("user.home"))
      case _ => dir // TODO: map '//' to root of file system?
    }

    private def rootPath = FileSystems.getDefault.getRootDirectories.iterator.next
    private val format = (file :Path) => {
      val path = defang(file.toString)
      if (Files.isDirectory(file)) path + File.separator else path
    }
  }
}