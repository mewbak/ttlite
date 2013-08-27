package superspec

import superspec.tt._
import mrsc.core._

trait ListDriver extends CoreDriver with ListAST with ListEval {

  case object ConsLabel extends Label
  case object ListLabel extends Label

  override def driveNeutral(n: Neutral): DriveStep = n match {
    case NPiListElim(VPiList(a), m, nilCase, consCase, l) => l match {
      case NFree(n) =>
        val aType = quote0(a)
        val caseNil = ElimLabel(n, PiNil(PiList(aType)), Map())

        val h1 = freshLocal(quote0(a))
        val t1 = freshLocal(quote0(VPiList(a)))
        val caseCons = ElimLabel(n, PiCons(PiList(aType), h1, t1), Map(n -> t1))

        ElimDStep(caseNil, caseCons)
      case n =>
        driveNeutral(n)
    }
    case _ =>
      super.driveNeutral(n)
  }

  override def decompose(c: Conf): DriveStep = c.term match {
    case PiCons(PiList(a), h, t) =>
      DecomposeDStep(ConsLabel, Conf(h, a), Conf(t, c.tp))
    case PiList(a) =>
      DecomposeDStep(ListLabel, Conf(a, c.tp))
    case _ =>
      super.decompose(c)
  }

}

trait ListResiduator extends BaseResiduator with ListDriver {
  override def fold(node: N, env: NameEnv[Value], bound: Env, recM: Map[TPath, Value]): Value =
    node.outs match {
      case
        TEdge(nodeZ, ElimLabel(sel, PiNil(PiList(a)), _)) ::
          TEdge(nodeS, ElimLabel(_, PiCons(_, Free(hN), Free(tN)), _)) ::
          Nil =>

        val aVal = eval(a, env, bound)
        val motive =
          VLam(VPiList(aVal), n => eval(node.conf.tp, env + (sel -> n), n :: bound))

        val nilCase =
          fold(nodeZ, env, bound, recM)
        val consCase =
          VLam (aVal, h => VLam (VPiList(aVal), t => VLam (motive @@ t, rec =>
            fold(nodeS, env + (hN -> h) + (tN -> t), rec :: t :: h :: bound, recM + (node.tPath -> rec)))))

        listElim(VPiList(aVal), motive, nilCase, consCase, env(sel))
      case TEdge(h, ConsLabel) :: TEdge(t, ConsLabel) :: Nil =>
        val a = eval(node.conf.tp, env, bound)
        VPiCons(a, fold(h, env, bound, recM), fold(t, env, bound, recM))
      case TEdge(a, ListLabel) :: Nil =>
        VPiList(fold(a, env, bound, recM))
      case _ =>
        super.fold(node, env, bound, recM)
    }
}

trait ListProofResiduator extends ListResiduator with ProofResiduator {
  override def proofFold(node: N,
                         env: NameEnv[Value], bound: Env, recM: Map[TPath, Value],
                         env2: NameEnv[Value], bound2: Env, recM2: Map[TPath, Value]): Value =
    node.outs match {
      case
        TEdge(nodeZ, ElimLabel(sel, PiNil(PiList(a)), _)) ::
          TEdge(nodeS, ElimLabel(_, PiCons(_, Free(hN), Free(tN)), _)) ::
          Nil =>

        val aVal = eval(a, env, bound)

        val motive =
          VLam(VPiList(aVal), n =>
            VEq(
              eval(node.conf.tp, env + (sel -> n), n :: bound),
              eval(node.conf.term, env + (sel -> n), n :: bound),
              fold(node, env + (sel -> n), n :: bound, recM)))

        val nilCase =
          proofFold(nodeZ,
            env, bound, recM,
            env2, bound2, recM2)

        val consCase =
          VLam (aVal, h => VLam (VPiList(aVal), t => VLam (motive @@ t, {rec =>
            // SIC!! - node, not nodeS!!
            val rec1 = fold(node, env + (sel -> t), t :: bound, recM)
            proofFold(nodeS,
              env + (hN -> h) + (tN -> t),
              rec1 :: t :: h :: bound,
              recM + (node.tPath -> rec1),

              env2 + (hN -> h) + (tN -> t),
              rec :: t :: h :: bound2,
              recM2 + (node.tPath -> rec))})))

        listElim(VPiList(aVal), motive, nilCase, consCase, env(sel))
      case TEdge(h, ConsLabel) :: TEdge(t, ConsLabel) :: Nil =>
        val VPiList(a) = eval(node.conf.tp, env, bound)
        val h1 = eval(h.conf.term, env, bound)
        val h2 = fold(h, env, bound, recM)
        val eq_h1_h2 = proofFold(h, env, bound, recM, env2, bound2, recM2)

        val t1 = eval(t.conf.term, env, bound)
        val t2 = fold(t, env, bound, recM)
        val eq_t1_t2 = proofFold(t, env, bound, recM, env2, bound2, recM2)

        'cong2 @@ a @@ VPiList(a) @@ VPiList(a) @@
          VLam(a, x => VLam(VPiList(a), y => VPiCons(VPiList(a), x, y))) @@
          h1 @@ h2 @@ eq_h1_h2 @@
          t1 @@ t2 @@ eq_t1_t2
      case TEdge(n1, ListLabel) :: Nil =>
        val tp = eval(node.conf.tp, env, bound)
        'cong1 @@
          tp @@
          tp @@
          VLam(tp, a => VPiList(a)) @@
          eval(n1.conf.term, env, bound) @@
          fold(n1, env, bound, recM) @@
          proofFold(n1, env, bound, recM, env2, bound2, recM2)
      case _ =>
        super.proofFold(node, env, bound, recM, env2, bound2, recM2)
    }
}
