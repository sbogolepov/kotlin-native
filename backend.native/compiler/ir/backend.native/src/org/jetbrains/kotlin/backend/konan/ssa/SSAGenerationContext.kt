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

internal sealed class GenerationContext<T>(
        val builder: SSAFunctionBuilder,
        val parent: GenerationContext<*>?
) {
    abstract fun complete(result: T): SSAValue

    internal class Loop(
            builder: SSAFunctionBuilder,
            parent: GenerationContext<*>?,
            val loop: IrLoop
    ) : GenerationContext<Unit>(builder, parent), LoopContext {

        val loopCondition = builder.createBlock("loop_condition").apply {
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
                builder.addBr(loopCondition)
            } else {
                parent.getLoop().emitContinue(irContinue)
            }
        }

        override fun complete(result: Unit): SSAValue {
            return builder.getUnit()
        }
    }

    class TryCatch(
            builder: SSAFunctionBuilder,
            parent: GenerationContext<*>?,
            val irTry: IrTry
    ) : GenerationContext<Unit>(builder, parent), TryCatchContext {

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

        override fun complete(result: Unit): SSAValue {
            if (builder.curBlock.body.isEmpty() || !builder.curBlock.body.last().isTerminal()) {
                builder.addBr(continuation)
            }
            builder.curBlock = continuation
            builder.seal(continuation)
            return returnValue
        }
    }

    class Function(
            builder: SSAFunctionBuilder,
            parent: GenerationContext<*>?
    ) : GenerationContext<Unit>(builder, parent), TryCatchContext, ReturnContext {

        private val landingPad: SSABlock by lazy {
            builder.createBlock(SSABlockId.LandingPad).apply {
                builder.addBlock(this)
            }
        }

        override fun emitThrow(value: SSAValue) {
            with(builder) {
                val edge = SSAEdge(builder.curBlock, landingPad, mutableListOf(value))
                if (landingPad.params.isEmpty()) {
                    landingPad.addParam(value.type)
                }
                SSAThrow(edge, builder.curBlock).add()
            }
        }

        override fun emitReturn(value: SSAValue, target: IrReturnTarget) =
                with(builder) {
                    val returnBlock = function.returnBlock
                    if (returnBlock.params.isEmpty()) {
                        returnBlock.addParam(function.type.returnType)
                        returnBlock.body += SSAReturn(returnBlock.params[0], returnBlock)
                        returnBlock.sealed = true
                    }
                    builder.addBr(returnBlock).apply {
                        edge.args += value
                    }
                }

        override fun complete(result: Unit): SSAValue {
            builder.seal(landingPad)
            return SSAConstant.Undef
        }
    }

    class When(
            builder: SSAFunctionBuilder,
            parent: GenerationContext<*>?,
            private val irWhen: IrWhen
    ) : GenerationContext<Unit>(builder, parent) {

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

        fun generateWhenCase(branch: IrBranch, isLast: Boolean) = with(builder) {
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
        override fun complete(result: Unit): SSAValue {
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
            parent: GenerationContext<*>?,
            private val irReturnableBlock: IrReturnableBlock
    ) : GenerationContext<Unit>(builder, parent), ReturnContext {

        private lateinit var _returnValue: SSAValue

        private val returnValue: SSAValue
            get() = _returnValue

        private val exit: SSABlock by lazy {
            with(builder) {
                createBlock("exit_from_returnable").apply {
                    addBlock(this)
                    _returnValue = if (!irReturnableBlock.type.isUnit()) {
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
                            if (!irReturnableBlock.type.isUnit()) {
                                edge.args.add(0, value)
                            }
                        }
                        getNothing()
                    }
                }

        override fun complete(result: Unit): SSAValue {
            if (builder.curBlock.body.isEmpty() || !builder.curBlock.body.last().isTerminal()) {
                builder.addBr(exit)
            }
            builder.curBlock = exit
            builder.seal(exit)
            return returnValue
        }
    }
}

internal inline fun <G, T: GenerationContext<G>> GenerationContext<*>.inNewContext(newContext: T, action: T.() -> G): SSAValue {
    val old = builder.generationContext
    builder.generationContext = newContext

    val value = newContext.complete(newContext.action())
    builder.generationContext = old
    return value
}

internal inline fun GenerationContext<*>.inWhen(irWhen: IrWhen, action: GenerationContext.When.() -> Unit): SSAValue {
    val whenContext = GenerationContext.When(builder, this, irWhen)
    return inNewContext(whenContext, action)
}

internal inline fun GenerationContext<*>.inLoop(irLoop: IrLoop, action: GenerationContext.Loop.() -> Unit): SSAValue {
    val loop = GenerationContext.Loop(builder, this, irLoop)
    return inNewContext(loop, action)
}

internal inline fun GenerationContext<*>.inTryCatch(irTry: IrTry, action: GenerationContext.TryCatch.() -> Unit): SSAValue {
    val tryCatch = GenerationContext.TryCatch(builder, this, irTry)
    return inNewContext(tryCatch, action)
}

internal inline fun GenerationContext<*>.inReturnableBlock(
        irReturnableBlock: IrReturnableBlock,
        action: GenerationContext.ReturnableBlock.() -> Unit
): SSAValue {
    val returnableBlock = GenerationContext.ReturnableBlock(builder, this, irReturnableBlock)
    return inNewContext(returnableBlock, action)
}

internal fun GenerationContext<*>?.getLoop(): LoopContext = when (this) {
    is LoopContext -> this
    null -> error("Cannot find parent loop")
    else -> parent.getLoop()
}

internal fun GenerationContext<*>?.getTryCatch(): TryCatchContext = when (this) {
    is TryCatchContext -> this
    null -> error("Cannot find parent try-catch")
    else -> parent.getTryCatch()
}

internal fun GenerationContext<*>?.getReturn(): ReturnContext = when (this) {
    is ReturnContext -> this
    null -> error("Cannot find parent try-catch")
    else -> parent.getReturn()
}





