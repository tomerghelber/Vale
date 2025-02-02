package dev.vale.typing.expression

import dev.vale.parsing.ast.LoadAsBorrowP
import dev.vale.postparsing.{IRuneS, ITemplataType, PatternInputRuneS}
import dev.vale.{Interner, Profiler, RangeS, vassert, vassertSome, vfail}
import dev.vale.postparsing.patterns.AtomSP
import dev.vale.postparsing.rules.{IRulexSR, RuneUsage}
import dev.vale.typing.{ArrayCompiler, CompileErrorExceptionT, ConvertHelper, InferCompiler, InitialSend, RangedInternalErrorT, Compiler, TypingPassOptions, CompilerOutputs, WrongNumberOfDestructuresError}
import dev.vale.typing.ast.{ConstantIntTE, DestroyMutRuntimeSizedArrayTE, DestroyStaticSizedArrayIntoLocalsTE, DestroyTE, LetNormalTE, LocalLookupTE, LocationInFunctionEnvironment, ReferenceExpressionTE, ReferenceMemberLookupTE, SoftLoadTE}
import dev.vale.typing.env.{ILocalVariableT, NodeEnvironmentBox, TemplataEnvEntry}
import dev.vale.typing.function.DestructorCompiler
import dev.vale.typing.names.RuneNameT
import dev.vale.typing.templata.CoordTemplata
import dev.vale.typing.types.{BorrowT, CoordT, OwnT, OwnershipT, RuntimeSizedArrayTT, ShareT, StaticSizedArrayTT, StructTT, WeakT}
import dev.vale.highertyping._
import dev.vale.parsing.ast.LoadAsBorrowP
import dev.vale.postparsing.rules.IRulexSR
import dev.vale.postparsing._
import dev.vale.typing.env._
import dev.vale.typing.templata._
import dev.vale.typing.types._
import dev.vale.typing._
import dev.vale.typing.ast._
import dev.vale.{Interner, Profiler, RangeS, vassert, vassertSome, vfail}

import scala.collection.immutable.List

