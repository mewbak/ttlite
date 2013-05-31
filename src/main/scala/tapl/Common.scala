package tapl

trait Common {
  trait Name
  case class Global(n: String) extends Name
  case class Local(i: Int) extends Name
  case class Quote(i: Int) extends Name

  type Result[A] = Either[String, A]
  type NameEnv[V] = List[(Name, V)]

  trait Stmt[+I, +TInf]
  case class Let[I](n: String, i: I) extends Stmt[I, Nothing]
  case class Assume[TInf](ns: List[(String, TInf)]) extends Stmt[Nothing, TInf]
  case class Eval[I](e: I) extends Stmt[I, Nothing]
  case class PutStrLm(s: String) extends Stmt[Nothing, Nothing]
  case class Out(s: String) extends Stmt[Nothing, Nothing]

  // TODO
  val vars: Stream[String] = null

  // utility
  def lookup[A, B](k: A, kvs: List[(A, B)]): Option[B] =
    kvs.find(_._1 == k).map(_._2)
}