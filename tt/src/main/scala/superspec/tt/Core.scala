package superspec.tt

import org.kiama.output.PrettyPrinter

trait CoreAST {
  trait Term {
    def @@(t1: Term) = :@:(this, t1)
  }

  case class Pi(c1: Term, c2: Term) extends Term
  case class Lam(t: Term, e: Term) extends Term
  case class :@:(h: Term, t: Term) extends Term

  case class Sigma(c1: Term, c2: Term) extends Term
  case class DPair(sigma: Term, t: Term, e: Term) extends Term
  case class SigmaElim(sigma: Term, m: Term, f: Term, pair: Term) extends Term

  case class Ann(c1: Term, ct2: Term) extends Term
  case object Star extends Term
  case class Bound(i: Int) extends Term
  case class Free(n: Name) extends Term

  trait Value {
    def @@(x: Value): Value = vapp(this, x)
  }
  case class VLam(t: Value, e: Value => Value) extends Value
  case class VDPair(sigma: Value, t: Value, e: Value) extends Value
  case object VStar extends Value
  case class VPi(t: Value, e: Value => Value) extends Value
  case class VSigma(t: Value, e: Value => Value) extends Value
  case class VNeutral(n: Neutral) extends Value

  trait Neutral
  case class NFree(n: Name) extends Neutral
  case class NApp(n: Neutral, v: Value) extends Neutral
  case class NSigmaElim(sigma: Value, m: Value, f: Value, pair: Neutral) extends Neutral

  type Env = List[Value]
  val emptyNEnv = Map[Name, Value]()

  def vfree(n: Name): Value = VNeutral(NFree(n))

  private def vapp(x: Value, v: Value): Value = x match {
    case VLam(_, f) => f(v)
    case VNeutral(n) => VNeutral(NApp(n, v))
  }

  def freeLocals(c: Any): Set[Local] = c match {
    case Free(Local(n)) =>
      Set(Local(n))
    case p: scala.Product =>
      p.productIterator.flatMap(freeLocals).toSet
    case _ => Set()
  }

  implicit def sym2val(s: Symbol): Value =
    VNeutral(NFree(Global(s.name)))
  implicit def sym2Term(s: Symbol): Term =
    Free(Global(s.name))
  implicit def s2val(s: String): Value =
    VNeutral(NFree(Global(s)))
  implicit def s2Term(s: String): Term =
    Free(Global(s))

}

/**
 * Translation of MetaLanguage into concrete language
 */
trait MCore extends CoreAST {
  def fromM(m: MTerm): Term = m match {
    case MVar(Quote(i)) =>
      Bound(i)
    case MVar(n) =>
      Free(n)
    case MAnn(t1, t2) =>
      Ann(fromM(t1), fromM(t2))
    case MVar(Global("elim")) :@@: tp =>
      sys.error(s"incorrect eliminator: $m")
    case t1 :@@: t2 =>
      fromM(t1) @@ fromM(t2)
    case MBind("forall", t1, t2) =>
      Pi(fromM(t1), fromM(t2))
    case MBind("\\", t1, t2) =>
      Lam(fromM(t1), fromM(t2))
    case MBind("exists", t1, t2) =>
      Sigma(fromM(t1), fromM(t2))
  }

}

trait CorePrinter extends CoreAST with PrettyPrinter {
  def parensIf(b: Boolean, d: Doc) =
    if (b) parens(d) else d
  def pprint(c: Term): String =
    pretty(print(0, 0, c))

