package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.expressions.IrBreak
import org.jetbrains.kotlin.ir.expressions.IrContinue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrTry

interface SSAFunctionBuilder {

    var generationContext: GenerationContext

    var curBlock: SSABlock

    fun addBr(to: SSABlock): SSABr

    fun addBlock(name: String): SSABlock

    fun addBlock(id: SSABlockId): SSABlock

    fun <T: SSAInstruction> T.add(): T
}


interface LoopContext {
    fun emitBreak(irBreak: IrBreak)

    fun emitContinue(irContinue: IrContinue)
}

interface TryCatchContext {
    fun emitThrow(value: SSAValue)
}

sealed class GenerationContext(
        val builder: SSAFunctionBuilder,
        val parent: GenerationContext?
) {
    class Loop(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?,
            val loop: IrLoop
    ) : GenerationContext(builder, parent), LoopContext {

        val loopEntry = builder.addBlock("loop_entry")

        val loopExit = builder.addBlock("loop_exit")

        override fun emitBreak(irBreak: IrBreak) {
            if (irBreak.loop == loop) {
                builder.addBr(loopExit)
            } else {
                parent.getLoop().emitBreak(irBreak)
            }
        }

        override fun emitContinue(irContinue: IrContinue) {
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
    ) : GenerationContext(builder, parent), TryCatchContext {
        override fun emitThrow(value: SSAValue) {
        }
    }

    class Function(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?
    ) : GenerationContext(builder, parent), TryCatchContext {

        private val landingPad: SSABlock by lazy {
            builder.addBlock(SSABlockId.LandingPad)
        }

        override fun emitThrow(value: SSAValue) {
            with(builder) {
                val edge = SSAEdge(builder.curBlock, landingPad, mutableListOf(value))
                SSAThrow(edge, builder.curBlock).add()
            }
        }
    }
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

fun GenerationContext?.getLoop(): LoopContext = when (this) {
    is LoopContext -> this
    null -> error("Cannot find parent loop")
    else -> parent.getLoop()
}

fun GenerationContext?.getTryCatch(): TryCatchContext = when (this) {
    is TryCatchContext -> this
    null -> error("Cannot find parent try-catch")
    else -> parent.getTryCatch()
}





