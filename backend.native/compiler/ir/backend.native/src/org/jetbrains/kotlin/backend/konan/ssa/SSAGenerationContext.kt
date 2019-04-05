package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.ir.declarations.IrReturnTarget
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.isUnit

interface LoopContext {
    fun emitBreak(irBreak: IrBreak)

    fun emitContinue(irContinue: IrContinue)
}

interface TryCatchContext {
    fun emitThrow(value: SSAValue)
}

interface ReturnContext {
    fun emitReturn(value: SSAValue, target: IrReturnTarget): SSAValue
}

sealed class GenerationContext(
        val builder: SSAFunctionBuilder,
        val parent: GenerationContext?
) {
    open fun complete() {}

    class Loop(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?,
            val loop: IrLoop
    ) : GenerationContext(builder, parent), LoopContext {

        val loopEntry = builder.createBlock("loop_entry").apply {
            builder.addBlock(this)
        }

        val loopExit = builder.createBlock("loop_exit").apply {
            builder.addBlock(this)
        }

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

        private lateinit var _returnValue: SSAValue

        val returnValue: SSAValue
            get() = _returnValue

        val continuation: SSABlock by lazy {
            with(builder) {
                createBlock("continuation").apply {
                    _returnValue = addParam(typeMapper.map(irTry.tryResult.type))
                    addBlock(this)
                }
            }
        }

        override fun emitThrow(value: SSAValue) {
            // TODO: add support for catches
            parent.getTryCatch().emitThrow(value)
        }

        override fun complete() {
            builder.curBlock = continuation
            builder.seal(continuation)
        }
    }

    class Function(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?
    ) : GenerationContext(builder, parent), TryCatchContext, ReturnContext {

        private val landingPad: SSABlock by lazy {
            builder.createBlock(SSABlockId.LandingPad).apply {
                builder.addBlock(this)
            }
        }

        override fun emitThrow(value: SSAValue) {
            with(builder) {
                val edge = SSAEdge(builder.curBlock, landingPad, mutableListOf(value))
                SSAThrow(edge, builder.curBlock).add()
            }
        }

        override fun emitReturn(value: SSAValue, target: IrReturnTarget) =
                with(builder) {
                    SSAReturn(value, curBlock).add()
                }
    }

    class When(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?,
            private val irWhen: IrWhen
    ) : GenerationContext(builder, parent) {

        private val exitBlock = builder.createBlock("when_exit")

        private val param: SSABlockParam by lazy {
            with(builder) {
                exitBlock.addParam(typeMapper.map(irWhen.type))
            }
        }

        private val isExpression = isUnconditional(irWhen.branches.last()) && !irWhen.type.isUnit()

        private fun isUnconditional(branch: IrBranch): Boolean =
                branch.condition is IrConst<*>                            // If branch condition is constant.
                        && (branch.condition as IrConst<*>).value as Boolean  // If condition is "true"

        private fun generateWhenCase(branch: IrBranch, isLast: Boolean) = with(builder) {
            val nextBlock = if (isLast) exitBlock else createBlock("when_cond")
            seal(curBlock)
            val result = if (isUnconditional(branch)) {
                evalExpression(branch.result)
            } else {
                val cond = evalExpression(branch.condition)
                val bodyBlock = createBlock("when_body").apply { addBlock(this) }
                addCondBr(cond, bodyBlock, nextBlock)
                curBlock = bodyBlock
                seal(curBlock)
                evalExpression(branch.result)
            }

            if (curBlock.body.isEmpty() || !curBlock.body.last().isTerminal()) {
                val br = addBr(exitBlock)
                if (isExpression) {
                    br.edge.args.add(result)
                }
            }
            if (nextBlock != exitBlock) {
                addBlock(nextBlock)
            }
            curBlock = nextBlock
        }

        fun generate(): SSAValue {
            irWhen.branches.forEach {
                generateWhenCase(it, it == irWhen.branches.last())
            }
            builder.addBlock(exitBlock)
            exitBlock.sealed = true
            return if (isExpression) {
                param
            } else {
                builder.getUnit()
            }
        }
    }

    class ReturnableBlock(
            builder: SSAFunctionBuilder,
            parent: GenerationContext?,
            private val irReturnableBlock: IrReturnableBlock
    ) : GenerationContext(builder, parent), ReturnContext {

        private lateinit var _returnValue: SSAValue

        val returnValue: SSAValue
            get() = _returnValue

        val exit: SSABlock by lazy {
            with(builder) {
                createBlock("exit_from_returnable").apply {
                    addBlock(this)
                    _returnValue = if (typeMapper.map(irReturnableBlock.type) != SpecialType) {
                        addParam(typeMapper.map(irReturnableBlock.type))
                    } else {
                        getUnit()
                    }
                }
            }
        }

        override fun emitReturn(value: SSAValue, target: IrReturnTarget): SSAValue =
                with(builder) {
                    if (target != irReturnableBlock) {
                        parent.getReturn().emitReturn(value, target)
                    } else {
                        addBr(exit).apply {
                            comment = "return in returnable block"
                            edge.args.add(0, value)
                        }

                        getNothing()
                    }
                }

        override fun complete() {
            if (builder.curBlock.body.lastOrNull()?.isTerminal() == false) {
                builder.addBr(exit)
            }
            builder.curBlock = exit
            builder.seal(exit)
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
        loop.complete()
        builder.generationContext = old
    }
}

inline fun GenerationContext.inTryCatch(irTry: IrTry, action: GenerationContext.TryCatch.() -> SSAValue): SSAValue {
    val tryCatch = GenerationContext.TryCatch(builder, this, irTry)
    val old = builder.generationContext
    builder.generationContext = tryCatch
    try {
        val result = tryCatch.action()
        builder.addBr(tryCatch.continuation).apply { comment = "return try value" }.edge.args.add(0, result)
        tryCatch.complete()
    } finally {
        builder.generationContext = old
    }
    return tryCatch.returnValue
}

inline fun <T> GenerationContext.inReturnableBlock(
        irReturnableBlock: IrReturnableBlock,
        action: GenerationContext.ReturnableBlock.() -> T
): SSAValue {
    val returnableBlock = GenerationContext.ReturnableBlock(builder, this, irReturnableBlock)
    val old = builder.generationContext
    builder.generationContext = returnableBlock
    try {
        returnableBlock.action()
        returnableBlock.complete()
    } finally {
        builder.generationContext = old
    }
    return returnableBlock.returnValue
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

fun GenerationContext?.getReturn(): ReturnContext = when (this) {
    is ReturnContext -> this
    null -> error("Cannot find parent try-catch")
    else -> parent.getReturn()
}





