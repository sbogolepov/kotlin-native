package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.SSAGetObjectValue
import org.jetbrains.kotlin.backend.konan.ssa.SSAReturn
import org.jetbrains.kotlin.backend.konan.ssa.validateFunction
import org.jetbrains.kotlin.ir.types.isUnit

// TODO: Replace
//  val x: Unit = f()
// with
//  f()
//  val x: Unit = getUnit()
class UnitReturnsLoweringPass : FunctionPass {

    override val name: String = "Unit returns lowering"

    override fun apply(function: SSAFunction) {
        if (!function.isUnit()) {
            return
        }
        for (block in function.blocks) {
            for (insn in block.body) {
                if (insn is SSAGetObjectValue && insn.users.size == 1) {
                    val user = insn.users.single()
                    if (user is SSAReturn) {
                        block.body -= insn
                        user.owner.body -= user
                        user.owner.body += SSAReturn(null, user.owner)
                    }
                }
            }
        }
    }

    private fun SSAFunction.isUnit(): Boolean {
        irOrigin?.let {
            return it.returnType.isUnit()
        }
        for (block in blocks) {
            for (insn in block.body) {
                if (insn is SSAGetObjectValue && insn.users.size == 1) {
                    val user = insn.users.single()
                    if (user is SSAReturn) {
                        return true
                    }
                }
            }
        }
        return false
    }
}