  def print(p: Int, ii: Int, t: Term): Doc = t match {
    case Ann(c, ty) =>
      parensIf(p > 1, nest(sep(Seq(print(2, ii, c) <> " :: " , nest(print(0, ii, ty))))))
    case Star =>
      "*"
    case Pi(d, Pi(d1, r)) =>
      parensIf(p > 0, nestedForall(ii + 2, List((ii + 1, d1), (ii, d)), r))
    case Pi(d, r) =>
      parensIf(p > 0, sep(Seq("forall " <> parens(vars(ii) <> " :: " <> print(0, ii, d)) <> " .", nest(print(0, ii + 1, r)))))
    case Sigma(d, Sigma(d1, r)) =>
      parens(nestedExists(ii + 2, List((ii + 1, d1), (ii, d)), r))
    case Sigma(d, r) =>
      parensIf(p > 0, sep(Seq("exists " <> parens(vars(ii) <> " :: " <> print(0, ii, d)) <> " .", nest(print(0, ii + 1, r)))))
    case Bound(k) if ii - k - 1 >= 0 =>
      vars(ii - k - 1)
    case Free(Global(s)) =>
      s
    case Free(Assumed(s)) =>
      s
    case Free(Local(i)) =>
      s"<$i>"
    case i :@: c =>
      parensIf(p > 2, sep(Seq(print(2, ii, i), nest(print(3, ii, c)))))
    case Lam(t, c) =>
      parensIf(p > 0,  "\\ " <> parens(vars(ii) <> " :: " <> print(0, ii, t)) <> " -> " <> nest(print(0, ii + 1, c)))
    case DPair(s, a, b) =>
      print(p, ii, 'dpair @@ s @@ a @@ b)
    case SigmaElim(s, m, f, dp) =>
      print(p, ii, 'sigmaElim @@ s @@ m @@ f @@ dp)
    case _ =>
      t.toString
  }

  def nestedForall(i: Int, fs: List[(Int, Term)], t: Term): Doc = t match {
    case Pi(d, r) =>
      nestedForall(i + 1, (i, d) :: fs, r)
    case x =>
      val fors = fs.reverse.map{case (n,d) => parens(vars(n) <> " :: " <> nest(print(0, n, d)))}.toSeq
      val fors1 = fors.updated(fors.length - 1, fors(fors.length - 1) <> " .")
      nest(sep((text("forall") +: fors1).toSeq ++ Seq(print(0, i , x))))
  }

  def nestedExists(i: Int, fs: List[(Int, Term)], t: Term): Doc = t match {
    case Sigma(d, r) =>
      nestedExists(i + 1, (i, d) :: fs, r)
    case x =>
      val fors = fs.reverse.map{case (n,d) => parens(vars(n) <> " :: " <> nest(print(0, n, d)))}.toSeq
      val fors1 = fors.updated(fors.length - 1, fors(fors.length - 1) <> " .")
      nest(sep((text("exists") +: fors1).toSeq ++ Seq(print(0, i , x))))
  }
}

trait CoreQuote extends CoreAST {
  def quote0(v: Value): Term =
    quote(0, v)

  def quote(ii: Int, v: Value): Term = v match {
    case VLam(t, f) =>
      Lam(quote(ii, t), quote(ii + 1, f(vfree(Quote(ii)))))
    case VStar =>
      Star
    case VPi(v, f) =>
      Pi(quote(ii, v), quote(ii + 1, f(vfree(Quote(ii)))))
    case VSigma(v, f) =>
      Sigma(quote(ii, v), quote(ii + 1, f(vfree(Quote(ii)))))
    case VNeutral(n) =>
      neutralQuote(ii, n)
    case VDPair(sigma, e1, e2) =>
      DPair(quote(ii, sigma), quote(ii, e1), quote(ii, e2))

  }

  def neutralQuote(ii: Int, n: Neutral): Term = n match {
    case NFree(x) =>
      boundFree(ii, x)
    case NApp(n, v) =>
      neutralQuote(ii, n) @@ quote(ii, v)
    case NSigmaElim(sigma, m, f, p) =>
      SigmaElim(quote(ii, sigma), quote(ii, m), quote(ii, f), neutralQuote(ii, p))
  }

  // the problem is here!!!
  def boundFree(ii: Int, n: Name): Term = n match {
    case Quote(k) =>
      Bound(math.max(ii - k - 1, 0))
    case x =>
      Free(x)
  }
}

trait CoreEval extends CoreAST {
  def eval0(c: Term): Value = eval(c, emptyNEnv, Nil)
  def eval(t: Term, named: NameEnv[Value], bound: Env): Value = t match {
    case Ann(e, _) =>
      eval(e, named, bound)
    case Star =>
      VStar
    case Pi(ty, ty1) =>
      VPi(eval(ty, named, bound), x => eval(ty1, named, x :: bound))
    case Sigma(ty, ty1) =>
      VSigma(eval(ty, named, bound), x => eval(ty1, named, x :: bound))
    case Free(x) =>
      named.getOrElse(x, vfree(x))
    case Bound(ii) =>
      if (ii < bound.length) bound(ii) else vfree(Quote(ii))
    case e1 :@: e2 =>
      eval(e1, named, bound) @@ eval(e2, named, bound)
    case Lam(t, e) =>
      VLam(eval(t, named, bound), x => eval(e, named, x :: bound))
    case DPair(sigma, e1, e2) =>
      VDPair(eval(sigma, named, bound), eval(e1, named, bound), eval(e2, named, bound))
    case SigmaElim(sigma, m, f, p) =>
      val pVal = eval(p, named, bound)
      pVal match {
        case VDPair(_, x, y) =>
          val fVal = eval(f, named, bound)
          fVal @@ x @@ y
        case VNeutral(n) =>
          VNeutral(NSigmaElim(
            eval(sigma, named, bound),
            eval(m, named, bound),
            eval(f, named, bound),
            n
          ))
      }
  }
}

trait CoreCheck extends CoreAST with CoreQuote with CoreEval with CorePrinter {
  def iType0(named: NameEnv[Value], bound: NameEnv[Value], i: Term): Value =
    iType(0, named, bound, i)

  def checkEqual(i: Int, inferred: Term, expected: Term) {
    if (inferred != expected) {
      throw new TypeError(s"inferred: ${pprint(inferred)}, expected: ${pprint(expected)}")
    }
  }

  def checkEqual(i: Int, inferred: Value, expected: Term) {
    val infTerm = quote(i, inferred)
    if (infTerm != expected) {
      throw new TypeError(s"inferred: ${pprint(infTerm)}, expected: ${pprint(expected)}")
    }
  }

  def checkEqual(i: Int, inferred: Value, expected: Value) {
    val infTerm = quote(i, inferred)
    val expTerm = quote(i, expected)
    if (infTerm != expTerm) {
      throw new TypeError(s"inferred: ${pprint(infTerm)}, expected: ${pprint(expTerm)}")
    }
  }

  // todo: eval with bound - do we need it?? !!!
  def iType(i: Int, named: NameEnv[Value], bound: NameEnv[Value], t: Term): Value = t match {
    // TODO: universes
    case Star => VStar
    case Ann(e, Star) =>
      val eType = iType(i, named, bound, e)
      checkEqual(i, eType, VStar)
      VStar
    case Ann(e, tp) =>
      val tpVal = eval(tp, named, Nil)

      val tpType = iType(i, named, bound, tp)
      checkEqual(i, tpType, Star)

      val eType = iType(i, named, bound, e)
      checkEqual(i, eType, tpVal)

      tpVal

    case Pi(Star, tp) =>
      val tpType = iType(i + 1, named,  bound + (Local(i) -> VStar), iSubst(0, Free(Local(i)), tp))
      checkEqual(i, tpType, Star)
      VStar
    case Pi(x, tp) =>
      val xVal = eval(x, named, Nil)

      val xType = iType(i, named, bound, x)
      checkEqual(i, xType, Star)

      val tpType = iType(i + 1, named,  bound + (Local(i) -> xVal), iSubst(0, Free(Local(i)), tp))
      checkEqual(i, tpType, Star)

      VStar
    case Sigma(Star, tp) =>
      val tpType = iType(i + 1, named,  bound + (Local(i) -> VStar), iSubst(0, Free(Local(i)), tp))
      checkEqual(i, tpType, Star)
      VStar
    case Sigma(x, tp) =>
      val xVal = eval(x, named, Nil)

      val xType = iType(i, named, bound, x)
      checkEqual(i, xType, Star)

      val tpType = iType(i + 1, named,  bound + (Local(i) -> xVal), iSubst(0, Free(Local(i)), tp))
      checkEqual(i, tpType, Star)

      VStar
    case Free(x) =>
      bound.get(x) match {
        case Some(ty) => ty
        case None => sys.error(s"unknown id: $x")
      }
    case (e1 :@: e2) =>
      iType(i, named, bound, e1) match {
        case VPi(x, f) =>
          val e2Type = iType(i, named, bound, e2)
          checkEqual(i, e2Type, x)
          f(eval(e2, named, Nil))
        case _ =>
          sys.error(s"illegal application: $t")
      }
    case DPair(sigma, x, y) =>
      eval(sigma, named, Nil) match {
        case VSigma(a, f) =>
          val xType = iType(i, named, bound, x)
          checkEqual(i, xType, a)

          val xVal = eval(x, named, Nil)

          val yType = iType(i, named, bound, y)
          checkEqual(i, yType, f(xVal))

          VSigma(a, f)
        case _ =>
          sys.error(s"illegal application: $t")
      }
    case SigmaElim(sigma, m, f, p) =>
      val sigmaType = iType(i, named, bound, sigma)
      checkEqual(i, sigmaType, Star)
      eval(sigma, named, Nil) match {
        case sigmaVal@VSigma(x1, x2) =>

          val pType = iType(i, named, bound, p)
          checkEqual(i, pType, sigmaVal)

          val pVal = eval(p, named, List())

          val mType = iType(i, named, bound, m)
          checkEqual(i, mType, VPi(sigmaVal, {_ => VStar}))

          val mVal = eval(m, named, List())

          val fType = iType(i, named, bound, f)
          checkEqual(i, fType, VPi(x1, {x => VPi(x2(x), y => mVal @@ VDPair(sigmaVal, x, y))}))

          mVal @@ pVal
        case _ =>
          sys.error(s"illegal application: $t")
      }

    case Lam(Star, e) =>
      // to force early error
      iType(i + 1, named,  bound + (Local(i) -> VStar), iSubst(0, Free(Local(i)), e))
      VPi(VStar, v => iType(i + 1, named + (Local(i) -> v), bound + (Local(i) -> VStar) , iSubst(0, Free(Local(i)), e)))
    case Lam(t, e) =>
      val tVal = eval(t, named, Nil)
      val tType = iType(i, named, bound, t)

      checkEqual(i, tType, Star)
      // to force early error
      iType(i + 1, named,  bound + (Local(i) -> tVal), iSubst(0, Free(Local(i)), e))

      VPi(tVal, v => iType(i + 1, named + (Local(i) -> v), bound + (Local(i) -> tVal) , iSubst(0, Free(Local(i)), e)))
  }

  def iSubst(i: Int, r: Term, it: Term): Term = it match {
    case Ann(c, c1) =>
      Ann(iSubst(i, r, c), iSubst(i, r, c1))
    case Star =>
      Star
    case Lam(t, e) =>
      Lam(iSubst(i, r, t), iSubst(i + 1, r, e))
    case Pi(ty, ty1) =>
      Pi(iSubst(i, r, ty), iSubst(i + 1, r, ty1))
    case Sigma(ty, ty1) =>
      Sigma(iSubst(i, r, ty), iSubst(i + 1, r, ty1))
    case DPair(sigma, e1, e2) =>
      DPair(iSubst(i, r, sigma), iSubst(i, r, e1), iSubst(i, r, e2))
    case SigmaElim(sigma, m, f, p) =>
      SigmaElim(iSubst(i, r, sigma), iSubst(i, r, m), iSubst(i, r, f), iSubst(i, r, p))
    case Bound(j) =>
      if (i == j) r else Bound(j)
    case Free(y) =>
      Free(y)
    case (e1 :@: e2) =>
      iSubst(i, r, e1) @@ iSubst(i, r, e2)
  }
}

trait CoreREPL extends CoreAST with CorePrinter with CoreEval with CoreCheck with CoreQuote with REPL {
  lazy val coreTE: NameEnv[Value] =
    Map(

    )

  import scala.util.parsing.combinator.{PackratParsers, ImplicitConversions}
  import scala.language.postfixOps
  type T = Term
  type V = Value
  override lazy val int = new CoreInterpreter

  trait CoreParser extends Interpreter with PackratParsers with ImplicitConversions {
    lexical.reserved += ("assume", "let", "forall", "import", "sc", "sc2", "exists", "dpair", "sigmaElim")
    lexical.delimiters += ("(", ")", "::", "->", "*", "=", "\\", ";", ".")

    type C = List[String]
    type Res[A] = C => A

    lazy val term: PackratParser[Res[Term]] =
        maybeTyped ~ ("->" ~> term) ^^ {case x ~ y => ctx: C => Pi(x(ctx), y("" :: ctx))} |
        maybeTyped
    lazy val untyped = sigmaElim | dpair | app | lam | forall | exists
    lazy val maybeTyped: PackratParser[Res[Term]] =
      untyped ~ ("::" ~> term) ^^ {case e ~ t => ctx: C => Ann(e(ctx), t(ctx))} | untyped
    lazy val app: PackratParser[Res[Term]] =
      (aTerm+) ^^ {ts => ctx: C => ts.map{_(ctx)}.reduce{_ @@ _} }
    lazy val aTerm: PackratParser[Res[Term]] = // atomicTerm
      ident ^^ {i => ctx: C => ctx.indexOf(i) match {case -1 => free(i) case j => Bound(j)}} |
        "<" ~> numericLit <~ ">" ^^ {x => ctx: C => Free(Local(x.toInt))} |
        "(" ~> term <~ ")" | "*" ^^^ {ctx: C => Star}
    lazy val dpair: PackratParser[Res[Term]] =
      ("dpair" ~> aTerm) ~ aTerm ~ aTerm ^^ {case t1 ~ t2 ~ t3 => ctx: C => DPair(t1(ctx), t2(ctx), t3(ctx))}
    lazy val sigmaElim: PackratParser[Res[Term]] =
      ("sigmaElim" ~> aTerm) ~ aTerm ~ aTerm ~ aTerm ^^ {
        case t1 ~ t2 ~ t3 ~ t4 => ctx: C => SigmaElim(t1(ctx), t2(ctx), t3(ctx), t4(ctx))
      }
    lazy val forallBs: PackratParser[Res[Term]] = {
      "." ~> term |
        bindingPar ~ forallBs ^^ { case b ~ t1 => ctx: C =>
          val bb = b(ctx)
          val t = bb._2
          Pi(t, t1(bb._1 :: ctx))
        }
    }
    lazy val existsBs: PackratParser[Res[Term]] = {
      "." ~> term |
        bindingPar ~ existsBs ^^ { case b ~ t1 => ctx: C =>
          val bb = b(ctx)
          val t = bb._2
          Sigma(t, t1(bb._1 :: ctx))
        }
    }
    lazy val lamBs: PackratParser[Res[Term]] = {
      "->" ~> term |
        bindingPar ~ lamBs ^^ { case b ~ t1 => ctx: C =>
          val bb = b(ctx)
          val t = bb._2
          Lam(t, t1(bb._1 :: ctx))
        }
    }
    lazy val forall: PackratParser[Res[Term]] =
      ("forall" ~> bindingPar) ~ forallBs ^^ { case b ~ t1 => ctx: C =>
        val bb = b(ctx)
        val t = bb._2
        Pi(t, t1(bb._1 :: ctx))
      }
    lazy val exists: PackratParser[Res[Term]] =
      ("exists" ~> bindingPar) ~ forallBs ^^ { case b ~ t1 => ctx: C =>
        val bb = b(ctx)
        val t = bb._2
        Sigma(t, t1(bb._1 :: ctx))
      }
    lazy val lam: PackratParser[Res[Term]] =
      ("\\" ~> bindingPar) ~ lamBs ^^ {case b ~ t1 => ctx: C =>
          val id = b(ctx)._1
          val t = b(ctx)._2
          var res = Lam(t, t1(id :: ctx))
          res
        }

    lazy val bindingPar: PackratParser[Res[(String, Term)]] =
      "(" ~> (ident ~ ("::" ~> term)) <~ ")" ^^ {case i ~ x => ctx: C => (i, x(ctx))}

    lazy val stmt: PackratParser[Stmt[Term]] = stmts.reduce( _ | _)
    lazy val stmts = List(letStmt, assumeStmt, importStmt, evalStmt)
    lazy val letStmt: PackratParser[Stmt[Term]] =
      "let" ~> ident ~ ("=" ~> term <~ ";") ^^ {case x ~ y => Let(x, y(Nil))}
    lazy val assumeStmt: PackratParser[Stmt[Term]] =
      "assume" ~> (bindingPar+) <~ ";" ^^ {bs => Assume(bs.map(_(Nil)))}
    lazy val importStmt: PackratParser[Stmt[Term]] =
      "import" ~> stringLit <~ ";" ^^ Import
    lazy val evalStmt: PackratParser[Stmt[Term]] =
      term <~ ";" ^^ {t => Eval(t(Nil))}
  }

  def s2name(s: String): Name =
    if (s.startsWith("$")) Assumed(s) else Global(s)

  def free(s: String): Free =
    Free(s2name(s))

  class CoreInterpreter extends CoreParser {
    val prompt: String = "TT> "

    override def itype(ne: NameEnv[Value], ctx: NameEnv[Value], i: Term): Result[Value] =
      try {
        Right(iType0(ne, ctx, i))
      } catch {
        case e: Throwable =>
          e.printStackTrace()
          Left(e.getMessage)
      }
    override def iquote(v: Value): Term =
      quote0(v)
    override def ieval(ne: NameEnv[Value], i: Term): Value =
      eval(i, ne, List())
    def typeInfo(t: Value): Value =
      t
    override def icprint(c: Term): String =
      pretty(print(0, 0, c))
    override def itprint(t: Value): String =
      pretty(print(0, 0, quote0(t)))
    def assume(state: State, x: (String, Term)): State = {
      x._2 match {
        case Star =>
          output(icprint(iquote(VStar)))
          state.copy(ctx = state.ctx + (s2name(x._1) -> VStar))
        case _ =>
          itype(state.ne, state.ctx, Ann(x._2, Star)) match {
            case Right(_) =>
              val v = ieval(state.ne, Ann(x._2, Star))
              output(icprint(iquote(v)))
              state.copy(ctx = state.ctx + (s2name(x._1) -> v))
            case Left(_) =>
              state
          }
      }

    }
    override lazy val iParse: Parser[Term] = term ^^ {_(Nil)}
    override val stmtParse: Parser[Stmt[Term]] = stmt
  }
}
