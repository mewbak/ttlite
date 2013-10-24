package ttlite.core

import scala.util.parsing.combinator._
import scala.util.parsing.input._
import scala.collection.mutable.ArrayBuffer

trait RichPositional {
  var startPos: Position = NoPosition
  var endPos: Position = NoPosition
  def setPs(start: Position, end: Position): this.type = {
    startPos = start
    endPos = end
    this
  }

  lazy val source: java.lang.CharSequence =
    startPos.asInstanceOf[OffsetPosition].source

  /** An index that contains all line starts, including first line, and eof. */
  private lazy val index: Array[Int] = {
    var lineStarts = new ArrayBuffer[Int]
    lineStarts += 0
    for (i <- 0 until source.length)
      if (source.charAt(i) == '\n') lineStarts += (i + 1)
    lineStarts += source.length
    lineStarts.toArray
  }

  def origin: String =
    source.subSequence(
      index(startPos.line -1) + startPos.column - 1,
      index(endPos.line - 1) + endPos.column - 1).toString

  def originPrefix: String =
    source.subSequence(
      index(startPos.line -1),
      index(startPos.line -1) + startPos.column - 1
    ).toString

  def originSuffix: String =
    source.subSequence(
      index(endPos.line - 1) + endPos.column - 1,
      index(endPos.line)
    ).toString
}

// NAMES
sealed class Name (s : String) {
  override def toString = s
}
case class Global(n: String) extends Name(n)
case class Assumed(n: String) extends Name(n)
case class Local(i: Int) extends Name(s"<$i>")
case class Quote(i: Int) extends Name(s"[$i]")
// META-SYNTAX
sealed trait MTerm extends RichPositional {
  def ~(t1: MTerm) = @@(this, t1)
}
case class MVar(n: Name) extends MTerm
case class @@(t1: MTerm, t2: MTerm) extends MTerm {
  override def toString = s"$t1 ~ $t2"
}
case class MAnn(t1: MTerm, t2: MTerm) extends MTerm
case class MBind(id: String, tp: MTerm, body: MTerm) extends MTerm

trait Stmt[+I]
case class Let[I](n: String, i: I) extends Stmt[I]
case class TypedLet[I](n: String, i: I, t: I) extends Stmt[I]
case class Assume[I](n: String, i: I) extends Stmt[I]
case class Eval[I](e: I) extends Stmt[I]
case class Import(s: String) extends Stmt[Nothing]
case class Reload(s: String) extends Stmt[Nothing]
case object Quit extends Stmt[Nothing]

// PARSING
class MetaLexical extends lexical.StdLexical {
  import scala.util.parsing.input.CharArrayReader._
  override def whitespace: Parser[Any] = rep(
    whitespaceChar
      | '{' ~ '-' ~ comment
      | '-' ~ '-' ~ rep( chrExcept(EofCh, '\n') )
      | '{' ~ '-' ~ failure("unclosed comment")
  )
  override protected def comment: Parser[Any] = (
    '-' ~ '}'  ^^ { case _ => ' '  }
      | chrExcept(EofCh) ~ comment
    )
  override def identChar = letter | elem('_') | elem('$') | elem('/') | elem('\'') | elem('\\')
}

trait MetaParser extends syntactical.StandardTokenParsers with PackratParsers with ImplicitConversions {
  override val lexical = new MetaLexical
  lexical.reserved += ("assume", "let", "import", "sc", "sc2", "quit", "reload")
  lexical.delimiters += ("(", ")", ":", ".", "=", "->", ";")
  type C = List[String]
  case class Res(private val f : C => MTerm) extends RichPositional {
    def p(c : C = Nil): MTerm = f(c).setPs(startPos, endPos)
  }
  case class P(p : (String, Res)) extends RichPositional

  lazy val term: PackratParser[Res] = untyped
  lazy val aTerm: PackratParser[Res] =
    p(mVar | "(" ~> term <~ ")")
  lazy val mVar: PackratParser[Res] =
    p(ident ^^ {i => Res {ctx: C => ctx.indexOf(i) match {case -1 => MVar(s2name(i)) case j => MVar(Quote(j))}} })
  lazy val app: PackratParser[Res] =
    p((aTerm+) ^^ {ts => Res {ctx: C => ts.map{_.p(ctx)}.reduce(_ ~ _)} })

  lazy val bind: PackratParser[Res] =
    p(ident ~ (arg+) ~ (("." | "->") ~> term) ^^ {case id ~ args ~ body => Res {ctx: C =>
      def mkBind(xs: List[P], c: C): MTerm = xs match {
        case Nil => body.p(c)
        case (p@P((n, tp))) :: xs =>
          val bind = MBind(id, tp.p(c), mkBind(xs, n :: c))
          bind.setPs(p.startPos, p.endPos)
          bind
      }
      mkBind(args, ctx)
    } })
  lazy val untyped: PackratParser[Res] = bind | app
  val arg: PackratParser[P] =
    p("(" ~> (ident ~ (":" ~> term)) <~ ")" ^^ {case i ~ x => P(i, x)})
  lazy val stmt: PackratParser[Stmt[MTerm]] = stmts.reduce(_ | _)
  def stmts = List(quitStmt, assumeStmt, letStmt1, letStmt, importStmt, reloadStmt, evalStmt)
  def p[T <: RichPositional](p: => Parser[T]): Parser[T] = Parser { in => p(in) match {
    case Success(t, in1) => Success(t.setPs(in.pos, in1.pos), in1) case n => n
  }}