class PatternCompiler(
    opts: TypingPassOptions,

    interner: Interner,
    inferCompiler: InferCompiler,
    arrayCompiler: ArrayCompiler,
    convertHelper: ConvertHelper,
    destructorCompiler: DestructorCompiler,
    localHelper: LocalHelper) {
  // Note: This will unlet/drop the input expressions. Be warned.
  // patternInputsTE is a list of reference expression because they're coming in from
  // god knows where... arguments, the right side of a let, a variable, don't know!
  // If a pattern needs to send it to multiple places, the pattern is free to put it into
  // a local variable.
  // PatternCompiler must be sure to NOT USE IT TWICE! That would mean copying the entire
  // expression subtree that it contains!
  def translatePatternList(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironment,
    patternsA: Vector[AtomSP],
    patternInputsTE: Vector[ReferenceExpressionTE],
    // This would be a continuation-ish lambda that evaluates:
    // - The body of an if-let statement
    // - The body of a match's case statement
    // - The rest of the pattern that contains this pattern
    // But if we're doing a regular let statement, then it doesn't need to contain everything past it.
    afterPatternsSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, Vector[ILocalVariableT]) => ReferenceExpressionTE):
  ReferenceExpressionTE = {
    Profiler.frame(() => {
      iterateTranslateListAndMaybeContinue(
        coutputs, nenv, life, Vector(), patternsA.toList, patternInputsTE.toList, afterPatternsSuccessContinuation)
    })
  }

  def iterateTranslateListAndMaybeContinue(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironment,
    liveCaptureLocals: Vector[ILocalVariableT],
    patternsA: List[AtomSP],
    patternInputsTE: List[ReferenceExpressionTE],
    // This would be a continuation-ish lambda that evaluates:
    // - The body of an if-let statement
    // - The body of a match's case statement
    // - The rest of the pattern that contains this pattern
    // But if we're doing a regular let statement, then it doesn't need to contain everything past it.
    afterPatternsSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, Vector[ILocalVariableT]) => ReferenceExpressionTE):
  ReferenceExpressionTE = {
    vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

    (patternsA, patternInputsTE) match {
      case (Nil, Nil) => afterPatternsSuccessContinuation(coutputs, nenv, liveCaptureLocals)
      case (headPatternA :: tailPatternsA, headPatternInputTE :: tailPatternInputsTE) => {
        innerTranslateSubPatternAndMaybeContinue(
          coutputs, nenv, life + 0, headPatternA, liveCaptureLocals, headPatternInputTE,
          (coutputs, nenv, life, liveCaptureLocals) => {
            vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

            iterateTranslateListAndMaybeContinue(
              coutputs, nenv, life + 1, liveCaptureLocals, tailPatternsA, tailPatternInputsTE, afterPatternsSuccessContinuation)
          })
      }
      case _ => vfail("wat")
    }
  }

  // Note: This will unlet/drop the input expression. Be warned.
  def inferAndTranslatePattern(
      coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
      life: LocationInFunctionEnvironment,
      rules: Vector[IRulexSR],
      runeToType: Map[IRuneS, ITemplataType],
      pattern: AtomSP,
      unconvertedInputExpr: ReferenceExpressionTE,
      // This would be a continuation-ish lambda that evaluates:
      // - The body of an if-let statement
      // - The body of a match's case statement
      // - The rest of the pattern that contains this pattern
      // But if we're doing a regular let statement, then it doesn't need to contain everything past it.
      afterPatternsSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE):
  ReferenceExpressionTE = {
    Profiler.frame(() => {

      // The rules are different depending on the incoming type.
      // See Impl Rule For Upcasts (IRFU).
      val convertedInputExpr =
        pattern.coordRune match {
          case None => {
            unconvertedInputExpr
          }
          case Some(receiverRune) => {
            val templatasByRune =
              inferCompiler.solveExpectComplete(
                nenv.snapshot,
                coutputs,
                rules,
                runeToType,
                pattern.range,
                Vector(),
                Vector(
                  InitialSend(
                    RuneUsage(pattern.range, PatternInputRuneS(pattern.range.begin)),
                    receiverRune,
                    CoordTemplata(unconvertedInputExpr.result.reference))))
            nenv.addEntries(
              interner,
              templatasByRune.toVector
                .map({ case (key, value) => (interner.intern(RuneNameT(key)), TemplataEnvEntry(value)) }))
            val CoordTemplata(expectedCoord) = vassertSome(templatasByRune.get(receiverRune.rune))

            // Now we convert m to a Marine. This also checks that it *can* be
            // converted to a Marine.
            convertHelper.convert(nenv.snapshot, coutputs, pattern.range, unconvertedInputExpr, expectedCoord)
          }
        }

      innerTranslateSubPatternAndMaybeContinue(coutputs, nenv, life, pattern, Vector(), convertedInputExpr, afterPatternsSuccessContinuation)
    })
  }

  private def innerTranslateSubPatternAndMaybeContinue(
      coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
      life: LocationInFunctionEnvironment,
      pattern: AtomSP,
      previousLiveCaptureLocals: Vector[ILocalVariableT],
      inputExpr: ReferenceExpressionTE,
      // This would be a continuation-ish lambda that evaluates:
      // - The body of an if-let statement
      // - The body of a match's case statement
      // - The rest of the pattern that contains this pattern
      // But if we're doing a regular let statement, then it doesn't need to contain everything past it.
      afterSubPatternSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE):
  ReferenceExpressionTE = {
    vassert(previousLiveCaptureLocals.map(_.id) == previousLiveCaptureLocals.map(_.id).distinct)

    val AtomSP(range, maybeCaptureLocalVarA, maybeVirtuality, coordRuneA, maybeDestructure) = pattern

    if (maybeVirtuality.nonEmpty) {
      // This is actually to be expected for when we translate the patterns from the
      // function's parameters. Ignore them.
    }

    // We make it here instead of down in the maybeDestructure clauses because whether we destructure it or not
    // is unrelated to whether we destructure it.


    var currentInstructions = Vector[ReferenceExpressionTE]()

    val (maybeCaptureLocalVarT, exprToDestructureOrDropOrPassTE) =
      maybeCaptureLocalVarA match {
        case None => (None, inputExpr)
        case Some(captureS) => {
          val localS = vassertSome(vassertSome(nenv.nearestBlockEnv())._2.locals.find(_.varName == captureS.name))
          val localT = localHelper.makeUserLocalVariable(coutputs, nenv, range, localS, inputExpr.result.reference)
          currentInstructions = currentInstructions :+ LetNormalTE(localT, inputExpr)
          val capturedLocalAliasTE =
            localHelper.softLoad(nenv, range, LocalLookupTE(range, localT), LoadAsBorrowP)
          (Some(localT), capturedLocalAliasTE)
        }
      }
    // If we captured it as a local, then the exprToDestructureOrDropOrPassTE should be a non-owning alias of it
    if (maybeCaptureLocalVarT.nonEmpty) {
      vassert(exprToDestructureOrDropOrPassTE.result.reference.ownership != OwnT)
    }

    val liveCaptureLocals = previousLiveCaptureLocals ++ maybeCaptureLocalVarT.toVector
    vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

    Compiler.consecutive(
      currentInstructions :+
        (maybeDestructure match {
        case None => {
          // Do nothing
          afterSubPatternSuccessContinuation(coutputs, nenv, life + 0, liveCaptureLocals)
        }
        case Some(listOfMaybeDestructureMemberPatterns) => {
          exprToDestructureOrDropOrPassTE.result.reference.ownership match {
            case OwnT => {
              // We aren't capturing the var, so the destructuring should consume the incoming value.
              destructureOwning(
                coutputs, nenv, life + 1, range, liveCaptureLocals, exprToDestructureOrDropOrPassTE, listOfMaybeDestructureMemberPatterns, afterSubPatternSuccessContinuation)
            }
            case BorrowT | ShareT => {
              destructureNonOwningAndMaybeContinue(
                coutputs, nenv, life + 2, range, liveCaptureLocals, exprToDestructureOrDropOrPassTE, listOfMaybeDestructureMemberPatterns, afterSubPatternSuccessContinuation)
            }
          }
        }
      }))
  }

  private def destructureOwning(
      coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
      life: LocationInFunctionEnvironment,
      range: RangeS,
      initialLiveCaptureLocals: Vector[ILocalVariableT],
      inputExpr: ReferenceExpressionTE,
      listOfMaybeDestructureMemberPatterns: Vector[AtomSP],
      afterDestructureSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE
  ): ReferenceExpressionTE = {
    vassert(initialLiveCaptureLocals.map(_.id) == initialLiveCaptureLocals.map(_.id).distinct)

    val CoordT(OwnT, expectedContainerKind) = inputExpr.result.reference
    expectedContainerKind match {
      case StructTT(_) => {
        // Example:
        //   struct Marine { bork: Bork; }
        //   Marine(b) = m;
        // In this case, expectedStructType1 = TypeName1("Marine") and
        // destructureMemberPatterns = Vector(CaptureSP("b", FinalP, None)).
        // Since we're receiving an owning reference, and we're *not* capturing
        // it in a variable, it will be destroyed and we will harvest its parts.
        translateDestroyStructInnerAndMaybeContinue(
          coutputs, nenv, life + 0, range, initialLiveCaptureLocals, listOfMaybeDestructureMemberPatterns, inputExpr, afterDestructureSuccessContinuation)
      }
      case staticSizedArrayT @ StaticSizedArrayTT(size, _, _, elementType) => {
        if (size != listOfMaybeDestructureMemberPatterns.size) {
          throw CompileErrorExceptionT(RangedInternalErrorT(range, "Wrong num exprs!"))
        }

        val elementLocals = (0 until size).map(i => localHelper.makeTemporaryLocal(nenv, life + 3 + i, elementType)).toVector
        val destroyTE = DestroyStaticSizedArrayIntoLocalsTE(inputExpr, staticSizedArrayT, elementLocals)
        val liveCaptureLocals = initialLiveCaptureLocals ++ elementLocals
        vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

        if (elementLocals.size != listOfMaybeDestructureMemberPatterns.size) {
          throw CompileErrorExceptionT(WrongNumberOfDestructuresError(range, listOfMaybeDestructureMemberPatterns.size, elementLocals.size))
        }
        val lets =
          makeLetsForOwnAndMaybeContinue(
            coutputs, nenv, life + 4, liveCaptureLocals, elementLocals.toList, listOfMaybeDestructureMemberPatterns.toList, afterDestructureSuccessContinuation)
        Compiler.consecutive(Vector(destroyTE, lets))
      }
      case rsa @ RuntimeSizedArrayTT(_, _) => {
        if (listOfMaybeDestructureMemberPatterns.nonEmpty) {
          throw CompileErrorExceptionT(RangedInternalErrorT(range, "Can only destruct RSA with zero destructure targets."))
        }
        DestroyMutRuntimeSizedArrayTE(inputExpr)
      }
      case _ => vfail("impl!")
    }
  }

  private def destructureNonOwningAndMaybeContinue(
      coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
      life: LocationInFunctionEnvironment,
      range: RangeS,
      liveCaptureLocals: Vector[ILocalVariableT],
      containerTE: ReferenceExpressionTE,
      listOfMaybeDestructureMemberPatterns: Vector[AtomSP],
      afterDestructureSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE
  ): ReferenceExpressionTE = {
    vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

    val localT = localHelper.makeTemporaryLocal(nenv, life + 0, containerTE.result.reference)
    val letTE = LetNormalTE(localT, containerTE)
    val containerAliasingExprTE =
      localHelper.softLoad(nenv, range, LocalLookupTE(range, localT), LoadAsBorrowP)

    Compiler.consecutive(
      Vector(
        letTE,
        iterateDestructureNonOwningAndMaybeContinue(
          coutputs, nenv, life + 1, range, liveCaptureLocals, containerTE.result.reference, containerAliasingExprTE, 0, listOfMaybeDestructureMemberPatterns.toList, afterDestructureSuccessContinuation)))
  }

  private def iterateDestructureNonOwningAndMaybeContinue(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironment,
    range: RangeS,
    liveCaptureLocals: Vector[ILocalVariableT],
    expectedContainerCoord: CoordT,
    containerAliasingExprTE: ReferenceExpressionTE,
    memberIndex: Int,
    listOfMaybeDestructureMemberPatterns: List[AtomSP],
    afterDestructureSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE
  ): ReferenceExpressionTE = {
    vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

    val CoordT(expectedContainerOwnership, expectedContainerKind) = expectedContainerCoord

    listOfMaybeDestructureMemberPatterns match {
      case Nil => afterDestructureSuccessContinuation(coutputs, nenv, life + 0, liveCaptureLocals)
      case headMaybeDestructureMemberPattern :: tailDestructureMemberPatternMaybes => {
        val memberAddrExprTE =
          expectedContainerKind match {
            case structTT@StructTT(_) => {
              // Example:
              //   struct Marine { bork: Bork; }
              //   Marine(b) = m;
              // In this case, expectedStructType1 = TypeName1("Marine") and
              // destructureMemberPatterns = Vector(CaptureSP("b", FinalP, None)).
              // Since we're receiving an owning reference, and we're *not* capturing
              // it in a variable, it will be destroyed and we will harvest its parts.

              loadFromStruct(coutputs, range, containerAliasingExprTE, structTT, memberIndex)
            }
            case staticSizedArrayT@StaticSizedArrayTT(size, _, _, elementType) => {
              loadFromStaticSizedArray(range, staticSizedArrayT, expectedContainerCoord, expectedContainerOwnership, containerAliasingExprTE, memberIndex)
            }
            case other => {
              throw CompileErrorExceptionT(RangedInternalErrorT(range, "Unknown type to destructure: " + other))
            }
          }

        val memberOwnershipInStruct = memberAddrExprTE.result.reference.ownership
        val coerceToOwnership = loadResultOwnership(memberOwnershipInStruct)

        val loadExpr = SoftLoadTE(memberAddrExprTE, coerceToOwnership)
        innerTranslateSubPatternAndMaybeContinue(
          coutputs, nenv, life + 1, headMaybeDestructureMemberPattern, liveCaptureLocals, loadExpr,
          (coutputs, nenv, life, liveCaptureLocals) => {
            vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

            val nextMemberIndex = memberIndex + 1
            iterateDestructureNonOwningAndMaybeContinue(
              coutputs,
              nenv,
              life,
              range,
              liveCaptureLocals,
              expectedContainerCoord,
              containerAliasingExprTE,
              nextMemberIndex,
              tailDestructureMemberPatternMaybes,
              afterDestructureSuccessContinuation)
          })
      }
    }
  }

  private def translateDestroyStructInnerAndMaybeContinue(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironment,
    range: RangeS,
    initialLiveCaptureLocals: Vector[ILocalVariableT],
    innerPatternMaybes: Vector[AtomSP],
    inputStructExpr: ReferenceExpressionTE,
    afterDestroySuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE
  ): ReferenceExpressionTE = {
    vassert(initialLiveCaptureLocals.map(_.id) == initialLiveCaptureLocals.map(_.id).distinct)

    val CoordT(_, structTT @ StructTT(_)) = inputStructExpr.result.reference
    val structDefT = coutputs.getStructDefForRef(structTT)
    // We don't pattern match against closure structs.

    val memberLocals =
      structDefT.members
        .map(_.tyype.expectReferenceMember().reference)
        .zipWithIndex
        .map({ case (memberType, i) => localHelper.makeTemporaryLocal(nenv, life + 1 + i, memberType) }).toVector
    val destroyTE = DestroyTE(inputStructExpr, structTT, memberLocals)
    val liveCaptureLocals = initialLiveCaptureLocals ++ memberLocals
    vassert(liveCaptureLocals.map(_.id) == liveCaptureLocals.map(_.id).distinct)

    if (memberLocals.size != innerPatternMaybes.size) {
      throw CompileErrorExceptionT(WrongNumberOfDestructuresError(range, innerPatternMaybes.size, memberLocals.size))
    }
    val restTE =
      makeLetsForOwnAndMaybeContinue(
        coutputs,
        nenv,
        life + 0,
        liveCaptureLocals,
        memberLocals.toList,
        innerPatternMaybes.toList,
        afterDestroySuccessContinuation)
    Compiler.consecutive(Vector(destroyTE, restTE))
  }

  private def makeLetsForOwnAndMaybeContinue(
    coutputs: CompilerOutputs,
    nenv: NodeEnvironmentBox,
    life: LocationInFunctionEnvironment,
    initialLiveCaptureLocals: Vector[ILocalVariableT],
    memberLocalVariables: List[ILocalVariableT],
    innerPatternMaybes: List[AtomSP],
    afterLetsSuccessContinuation: (CompilerOutputs, NodeEnvironmentBox, LocationInFunctionEnvironment, Vector[ILocalVariableT]) => ReferenceExpressionTE
  ): ReferenceExpressionTE = {
    vassert(initialLiveCaptureLocals.map(_.id) == initialLiveCaptureLocals.map(_.id).distinct)

    vassert(memberLocalVariables.size == innerPatternMaybes.size)

    (memberLocalVariables, innerPatternMaybes) match {
      case (Nil, Nil) => {
        afterLetsSuccessContinuation(coutputs, nenv, life + 0, initialLiveCaptureLocals)
      }
      case (headMemberLocalVariable :: tailMemberLocalVariables, headMaybeInnerPattern :: tailInnerPatternMaybes) => {
        val unletExpr = localHelper.unletLocalWithoutDropping(nenv, headMemberLocalVariable)
        val liveCaptureLocals = initialLiveCaptureLocals.filter(_.id != headMemberLocalVariable.id)
        vassert(liveCaptureLocals.size == initialLiveCaptureLocals.size - 1)

        innerTranslateSubPatternAndMaybeContinue(
          coutputs, nenv, life + 1, headMaybeInnerPattern, liveCaptureLocals, unletExpr,
          (coutputs, nenv, life, liveCaptureLocals) => {
            vassert(initialLiveCaptureLocals.map(_.id) == initialLiveCaptureLocals.map(_.id).distinct)

            makeLetsForOwnAndMaybeContinue(
              coutputs, nenv, life, liveCaptureLocals, tailMemberLocalVariables, tailInnerPatternMaybes, afterLetsSuccessContinuation)
          })
      }
    }
  }

  private def loadResultOwnership(memberOwnershipInStruct: OwnershipT): OwnershipT = {
    memberOwnershipInStruct match {
      case OwnT => BorrowT
      case BorrowT => BorrowT
      case WeakT => WeakT
      case ShareT => ShareT
    }
  }

  private def loadFromStruct(
    coutputs: CompilerOutputs,
    range: RangeS,
    containerAlias: ReferenceExpressionTE,
    structTT: StructTT,
    index: Int) = {
    val structDefT = coutputs.getStructDefForRef(structTT)

    val member = structDefT.members(index)

    val memberCoord = member.tyype.expectReferenceMember().reference

    ReferenceMemberLookupTE(
      range,
      containerAlias,
      structDefT.fullName.addStep(structDefT.members(index).name),
      memberCoord,
      member.variability)
  }

  private def loadFromStaticSizedArray(
    range: RangeS,
    staticSizedArrayT: StaticSizedArrayTT,
    localCoord: CoordT,
    structOwnership: OwnershipT,
    containerAlias: ReferenceExpressionTE,
    index: Int) = {
    arrayCompiler.lookupInStaticSizedArray(range, containerAlias, ConstantIntTE(index, 32), staticSizedArrayT)
  }
}
