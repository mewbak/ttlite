package superspec

import mrsc.core._
import superspec.lambdapi._

// TODO
trait Rebuilder extends CoreSubst

// base supercompiler for PI
trait PiSc extends CoreSubst {

  // sub - a substitution, using only this substitution we can perform a fold
  case class ElimBranch(ptr: CTerm, sub: Subst)
  trait DriveStep {
    def step(t: CTerm): Step
  }

  case class ElimDStep(sel: Name, cases: List[ElimBranch]) extends DriveStep {
    override def step(t: CTerm) =
      VariantsStep(sel, cases.map{branch => branch -> applySubst(t, Map(sel -> branch.ptr))})
  }
  case object StopDStep extends DriveStep {
    override def step(t: CTerm) = StopStep
  }
  // todo: generalize or specialize (via specializing to each data type)
  case class DecompositionDStep(comp: CTerm, next: CTerm) extends DriveStep {
    override def step(t: CTerm) = DecompositionStep(comp, next)
  }
  // the only concern is driving neutral terms!!
  // everything else we get from evaluation!!
  def driveTerm(c: CTerm): DriveStep


  trait Label
  case object NormLabel extends Label {
    override def toString = "->"
  }
  case class CaseBranchLabel(sel: Name, elim: ElimBranch) extends Label {
    override def toString = sel + " = " + elim
  }

  case class DecompositionLabel(f: CTerm) extends Label {
    override def toString = "Cong: " + f
  }
  // higher-level steps performed by supercompiler
  // this higher-level steps are translated into low-level
  trait Step {
    val graphStep: GraphRewriteStep[CTerm, Label]
  }
  case class NormStep(next: CTerm) extends Step {
    override val graphStep = AddChildNodesStep[CTerm, Label](List(next -> NormLabel))
  }
  case object StopStep extends Step {
    override val graphStep = CompleteCurrentNodeStep[CTerm, Label]()
  }
  case class VariantsStep(sel: Name, cases: List[(ElimBranch, CTerm)]) extends Step {
    override val graphStep = {
      val ns = cases map { v => (v._2, CaseBranchLabel(sel, v._1)) }
      AddChildNodesStep[CTerm, Label](ns)
    }
  }
  case class DecompositionStep(f: CTerm, next: CTerm) extends Step {
    override val graphStep = AddChildNodesStep[CTerm, Label](List(next -> DecompositionLabel(f)))
  }

  trait PiRules extends MRSCRules[CTerm, Label] {
    type Signal = Option[N]
    // todo: really, it should be more sophisticated: we need to check elimBranch for subst
    // in order to ensure that we respect eliminator recursion
    def inspect(g: G): Signal =
      g.current.ancestors.find(n => findRenaming(g.current.conf, n.conf).isDefined)
  }
  trait Folding extends PiRules {
    override def fold(signal: Signal, g: G): List[S] = {
      signal.map(n => FoldStep(n.sPath): S).toList
    }
  }
  trait NoRebuildings extends PiRules {
    override def rebuild(signal: Signal, g: G) = List()
  }
  // The simplest termination strategy
  trait Termination extends PiRules {
    val maxDepth: Int
    override def steps(g: G): List[S] =
      if (g.depth > maxDepth) List(StopStep.graphStep) else super.steps(g)
  }
  trait Driving extends PiRules {
    override def drive(signal: Signal, g: G): List[S] = {
      if (signal.isDefined)
        return List()
      val t = g.current.conf
      val piStep = driveTerm(t).step(t)
      List(piStep.graphStep)
    }
  }

}

// TODO: split into components, infer types during residualization
// (Eq A ...) - A should be derived from passed environment
trait PiResiduator extends PiSc with CoreAST with EqAST with NatAST with CoreEval with CoreSubst {

  def residuateToVal(g: TGraph[CTerm, Label], nEnv: NameEnv[Value], bEnv: Env): Value = {
    fold(g, g.root, nEnv, bEnv, Map())
  }

  /// context!!!!!
  def fold(g: TGraph[CTerm, Label], node: TNode[CTerm, Label], nEnv: NameEnv[Value], bEnv: Env, dRed: Map[CTerm, Value]): Value =
    node.base match {
      // recursive node
      case Some(tPath) =>
        dRed(g.get(tPath).conf)
      case None =>
        node.outs match {
          case Nil =>
            cEval(node.conf, nEnv, bEnv)
          // todo: get rid off this
          case TEdge(n1, DecompositionLabel(f)) :: Nil =>
            cEval(f, nEnv, bEnv) @@ fold(g, n1, nEnv, bEnv, dRed)
        }
    }
}

trait NatResiduator extends PiResiduator {
  override def fold(g: TGraph[CTerm, Label], node: TNode[CTerm, Label], nEnv: NameEnv[Value], bEnv: Env, dRed: Map[CTerm, Value]): Value =
    node.outs match {
      case
        TEdge(nodeZ, CaseBranchLabel(sel1, ElimBranch(Zero, _))) ::
          TEdge(nodeS, CaseBranchLabel(sel2, ElimBranch(Succ(Inf(Free(fresh))), _))) ::
          Nil =>
        // todo: infer real motive
        val motive =
          VLam {n => VNat}
        val zCase =
          fold(g, nodeZ, nEnv, bEnv, dRed)
        val sCase =
          VLam {n => VLam {rec => fold(g, nodeS, fresh -> n :: nEnv, bEnv, dRed + (node.conf -> rec))}}
        VNeutral(NFree(Global("natElim"))) @@ motive @@ zCase @@ sCase @@ lookup(sel1, nEnv).get
      case _ =>
        super.fold(g, node, nEnv, bEnv, dRed)
    }
}

case class SC(in: String) extends Command

object PiScREPL
  extends PiSc
  with CoreREPL
  with CoreSubst
  with CoreDriver
  with EqREPL
  with EqSubst
  with EqDriver
  with NatREPL
  with NatSubst
  with NatDriver
  with PiResiduator
  with LamResiduator
  with NatResiduator
  with PiGraphPrettyPrinter {

  val te = natTE ++ eqTE
  val ve = natVE ++ eqVE
  override def initialState = State(interactive = true, ve, te, Set())
  override def commands: List[Cmd] =
    Cmd(List(":sc"), "<expr>", x => SC(x), "supercompile") :: super.commands

  override def handleCommand(state: State, cmd: Command): State = cmd match {
    case SC(in) =>
      import int._
      val parsed = int.parseIO(int.iiparse, in)
      parsed match {
        case Some(t) =>
          val l = iquote(ieval(state.ne, t))
          val goal = l
          val rules: PiRules = new PiSc1
          val gs = GraphGenerator(rules, goal).toList
          for (g <- gs) {
            val tGraph = Transformations.transpose(g)
            println(tgToString(tGraph))
            val resVal = residuateToVal(tGraph, state.ne, List())
            val tRes = iquote(resVal)
            println(int.icprint(tRes))

          }
        case None =>
      }
      state
    case _ =>
      super.handleCommand(state, cmd)
  }

  class PiSc1 extends PiRules with Driving with Folding with Termination with NoRebuildings {
    val maxDepth = 10
  }
}