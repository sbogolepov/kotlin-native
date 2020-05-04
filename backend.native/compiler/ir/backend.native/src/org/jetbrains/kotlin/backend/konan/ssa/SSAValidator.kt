package org.jetbrains.kotlin.backend.konan.ssa

sealed class ValidationResult {
    object Ok : ValidationResult()
    class Error(val errors: List<String> = emptyList()) : ValidationResult()
}

fun validateFunction(fn: SSAFunction): ValidationResult {
    val errors = mutableListOf<String>()
    fn.blocks.firstOrNull()?.let { entry ->
        if (entry.id != SSABlockId.Entry) {
            errors += "Entry block is not marked as entry!"
        }
    }
    fn.blocks.forEach {
        for (pred in it.preds) {
            if (pred.args.size != it.params.size) {
                errors += "Edge from ${pred.from.id} to ${pred.to.id} has ${pred.args.size} args instead of ${it.params.size}"
            }
        }
        if (!it.sealed) {
            errors += "block ${it.id} is not sealed"
        }
        if (it.body.isNotEmpty() && !it.body.last().isTerminal()) {
            errors += "block ${it.id} is not ending with terminal instruction"
        }
        it.body.forEachIndexed { index, insn ->
            if (insn.isTerminal() && index != it.body.lastIndex) {
                errors += "$insn is a terminal but it is not the last insn in block."
            }
            for (operand in insn.operands) {
                if (insn !in operand.users) {
                    errors += "$insn is not a user of it's operand $operand"
                }
                if (operand is SSAInstruction && operand.owner !in fn.blocks) {
                    errors += "$insn"
                    errors += "$operand: instruction's owner doesn't belong to function blocks."
                }
            }
        }
    }
    return if (errors.isNotEmpty()) ValidationResult.Error(errors) else ValidationResult.Ok
}
