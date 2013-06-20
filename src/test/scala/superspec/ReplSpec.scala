package superspec

import org.scalatest.FunSpec

import superspec.lambda._
import superspec.lambdapi._

class ReplSpec extends FunSpec {
  describe("Lambda REPL should process prelude.st without errors") {
    LambdaREPL.main(Array("prelude.st"))
  }

  describe("Lambda Pi REPL should process prelude.lp without errors") {
    CoreREPLMain.main(Array("prelude.lp"))
  }

  describe("Nat REPL should process prelude.nat without errors") {
    NatREPLMain.main(Array("prelude.nat"))
  }

  describe("All REPL should process prelude.all and lists.pi without errors") {
    REPLMain.main(Array("prelude.all"))
  }
}