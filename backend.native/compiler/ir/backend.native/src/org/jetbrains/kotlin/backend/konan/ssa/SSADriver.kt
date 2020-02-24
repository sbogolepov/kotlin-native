package org.jetbrains.kotlin.backend.konan.ssa

import llvm.LLVMContextCreate
import llvm.LLVMCreateDIBuilder
import llvm.LLVMModuleCreateWithNameInContext
import org.jetbrains.kotlin.backend.common.phaser.namedIrModulePhase
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.backend.konan.llvm.createLlvmDeclarations
import org.jetbrains.kotlin.backend.konan.llvm.llvmContext
import org.jetbrains.kotlin.backend.konan.makeKonanModuleOpPhase
import org.jetbrains.kotlin.backend.konan.ssa.llvm.LLVMModuleFromSSA
import org.jetbrains.kotlin.backend.konan.ssa.passes.*
import org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph.ConnectionGraphBuilder
import org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph.ConnectionGraphBuilderPass

private val ssaGenerationPhase = makeKonanModuleOpPhase(
        name = "IrToSsa",
        description = "Generate SSA IR from HIR",
        op = { context, irModuleFragment ->
            llvmContext = LLVMContextCreate()!!
            context.ssaModule = SSAModuleBuilder(context).build(irModuleFragment)
//            println(SSARender().render(context.ssaModule))
        }
)

private val ssaLoweringPhase = makeKonanModuleOpPhase(
        name = "SsaLowering",
        description = "Run lowering passes over SSA IR",
        op = { context, irModuleFragment ->
            val passes = listOf(
                    UnreachableBlockElimination(),
                    UnitReturnsLoweringPass(),
                    InlineAccessorsPass(),
                    ConnectionGraphBuilderPass(context.functionToEscapeAnalysisResult),
                    ReferenceSlotBuilder(context.functionToSlots, context.functionToEscapeAnalysisResult)
            )
            passes.forEach { pass ->
                context.ssaModule.functions.forEach {
                    when (pass.applyChecked(it)) {
                        ValidationResult.Error -> {
                            println(SSARender().render(it))
                        }
                    }
                }
            }
        }
)

private val llvmFromSsaPhase = makeKonanModuleOpPhase(
        name = "SsaToLlvm",
        description = "Generate LLVM IR from SSA IR",
        op = { context, irModuleFragment ->
            LLVMModuleFromSSA(context, context.ssaModule).generate()
        }
)

internal val ssaPhase = namedIrModulePhase(
        name = "SSA",
        description = "SSA",
        lower = ssaGenerationPhase then
                ssaLoweringPhase
)