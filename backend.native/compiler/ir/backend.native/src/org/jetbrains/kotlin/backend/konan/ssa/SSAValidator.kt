package org.jetbrains.kotlin.backend.konan.ssa

fun validate(module: SSAModule) {
    module.functions.forEach {
        validateFunction(it)
    }
}

private fun validateFunction(fn: SSAFunction) {
    println("Validating function ${fn.name}")
    fn.blocks.forEach {
        if (!it.sealed) {
            println("block ${it.id} is not sealed")
        }
        if (it.body.isNotEmpty() && !it.body.last().isTerminal()) {
            println("block ${it.id} is not ending with terminal instruction")
        }
        for (insn in it.body) {
            if (insn is SSAPhi) {
                if (insn.operands.isEmpty()) {
                    println("block ${it.id} has operandless phi")
                }
            }
        }
    }
    println()
}
