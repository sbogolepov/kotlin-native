package org.jetbrains.kotlin.backend.konan.ssa

import llvm.LLVMDumpModule
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.verifyModule
import org.jetbrains.kotlin.backend.konan.ssa.llvm.LLVMModuleFromSSA
import org.jetbrains.kotlin.backend.konan.ssa.passes.CallsLoweringPass
import org.jetbrains.kotlin.backend.konan.ssa.passes.InlineAccessorsPass
import org.jetbrains.kotlin.backend.konan.ssa.passes.UnitReturnsLoweringPass

internal class SSADriver(val context: Context) {

    private val passes = listOf(
            UnitReturnsLoweringPass(),
            InlineAccessorsPass(),
            CallsLoweringPass()
    )

    fun exec() {
        val ssaModule = SSAModuleBuilder().build(context.ir.irModule)
        if (validate(ssaModule) == ValidationResult.Error) {
            return
        }

        println("--- BEFORE ALL PASSES ---")
        println(SSARender().render(ssaModule))
        for (pass in passes) {
            println("--- ${pass.name}")
            ssaModule.functions.forEach {
                when (pass.applyChecked(it)) {
                    ValidationResult.Error -> {
                        println("Error validating function ${it.name}")
                        return
                    }
                }
            }
            println(SSARender().render(ssaModule))
        }
        val llvmFromSsa = LLVMModuleFromSSA(context, ssaModule).generate()
        verifyModule(llvmFromSsa)
        LLVMDumpModule(llvmFromSsa)
    }
}