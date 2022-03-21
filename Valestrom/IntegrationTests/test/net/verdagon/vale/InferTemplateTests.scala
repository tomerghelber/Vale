package net.verdagon.vale

import net.verdagon.vale.templar.ast.ParameterT
import net.verdagon.vale.templar.names.{CitizenNameT, CitizenTemplateNameT, CodeVarNameT, FullNameT}
import net.verdagon.vale.templar.templata.{CoordTemplata, simpleName}
import net.verdagon.vale.templar.types.{BorrowT, CoordT, OwnT, StructTT}
import net.verdagon.von.VonInt
import org.scalatest.{FunSuite, Matchers}

class InferTemplateTests extends FunSuite with Matchers {
  test("Test inferring a borrowed argument") {
    val compile = RunCompilation.test(
      """
        |struct Muta { hp int; }
        |func moo<T>(m &T) int { ret m.hp; }
        |exported func main() int {
        |  x = Muta(10);
        |  ret moo(&x);
        |}
      """.stripMargin)

    val moo = compile.expectTemputs().lookupFunction("moo")
    moo.header.params match {
      case Vector(ParameterT(CodeVarNameT("m"), _, CoordT(BorrowT,_))) =>
    }
    moo.header.fullName.last.templateArgs match {
      case Vector(CoordTemplata(CoordT(OwnT, StructTT(FullNameT(PackageCoordinate.TEST_TLD, Vector(), CitizenNameT(CitizenTemplateNameT("Muta"), Vector())))))) =>
    }

    compile.evalForKind(Vector()) match { case VonInt(10) => }
  }
  test("Test inferring a borrowed static sized array") {
    val compile = RunCompilation.test(
      """
        |struct Muta { hp int; }
        |func moo<N>(m &[#N]Muta) int { ret m[0].hp; }
        |exported func main() int {
        |  x = [#][Muta(10)];
        |  ret moo(&x);
        |}
      """.stripMargin)

    compile.evalForKind(Vector()) match { case VonInt(10) => }
  }
  test("Test inferring an owning static sized array") {
    val compile = RunCompilation.test(
      """
        |struct Muta { hp int; }
        |func moo<N>(m [#N]Muta) int { ret m[0].hp; }
        |exported func main() int {
        |  x = [#][Muta(10)];
        |  ret moo(x);
        |}
      """.stripMargin)

    compile.evalForKind(Vector()) match { case VonInt(10) => }
  }
}
