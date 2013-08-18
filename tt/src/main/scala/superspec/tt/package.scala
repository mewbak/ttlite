package superspec

package object tt {
  // NAMES
  sealed trait Name
  case class Global(n: String) extends Name
  case class Assumed(n: String) extends Name
  // TODO: do we need local and quote together? is it possible to use only one of them?
  case class Local(i: Int) extends Name
  case class Quote(i: Int) extends Name
  // META-SYNTAX
  sealed trait MTerm {
    def @@(t1: MTerm) = :@@:(this, t1)
  }
  case class MVar(n: Name) extends MTerm
  case class :@@:(t1: MTerm, t2: MTerm) extends MTerm
  case class MAnn(t1: MTerm, t2: MTerm) extends MTerm
  case class MBind(id: String, tp: MTerm, body: MTerm) extends MTerm
  implicit def sym2Term(s: Symbol): MTerm = MVar(Global(s.name))
  // STATEMENTS. TODO: test commands, external commands
  trait Stmt[+I]
  case class Let[I](n: String, i: I) extends Stmt[I]
  case class Assume[I](ns: List[(String, I)]) extends Stmt[I]
  case class Eval[I](e: I) extends Stmt[I]
  case class Import(s: String) extends Stmt[Nothing]
  case class SC[I](e: I) extends Stmt[I]
  case class CertSC[I](e: I) extends Stmt[I]
  // PARSING
  import scala.util.parsing.combinator._
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
    override def identChar = letter | elem('_') | elem('$')
  }
  object MetaParser extends syntactical.StandardTokenParsers with PackratParsers with ImplicitConversions {
    override val lexical = new MetaLexical
    lexical.delimiters += ("(", ")", "::", ".")
    type C = List[String]
    type Res = C => MTerm
    lazy val term: PackratParser[Res] = optTyped
    lazy val aTerm: PackratParser[Res] =
      mVar | "(" ~> term <~ ")"
    lazy val mVar: PackratParser[Res] =
      ident ^^ {i => ctx: C => ctx.indexOf(i) match {case -1 => MVar(s2name(i)) case j => MVar(Quote(j))}}
    lazy val app: PackratParser[Res] =
      (aTerm+) ^^ {ts => ctx: C => ts.map{_(ctx)}.reduce(_ @@ _)}
    lazy val bind: PackratParser[Res] =
      ident ~ (arg+) ~ ("." ~> term) ^^ {case id ~ args ~ body => ctx: C =>
        def mkBind(xs: List[(String, Res)], c: C): MTerm = xs match {
          case Nil => body(c)
          case (n, tp) :: xs => MBind(id, tp(c), mkBind(xs, n :: c))
        }
        mkBind(args, ctx)
      }
    lazy val untyped: PackratParser[Res] = bind | app
    lazy val optTyped: PackratParser[Res] =
      untyped ~ ("::" ~> untyped) ^^ {case e ~ t => ctx: C => MAnn(e(ctx), t(ctx))} | untyped
    val arg: PackratParser[(String, Res)] =
      "(" ~> (ident ~ ("::" ~> term)) <~ ")" ^^ {case i ~ x => (i, x)}
    def s2name(s: String): Name = if (s.startsWith("$")) Assumed(s) else Global(s)
    def parseIO[A](p: Parser[A], in: String): Option[A] =
      phrase(p)(new lexical.Scanner(in)).map(Some(_)).getOrElse(None)
    def parseMTerm(in: String) = parseIO(term, in).map(_(Nil)).get
  }
  // MISC
  type NameEnv[V] = Map[Name, V]
  val ids = "abcdefghijklmnopqrstuvwxyz"
  val suffs = List("", "1")
  val vars = for {j <- suffs; i <- ids} yield s"$i$j"
  case class TypeError(msg: String) extends Exception(msg)
}
