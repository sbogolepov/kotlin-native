package org.jetbrains.kotlin.backend.konan.ssa

import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.konan.ir.constructedClass
import org.jetbrains.kotlin.backend.konan.ir.isOverridable
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.getArguments
import org.jetbrains.kotlin.ir.util.isInterface


private const val DEBUG = true

private fun debug(message: String) {
    if (DEBUG) println(message)
}

class SSABlockIdGenerator {
    fun next(suffix: String): SSABlockId {
        return SSABlockId.Simple(suffix)
    }
}

// TODO: Pass context about class here?
internal class SSAFunctionBuilderImpl(
        override val function: SSAFunction,
        val module: SSAModule,
        override val typeMapper: SSATypeMapper) : SSAFunctionBuilder {

    override var generationContext: GenerationContext<*> =
            GenerationContext.Function(this, null)

    private val declMapper = SSADeclarationsMapper(module, typeMapper)

    var blockIdGen = SSABlockIdGenerator()

    override var curBlock = function.entry

    override fun SSABlock.addParam(type: SSAType): SSABlockParam =
            SSABlockParam(type, this).also { this.params.add(it) }

    // SSA construction-related
    private val construct = object {
        val currentDef = mutableMapOf<IrValueDeclaration, MutableMap<SSABlock, SSAValue>>()
        val incompleteParams = mutableMapOf<SSABlock, MutableMap<IrValueDeclaration, SSABlockParam>>()

        fun dumpTable() {
            for ((def, map) in currentDef) {
                println("--- ${def.name.asString()} --- $def")
                for ((k, v) in map) {
                    println("${k.id} $v")
                }
            }
        }

        fun writeVariable(block: SSABlock, variable: IrValueDeclaration, value: SSAValue) {
            val blocks = currentDef.getOrPut(variable) { mutableMapOf() }
            blocks[block] = value
        }

        fun readVariable(block: SSABlock, variable: IrValueDeclaration): SSAValue =
                if (block in currentDef.getOrPut(variable) { mutableMapOf() }) {
                    currentDef[variable]!![block]!!
                } else {
                    readRecursiveVariable(block, variable)
                }

        private fun readRecursiveVariable(block: SSABlock, variable: IrValueDeclaration): SSAValue {
            val value: SSAValue = when {
                !block.sealed -> {
                    val param = block.addParam(typeMapper.map(variable.type))
//                    debug("Add param for ${variable.name}")
                    incompleteParams.getOrPut(block) { mutableMapOf() }[variable] = param
                    param
                }
                block.preds.size == 1 -> {
                    readVariable(block.preds.first().from, variable)
                }
                // Break potential cycles with operandless phi val
                else -> {
                    val param = block.addParam(typeMapper.map(variable.type))
                    writeVariable(block, variable, param)
                    addParamValues(variable, param)
                }
            }
            writeVariable(block, variable, value)
            return value
        }

        private fun addParamValues(variable: IrValueDeclaration, param: SSABlockParam): SSAValue {
            for (edge in param.owner.preds) {
                edge.args.add(readVariable(edge.from, variable))
            }
            return tryRemoveTrivialParam(param)
        }

        private fun tryRemoveTrivialParam(param: SSABlockParam): SSAValue {
            return param
//            val index = param.owner.params.indexOf(param)
//            val values = param.owner.preds.map { edge -> edge.args[index] }
//
//            var same: SSAValue? = null
//
//            for (op in values) {
//                if (op === same || op === param) {
//                    continue // unique value or self reference
//                }
//                if (same != null) {
//                    return param // phi merges at least 2 values -> not trivial
//                }
//                same = op
//            }
//            if (same == null) {
//                same = SSAConstant.Undef // phi is unreachable or in the entry block
//            }
//            param.users.remove(param)
//            val users = param.users
//            param.replaceBy(same)
//            users.filterIsInstance<SSABlockParam>().forEach { tryRemoveTrivialParam(it) }
//            return same
        }
    }

    override fun createBlock(name: String): SSABlock =
            SSABlock(function, blockIdGen.next(name))

    override fun createBlock(id: SSABlockId): SSABlock =
            SSABlock(function, id)

    override fun addBlock(ssaBlock: SSABlock) {
        ssaBlock.add()
    }

    private fun IrType.map() = typeMapper.map(this)

    fun build(irFunction: IrFunction): SSAFunction {

        // TODO: make parameters explicit parameters of the entry block
        irFunction.dispatchReceiverParameter?.let {
            // TODO: Reflect in owner type somehow
            val receiver = SSAReceiver(it.type.map())
            function.dispatchReceiver = receiver
            construct.writeVariable(function.entry, it, receiver)
        }

        irFunction.extensionReceiverParameter?.let {
            // TODO: Reflect in owner type somehow
            val receiver = SSAReceiver(it.type.map())
            function.extensionReceiver = receiver
            construct.writeVariable(function.entry, it, receiver)
        }

        irFunction.valueParameters.forEach {
            val param = SSAFuncArgument(it.name.asString(), it.type.map())
            function.params.add(param)
            construct.writeVariable(function.entry, it, param)
        }
        seal(function.entry)
        irFunction.body?.let { generateBody(it) }
        return function
    }

    override fun seal(block: SSABlock) {
        block.sealed = true
        // We need to populate missing block arguments.
        if (block !in construct.incompleteParams) return
        val incompleteParams = construct.incompleteParams.getValue(block)
        val incomingEdges = block.preds
        for ((variable, param) in incompleteParams) {
            val index = block.params.indexOf(param)
            for (edge in incomingEdges) {
                val arg = construct.readVariable(edge.from, variable)
                try {
                    edge.args.add(index, arg)
                } catch (e: IndexOutOfBoundsException) {
                    println(SSARender().render(function))
                    throw e
                }
            }
        }
        construct.incompleteParams.remove(block)
    }

    private fun generateBody(irBody: IrBody) = when (irBody) {
        is IrBlockBody -> {
            for (stmt in irBody.statements) {
                generateStatement(stmt)
            }
        }
        is IrExpressionBody -> TODO("$irBody")
        is IrSyntheticBody -> TODO("$irBody")
        else -> TODO()
    }

    private fun generateStatement(irStmt: IrStatement) {
        when (irStmt) {
            is IrExpression -> evalExpression(irStmt)
            is IrVariable -> generateVariable(irStmt)
            else -> TODO("$irStmt")
        }
    }

    private fun generateVariable(irVariable: IrVariable) {
        val value = irVariable.initializer?.let { evalExpression(it) }
        // TODO: explain
                ?: SSAConstant.Undef
        val declaration = +SSADeclare(irVariable.name.identifier, value, curBlock)
        construct.writeVariable(curBlock, irVariable, declaration)
    }

    override fun evalExpression(irExpr: IrExpression): SSAValue = when (irExpr) {
        is IrTypeOperatorCall -> evalTypeOperatorCall(irExpr)
        is IrCall -> evalCall(irExpr)
        is IrDelegatingConstructorCall -> evalDelegatingConstructorCall(irExpr)
        is IrGetValue -> evalGetValue(irExpr)
        is IrConst<*> -> evalConstant(irExpr)
        is IrReturn -> evalReturn(irExpr)
        is IrGetObjectValue -> evalGetObjectValue(irExpr)
        is IrWhileLoop -> evalWhileLoop(irExpr)
        is IrWhen -> evalWhen(irExpr)
        is IrSetVariable -> evalSetVariable(irExpr)
        is IrReturnableBlock     -> evalReturnableBlock(irExpr)
        is IrContainerExpression -> evalContainerExpression(irExpr)
        is IrGetField -> evalGetField(irExpr)
        is IrSetField -> evalSetField(irExpr)
        is IrTry -> evalTry(irExpr)
        is IrThrow -> evalThrow(irExpr)
        is IrBreak -> evalBreak(irExpr)
        is IrVararg -> evalVararg(irExpr)
        is IrContinue -> evalContinue(irExpr)
        else -> TODO("$irExpr")
    }

    private fun evalReturnableBlock(block: IrReturnableBlock): SSAValue {
        if (block.statements.isEmpty()) {
            return getNothing()
        }
        return generationContext.inReturnableBlock(block) {
            block.statements.forEach {
                generateStatement(it)
            }
        }
    }

    private fun evalTypeOperatorCall(irExpr: IrTypeOperatorCall): SSAValue =
            when (irExpr.operator) {
                IrTypeOperator.CAST -> evaluateCast(irExpr)
                IrTypeOperator.IMPLICIT_INTEGER_COERCION -> evaluateIntegerCoercion(irExpr)
                IrTypeOperator.IMPLICIT_CAST -> evalExpression(irExpr.argument)
                IrTypeOperator.IMPLICIT_NOTNULL -> TODO(ir2string(irExpr))
                IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> {
                    evalExpression(irExpr.argument)
                    getUnit()
                }
                IrTypeOperator.SAFE_CAST -> throw IllegalStateException("safe cast wasn't lowered")
                IrTypeOperator.INSTANCEOF -> evaluateInstanceOf(irExpr)
                IrTypeOperator.NOT_INSTANCEOF -> evaluateNotInstanceOf(irExpr)
                IrTypeOperator.SAM_CONVERSION -> TODO(ir2string(irExpr))
            }

    private fun evaluateCast(irExpr: IrTypeOperatorCall): SSAValue =
            +SSACast(evalExpression(irExpr.argument), typeMapper.map(irExpr.typeOperand), curBlock)

    private fun evaluateIntegerCoercion(irExpr: IrTypeOperatorCall): SSAValue =
            +SSAIntegerCoercion(evalExpression(irExpr.argument), typeMapper.map(irExpr.typeOperand), curBlock)

    private fun evaluateInstanceOf(irExpr: IrTypeOperatorCall): SSAValue =
            +SSAInstanceOf(evalExpression(irExpr.argument), typeMapper.map(irExpr.typeOperand), curBlock)

    private fun evaluateNotInstanceOf(irExpr: IrTypeOperatorCall): SSAValue {
        val instanceOf = evaluateInstanceOf(irExpr)
        return +SSANot(instanceOf, curBlock)
    }

    private fun evalSetField(irExpr: IrSetField): SSAValue {
        val field = SSAField(irExpr.symbol.owner.name.asString(), typeMapper.map(irExpr.type))
        if (irExpr.receiver == null) {
            val value = evalExpression(irExpr.value)
            return +SSASetGlobal(field, value, curBlock)
        } else {
            val receiver = evalExpression(irExpr.receiver!!)
            val value = evalExpression(irExpr.value)
            return +SSASetField(receiver, field, value, curBlock)
        }
    }

    private fun evalGetField(irExpr: IrGetField): SSAValue {
        // Static variables has no receiver
        val field = SSAField(irExpr.symbol.owner.name.asString(), typeMapper.map(irExpr.type))
        return if (irExpr.receiver == null) {
            +SSAGetGlobal(field, curBlock)
        } else {
            val receiver = evalExpression(irExpr.receiver!!)
            +SSAGetField(receiver, field, curBlock)
        }
    }

    private fun evalDelegatingConstructorCall(irCall: IrDelegatingConstructorCall): SSAValue {
        return +SSANOP("Delegating constructor", curBlock)
    }

    private fun evalWhen(irWhen: IrWhen): SSAValue =
            generationContext.inWhen(irWhen) {
                irWhen.branches.forEach {
                    generateWhenCase(it, it == irWhen.branches.last())
                }
            }

    override fun getUnit(): SSAValue = SSAConstant.Undef

    override fun getNothing(): SSAValue = SSAConstant.Undef

    private fun evalSetVariable(setVariable: IrSetVariable): SSAValue {
        val value = evalExpression(setVariable.value)
        construct.writeVariable(curBlock, setVariable.symbol.owner, value)
        return getUnit()
    }

    private fun evalContainerExpression(containerExpr: IrContainerExpression): SSAValue {
        containerExpr.statements.dropLast(1).forEach { generateStatement(it) }
        containerExpr.statements.lastOrNull()?.let {
            if (it is IrExpression) {
                return evalExpression(it)
            } else {
                generateStatement(it)
            }
        }
        return getUnit()
    }

    private fun evalWhileLoop(irLoop: IrWhileLoop): SSAValue =
        generationContext.inLoop(irLoop) {
            val loopBody = createBlock("loop_body").apply {
                addBlock(this)
            }

            addBr(loopEntry)

            curBlock = loopEntry
            val condition = evalExpression(irLoop.condition)
            addCondBr(condition, loopBody, loopExit)

            curBlock = loopBody
            seal(loopBody)
            irLoop.body?.let { generateStatement(it) }
            addBr(loopEntry)
            seal(loopEntry)

            curBlock = loopExit
            seal(loopExit)
        }

    override fun addBr(to: SSABlock): SSABr =
            +SSABr(SSAEdge(curBlock, to), curBlock)

    override fun addCondBr(cond: SSAValue, tru: SSABlock, fls: SSABlock) {
        val truEdge = SSAEdge(curBlock, tru)
        val flsEdge = SSAEdge(curBlock, fls)
        +SSACondBr(cond, truEdge, flsEdge, curBlock)
    }

    private fun evalGetObjectValue(irExpr: IrGetObjectValue): SSAValue {
        return +SSAGetObjectValue(typeMapper.map(irExpr.type), curBlock)
    }

    private fun evalReturn(irReturn: IrReturn): SSAValue {
        val retVal = evalExpression(irReturn.value)
        val target = irReturn.returnTargetSymbol.owner
        return generationContext.getReturn().emitReturn(retVal, target)
    }

    private fun evalConstant(irConst: IrConst<*>): SSAConstant = when (irConst.kind) {
        IrConstKind.Null -> SSAConstant.Null
        IrConstKind.Boolean -> SSAConstant.Bool(irConst.value as Boolean)
        IrConstKind.Char -> SSAConstant.Char(irConst.value as Char)
        IrConstKind.Byte -> SSAConstant.Byte(irConst.value as Byte)
        IrConstKind.Short -> SSAConstant.Short(irConst.value as Short)
        IrConstKind.Int -> SSAConstant.Int(irConst.value as Int)
        IrConstKind.Long -> SSAConstant.Long(irConst.value as Long)
        IrConstKind.String -> SSAConstant.String(irConst.value as String)
        IrConstKind.Float -> SSAConstant.Float(irConst.value as Float)
        IrConstKind.Double -> SSAConstant.Double(irConst.value as Double)
    }

    private fun evalGetValue(irGetValue: IrGetValue): SSAValue {
        val declaration = irGetValue.symbol.owner
        return if (declaration in construct.currentDef) {
            construct.readVariable(curBlock, declaration)
        } else {
            println("Failed to find ${declaration.name.asString()} --- $declaration")
            construct.dumpTable()
            TODO("decide how to work with `this` in methods")
        }
    }

    private operator fun <T : SSAInstruction> T.unaryPlus(): T = this.add()

    override fun <T : SSAInstruction> T.add(): T {
        // Dirty hack against ugly state of incoming IR.
        if (curBlock.body.lastOrNull()?.isTerminal() == true) {
            curBlock = createBlock("unreachable").apply {
//                addBlock(this)
                sealed = true
            }
        }
        curBlock.body += this
        return this
    }

    fun SSABlock.add(): SSABlock {
        owner.blocks += this
        return this
    }

    private fun evalCall(irCall: IrCall): SSAValue {
        val function = irCall.symbol.owner
        if (function is IrConstructor) return generateConstructorCall(irCall)

        val args = (irCall.getArguments()).map { (_, paramExpr) ->
            evalExpression(paramExpr)
        }

        val callee = declMapper.mapFunction(irCall.symbol.owner)

        val dispatchReceiver = irCall.dispatchReceiver
        return if (dispatchReceiver != null) {
            when {
                dispatchReceiver.type.isInterface() -> +SSAInterfaceCall(args, callee, curBlock, irCall)
                function is IrSimpleFunction && function.isOverridable -> +SSAVirtualCall(args, callee, curBlock, irCall)
                else -> +SSADirectCall(args[0], args.drop(1), callee, curBlock, irCall)
            }
        } else {
            +SSADirectCall(SSAGlobalReceiver, args, callee, curBlock, irCall)
        }
    }

    private fun generateConstructorCall(irCall: IrCall): SSAValue {
        val constructor = irCall.symbol.owner as IrConstructor
        val irClass = (irCall.symbol as IrConstructorSymbol).owner.constructedClass

        val ssaClass = typeMapper.mapClass(irClass)
        val allocationSite = +SSAAlloc(ssaClass, curBlock)

        val args = (irCall.getArguments()).map { (_, paramExpr) ->
            evalExpression(paramExpr)
        }

        val callee = declMapper.mapFunction(constructor)
        +SSADirectCall(allocationSite, args, callee, curBlock, irCall)
        return allocationSite
    }

    private fun evalTry(irTry: IrTry): SSAValue {
        return generationContext.inTryCatch(irTry) {
            evalExpression(irTry.tryResult)
        }
    }

    private fun evalThrow(irThrow: IrThrow): SSAValue {
        val value = evalExpression(irThrow.value)
        generationContext.getTryCatch().emitThrow(value)
        return SSAConstant.Undef
    }

    private fun evalBreak(irBreak: IrBreak): SSAValue {
        generationContext.getLoop().emitBreak(irBreak)
        return SSAConstant.Unit
    }

    private fun evalContinue(irContinue: IrContinue): SSAValue {
        generationContext.getLoop().emitContinue(irContinue)
        return SSAConstant.Unit
    }

    private fun evalVararg(irVararg: IrVararg): SSAValue {
        val values = irVararg.elements.forEach {
            if (it is IrExpression) evalExpression(it)
        }
        return SSAConstant.Undef
    }
}