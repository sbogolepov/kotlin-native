package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.verifyModule
import org.jetbrains.kotlin.backend.konan.ssa.llvm.LLVMModuleFromSSA
import org.jetbrains.kotlin.backend.konan.ssa.passes.CallsLoweringPass
import org.jetbrains.kotlin.backend.konan.ssa.passes.InlineAccessorsPass
import org.jetbrains.kotlin.backend.konan.ssa.passes.UnitReturnsLoweringPass

internal class SSADriver(val context: Context) {
    fun exec() {
        val ssaModule = SSAModuleBuilder().build(context.ir.irModule)
        validate(ssaModule)
        println("--- BEFORE ALL PASSES ---")
        println(SSARender().render(ssaModule))
        ssaModule.functions.forEach { UnitReturnsLoweringPass().apply(it) }
        println("--- AFTER UNIT LOWERING ---")
        println(SSARender().render(ssaModule))
        ssaModule.functions.forEach { InlineAccessorsPass().apply(it) }
        println("--- AFTER TRIVIAL INLINE ---")
        println(SSARender().render(ssaModule))
        ssaModule.functions.forEach { CallsLoweringPass().apply(it) }
        println("--- AFTER CALLS LOWERING ---")
        println(SSARender().render(ssaModule))
        val llvmFromSsa = LLVMModuleFromSSA(context, ssaModule).generate()
        verifyModule(llvmFromSsa)
    }
}