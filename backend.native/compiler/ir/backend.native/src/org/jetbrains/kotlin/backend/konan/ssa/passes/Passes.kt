package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.SSAType

interface FunctionPass {
    fun apply(function: SSAFunction)
}