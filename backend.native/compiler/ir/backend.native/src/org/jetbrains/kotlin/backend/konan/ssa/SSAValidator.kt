package org.jetbrains.kotlin.backend.konan.ssa

sealed class ValidationResult {
    object Ok : ValidationResult()
    object Error : ValidationResult()
}

fun validate(module: SSAModule): ValidationResult {
    module.functions.forEach {
        if (validateFunction(it) == ValidationResult.Error) {
            return ValidationResult.Error
        }
    }
    return ValidationResult.Ok
}

fun validateFunction(fn: SSAFunction): ValidationResult {
    var hasError = false
    fn.blocks.forEach {
        if (!it.sealed) {
            println("block ${it.id} is not sealed")
            hasError = true
        }
        if (it.body.isNotEmpty() && !it.body.last().isTerminal()) {
            println("block ${it.id} is not ending with terminal instruction")
            hasError = true
        }
        it.body.forEachIndexed { index, insn ->
            if (insn.isTerminal() && index != it.body.lastIndex) {
                println("$insn is a terminal but it is not the last insn in block.")
                hasError = true
            }
            for (operand in insn.operands) {
                if (insn !in operand.users) {
                    println("$insn is not a user of it's operand $operand")
                    hasError = true
                }
                if (operand is SSAInstruction && operand.owner !in fn.blocks) {
                    println("$operand: instruction's owner doesn't belong to function blocks.")
                    hasError = true
                }
            }
        }
    }
    return if (hasError) ValidationResult.Error else ValidationResult.Ok
}
