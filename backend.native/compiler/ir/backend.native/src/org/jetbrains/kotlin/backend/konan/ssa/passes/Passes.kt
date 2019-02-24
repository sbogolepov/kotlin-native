package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.ValidationResult
import org.jetbrains.kotlin.backend.konan.ssa.validateFunction

interface FunctionPass {
    val name: String

    fun apply(function: SSAFunction)

    fun applyChecked(function: SSAFunction): ValidationResult {
        apply(function)
        return validateFunction(function)
    }
}