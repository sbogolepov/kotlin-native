package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction

interface FunctionPass {
    fun apply(function: SSAFunction)
}