package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrTry

sealed class GenerationContext(
        val builder: SSAFunctionBuilder,
        val parent: GenerationContext?
) {
    class Loop(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?,
            val loop: IrLoop
    ) : GenerationContext(builder, parent) {

        val loopEntry = builder.addBlock("loop_entry")

        val loopExit = builder.addBlock("loop_exit")

        fun emitBreak(irBreak: IrBreak) {
            if (irBreak.loop == loop) {
                builder.addBr(loopExit)
            } else {
                parent.getLoop().emitBreak(irBreak)
            }
        }

        fun emitContinue(irContinue: IrContinue) {
            if (irContinue.loop == loop) {
                builder.addBr(loopEntry)
            } else {
                parent.getLoop().emitContinue(irContinue)
            }
        }
    }

    class TryCatch(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?,
            val irTry: IrTry
    ) : GenerationContext(builder, parent) {
        fun emitThrow(value: SSAValue) {

        }
    }

    class Function(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?
    ) : GenerationContext(builder, parent)
}

fun GenerationContext.inLoop(irLoop: IrLoop, action: GenerationContext.Loop.() -> Unit) {
    val loop = GenerationContext.Loop(builder, this, irLoop)
    val old = builder.generationContext
    builder.generationContext = loop
    try {
        loop.action()
    } finally {
        builder.generationContext = old
    }
}

inline fun <T> GenerationContext.inTryCatch(irTry: IrTry, action: GenerationContext.TryCatch.() -> T): T {
    val loop = GenerationContext.TryCatch(builder, this, irTry)
    val old = builder.generationContext
    builder.generationContext = loop
    try {
        return loop.action()
    } finally {
        builder.generationContext = old
    }
}

fun GenerationContext?.getLoop(): GenerationContext.Loop = when (this) {
    is GenerationContext.Loop -> this
    null -> error("Cannot find parent loop")
    else -> parent.getLoop()
}

fun GenerationContext?.getTryCatch(): GenerationContext.TryCatch = when (this) {
    is GenerationContext.TryCatch -> this
    null -> error("Cannot find parent try-catch")
    else -> parent.getTryCatch()
}





