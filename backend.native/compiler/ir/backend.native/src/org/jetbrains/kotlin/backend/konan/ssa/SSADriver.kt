package org.jetbrains.kotlin.backend.konan.ssa

import llvm.DICreateBuilder
import llvm.LLVMModuleCreateWithName
import org.jetbrains.kotlin.backend.common.phaser.namedIrModulePhase
import org.jetbrains.kotlin.backend.common.phaser.then
import org.jetbrains.kotlin.backend.konan.llvm.createLlvmDeclarations
import org.jetbrains.kotlin.backend.konan.makeKonanModuleOpPhase
import org.jetbrains.kotlin.backend.konan.ssa.llvm.LLVMModuleFromSSA
import org.jetbrains.kotlin.backend.konan.ssa.passes.CallsLoweringPass
import org.jetbrains.kotlin.backend.konan.ssa.passes.InlineAccessorsPass
import org.jetbrains.kotlin.backend.konan.ssa.passes.UnitReturnsLoweringPass

private val ssaGenerationPhase = makeKonanModuleOpPhase(
        name = "IrToSsa",
        description = "Generate SSA IR from HIR",
        op = { context, irModuleFragment ->
            context.ssaModule = SSAModuleBuilder().build(irModuleFragment)
        }
)

private val ssaLoweringPhase = makeKonanModuleOpPhase(
        name = "SsaLowering",
        description = "Run lowering passes over SSA IR",
        op = { context, irModuleFragment ->
            val passes = listOf(
                    UnitReturnsLoweringPass(),
                    InlineAccessorsPass(),
                    CallsLoweringPass()
            )
            passes.forEach { pass ->
                context.ssaModule.functions.forEach {
                    when (pass.applyChecked(it)) {
                        ValidationResult.Error -> {
                            println(SSARender().render(it))
                            error("Error validating function ${it.name}")
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
            val llvmModule = LLVMModuleCreateWithName("out")!! // TODO: dispose
            context.llvmModule = llvmModule
            context.debugInfo.builder = DICreateBuilder(llvmModule)
            context.llvmDeclarations = createLlvmDeclarations(context)
            context.lifetimes = mutableMapOf()
            LLVMModuleFromSSA(context, context.ssaModule).generate()
        }
)

internal val ssaPhase = namedIrModulePhase(
        name = "SSA",
        description = "SSA",
        lower = ssaGenerationPhase then
                ssaLoweringPhase then
                llvmFromSsaPhase
)