  lazy val letStmt: PackratParser[Stmt[MTerm]] =
    ident ~ ("=" ~> term <~ ";") ^^ {case x ~ y => Let(x, y.p(Nil))}
  lazy val letStmt1: PackratParser[Stmt[MTerm]] =
    (global ~ (":" ~> term) <~ ";") >> {
      case x ~ tp =>
        (ident ^?({case `x` => x}, _ => s"no implementation for $x")) ~ ("=" ~> term <~ ";") ^^ {
          case x ~ y => TypedLet(x, y.p(Nil), tp.p(Nil))
        }
    }
  lazy val assumeStmt: PackratParser[Stmt[MTerm]] =
    (assumed ~ (":" ~> term) <~ ";") ^^ {case x ~ y  => Assume(x, y.p(Nil))}
  lazy val importStmt: PackratParser[Stmt[MTerm]] =
    "import" ~> (stringLit | ident ^^ {x => s"$x.hs"}) <~ ";" ^^ Import
  lazy val reloadStmt: PackratParser[Stmt[MTerm]] =
    "reload" ~> (stringLit | ident ^^ {x => s"$x.hs"}) <~ ";" ^^ Reload
  lazy val evalStmt: PackratParser[Stmt[MTerm]] =
    term <~ ";" ^^ {t => Eval(t.p(Nil))}
  lazy val quitStmt: PackratParser[Stmt[MTerm]] =
    "quit" <~ ";" ^^ {t => Quit}

  def assumed: PackratParser[String] =
    ident ^? {case s: String if s.startsWith("$") => s}
  def global: PackratParser[String] =
    ident ^? {case s: String if !s.startsWith("$") => s}
  def s2name(s: String): Name = if (s.startsWith("$")) Assumed(s) else Global(s)
  def parseIO[A](p: Parser[A], in: String): A = phrase(p)(new lexical.Scanner(in)) match {
    case t if t.successful =>
      t.get
    case f@Failure(msg, next) =>
      throw ParsingError(msg, next.pos.line, next.pos.column, next.pos.longString)
    case Error(msg, next) =>
      throw ParsingError(msg, next.pos.line, next.pos.column, next.pos.longString)
  }

  def parseMTerm(in: String) = parseIO(term, in).p(Nil)
}

object MetaParser extends MetaParser

class TTLiteError(msg: String, line : Int, column : Int) extends Exception(msg) {
  var file : String = "_$_"
  def setFile(f : String) : this.type = {
    if (file == "_$_")
      file = f
    this
  }
  //override def getMessage(): String = "XXX"
  def details: String = ""
  def location: String = s"$file[$line:$column]"
}
object TTLiteExit extends TTLiteError("EXIT", 0, 0)
case class ParsingError(msg : String, line : Int, column : Int, longString : String) extends TTLiteError(msg, line, column) {
  override def details: String = longString.replace("\n\n", "\n")
}
case class TranslationError(mt : MTerm, msg : String) extends TTLiteError(msg, mt.startPos.line, mt.startPos.column) {
  override def details: String =
    mt.originPrefix + Console.RED + Console.BOLD + mt.origin + Console.RESET + mt.originSuffix
}
case class TypeError(msg : String) extends TTLiteError(msg,0, 0)
//case class RawTypingError(msg: String) extends TTLiteError(msg, 0, 0)

object `package` {
  sealed trait PathElem
  object L extends PathElem
  object R extends PathElem
  type Path = List[PathElem]
  def p(i : Int, n : Int) : Path = (i, n) match {
    case (1, 2) => List(L)
    case (2, 2) => List(R)
    case (i, n) if i == n => List(R)
    case (i, n) => L :: p(i, n - 1)
  }
  implicit class PathWrapper(path : Path) {
    def /(i: Int, n: Int) = path ++ p(i, n)
  }

  //implicit def sym2Term(s: Symbol): MTerm = MVar(Global(s.name))
  type NameEnv[V] = Map[Name, V]
  // todo: helper methods: bindVal, bindType
  case class Context[V](vals: NameEnv[V], types: NameEnv[V])

  def emptyEnv[V] = Map[Name, V]()
  def emptyContext[V] = Context(emptyEnv[V], emptyEnv[V])
  val ids = "abcdefghijklmnopqrstuvwxyz"
  val suffs = List("", "1")
  val vars = for {j <- suffs; i <- ids} yield s"$i$j"
}
