package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.SSAModule
import org.jetbrains.kotlin.backend.konan.ssa.ValidationResult
import org.jetbrains.kotlin.backend.konan.ssa.validateFunction

interface FunctionPass {
    val name: String

    val description: String
        get() = ""

    fun apply(function: SSAFunction)

    fun applyChecked(function: SSAFunction): ValidationResult {
        apply(function)
        return validateFunction(function)
    }
}

// TODO: Immutable pass
interface ModulePass<T> {
    val name: String

    val description: String
        get() = ""

    fun apply(module: SSAModule): T
}