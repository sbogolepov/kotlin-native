package org.jetbrains.kotlin.backend.konan.ssa.llvm

import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.isComparisonFunction
import org.jetbrains.kotlin.backend.konan.descriptors.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.descriptors.resolveFakeOverride
import org.jetbrains.kotlin.backend.konan.ir.isAny
import org.jetbrains.kotlin.backend.konan.ir.isUnit
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.int16Type
import org.jetbrains.kotlin.backend.konan.llvm.int1Type
import org.jetbrains.kotlin.backend.konan.llvm.int8Type
import org.jetbrains.kotlin.backend.konan.llvm.kNullObjHeaderPtr
import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.backend.konan.ssa.passes.connection_graph.EscapeState
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.parentAsClass

fun findSsaFunction(ssaModule: SSAModule, function: IrFunction): SSAFunction? =
    ssaModule.functions.firstOrNull { it.irOrigin == function }

internal class LLVMModuleFromSSA(val context: Context, val ssaModule: SSAModule) {

    private val llvmModule = context.llvmModule!!
    private val target = context.config.target
    private val runtimeFile = context.config.distribution.runtime(target)
    private val runtime = Runtime(runtimeFile)

    fun generate() {
        LLVMSetDataLayout(llvmModule, runtime.dataLayout)
        LLVMSetTarget(llvmModule, runtime.target)

//        for (function in ssaModule.functions) {
//            LLVMFunctionFromSSA(context, function, llvmDeclarations, typeMapper).generate()
//        }
    }
}

internal class LLVMFunctionFromSSA(
        val context: Context,
        val ssaFunc: SSAFunction,
        val llvmDeclarations: LlvmDeclarations,
        val typeMapper: LLVMTypeMapper) {

    private val llvmFunc = llvmDeclarations.forFunction(ssaFunc.irOrigin!!).llvmFunction

    private val paramIndex = ssaFunc.params.mapIndexed { index, argument -> argument to index }.toMap()

    private val codegen = LLVMCodeGenerator(context, llvmFunc)

    val constTrue = LLVMConstInt(int1Type, 1, 1)!!

    private val slots = context.functionToSlots.getValue(this.ssaFunc)
    private val escapeResults = context.functionToEscapeAnalysisResult[ssaFunc]

    private lateinit var llvmSlots: LLVMValueRef

    private val intrinsicGenerator = IntrinsicGenerator(object : IntrinsicGeneratorEnvironment {
        override val codegen = this@LLVMFunctionFromSSA.codegen
    })

    private fun SSAType.map() = typeMapper.map(this)
    private val blocksMap = mutableMapOf<SSABlock, LLVMBasicBlockRef>()
    private val blockParamToPhi = mutableMapOf<SSABlockParam, LLVMValueRef>()

    fun generate(): LLVMValueRef {
        SSARender().renderFunctionAsDot(ssaFunc).let { cfgDot ->
            context.config.tempFiles.create(ssaFunc.name, ".dot").writeText(cfgDot)
        }
        for (block in ssaFunc.blocks) {
            val bb = LLVMAppendBasicBlockInContext(llvmContext, llvmFunc, block.id.toString())!!
            blocksMap[block] = bb
            codegen.positionAtEnd(bb)
            block.params.forEach {
                blockParamToPhi[it] = codegen.phi(it.type.map())
            }
            if (block.id is SSABlockId.Entry) {
                setupSlots()
            }
        }
        for (block in ssaFunc.blocks) {
            when (block.id) {
                SSABlockId.LandingPad -> generateLandingPad(block)
                else -> generateBlock(block)
            }
        }
        return llvmFunc
    }

    private fun setupSlots() {
        val slotCount = slots.allocs.size
        llvmSlots = LLVMBuildArrayAlloca(codegen.builder, codegen.kObjHeaderPtr, Int32(slotCount).llvm, "")!!
        val slotsMem = codegen.bitcast(kInt8Ptr, llvmSlots)
        codegen.call(context.llvm.memsetFunction,
                listOf(slotsMem, Int8(0).llvm,
                        Int32(slotCount * codegen.runtime.pointerSize).llvm,
                        Int1(0).llvm))
    }

    private fun generateBlock(block: SSABlock) {
        val bb = blocksMap.getValue(block)
        codegen.positionAtEnd(bb)
        for (insn in block.body) {
            emitValue(insn)
        }
    }

    private fun generateLandingPad(block: SSABlock) {
        val bb = blocksMap.getValue(block)
        codegen.positionAtEnd(bb)
    }

    private val valueCache = mutableMapOf<SSAValue, LLVMValueRef>()

    private fun emitValue(value: SSAValue): LLVMValueRef = valueCache.getOrPut(value) {
        when (value) {
            is SSAConstant -> emitConstant(value)
            is SSAInstruction -> emitInstruction(value)
            is SSAFuncArgument -> emitFuncArgument(value)
            is SSABlockParam -> emitBlockParam(value)
            else -> error("Unsupported value type $value")
        }
    }

    private fun emitBlockParam(value: SSABlockParam): LLVMValueRef =
            blockParamToPhi.getValue(value)

    private fun emitFuncArgument(value: SSAFuncArgument): LLVMValueRef =
            codegen.getParam(paramIndex.getValue(value))

    private fun emitConstant(value: SSAConstant): LLVMValueRef = when (value) {
        SSAConstant.Undef -> TODO()
        SSAConstant.Null -> codegen.kNullObjHeaderPtr
        is SSAConstant.Bool -> when (value.value) {
            true -> {
                constTrue
            }
            false -> LLVMConstInt(int1Type, 0, 1)!!
        }
        is SSAConstant.Byte -> LLVMConstInt(int8Type, value.value.toLong(), 1)!!
        is SSAConstant.Char -> LLVMConstInt(int16Type, value.value.toLong(), 0)!!
        is SSAConstant.Short -> LLVMConstInt(int16Type, value.value.toLong(), 1)!!
        is SSAConstant.Int -> LLVMConstInt(int32Type, value.value.toLong(), 1)!!
        is SSAConstant.Long -> LLVMConstInt(int64Type, value.value, 1)!!
        is SSAConstant.Float -> LLVMConstRealOfString(floatType, value.value.toString())!!
        is SSAConstant.Double -> LLVMConstRealOfString(doubleType, value.value.toString())!!
        is SSAConstant.String -> codegen.emitStringConst(value.value)
        SSAConstant.Unit -> TODO()
    }

    private fun emitInstruction(insn: SSAInstruction): LLVMValueRef = when (insn) {
        is SSACallSite -> emitCallSite(insn)
        is SSAReturn -> emitReturn(insn)
        is SSABr -> emitBr(insn)
        is SSACondBr -> emitCondBr(insn)
        is SSAAlloc -> emitAlloc(insn)
        is SSACatch -> emitCatch(insn)
        is SSADeclare -> emitDeclare(insn)
        is SSAThrow -> emitThrow(insn)
        is SSAGetGlobal -> emitGetGlobal(insn)
        is SSASetGlobal -> emitSetGlobal(insn)
        is SSAIncRef -> emitIncRef(insn)
        is SSADecRef -> emitDecRef(insn)
        is SSANOP -> emitNop(insn)
        is SSAGetField -> emitGetField(insn)
        is SSASetField -> emitSetField(insn)
        is SSAGetObjectValue -> emitGetObjectValue(insn)
        is SSAInstanceOf -> emitInstanceOf(insn)
        is SSACast -> emitCast(insn)
        is SSAIntegerCoercion -> emitIntegerCoercion(insn)
        is SSANot -> emitNot(insn)
        is SSAGetITable -> emitGetITable(insn)
        is SSAGetVTable -> emitGetVTable(insn)
    }

    private fun emitGetVTable(insn: SSAGetVTable): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitGetITable(insn: SSAGetITable): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitNot(insn: SSANot): LLVMValueRef =
        codegen.not(emitValue(insn.value))

    private fun emitIntegerCoercion(insn: SSAIntegerCoercion): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitCast(insn: SSACast): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitInstanceOf(insn: SSAInstanceOf): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitGetObjectValue(insn: SSAGetObjectValue): LLVMValueRef {
        val type = insn.type
        if (type is SSAUnitType) {
            return codegen.unique(UniqueKind.UNIT).llvm
        }
        if (type !is SSAClass) TODO("Unsupported type: $type")

        val irClass = type.origin
        if (irClass.isUnit()) {
            return codegen.unique(UniqueKind.UNIT).llvm
        } else {
            TODO("Unsupported class: ${irClass.name}")
        }
    }

    private fun emitSetField(insn: SSASetField): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitGetField(insn: SSAGetField): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitNop(insn: SSANOP): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitDecRef(insn: SSADecRef): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitIncRef(insn: SSAIncRef): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitSetGlobal(insn: SSASetGlobal): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitGetGlobal(insn: SSAGetGlobal): LLVMValueRef {
        TODO("not implemented")
    }

    private fun emitThrow(insn: SSAThrow): LLVMValueRef {
        TODO()
    }

    // TODO: should we build alloca?
    private fun emitDeclare(insn: SSADeclare): LLVMValueRef {
        val initializer = emitValue(insn.value)
//        val slot = codegen.alloca(insn.value.type.map(), insn.name)
//        codegen.store(initializer, slot)
        return initializer
    }

    private fun emitCallSite(callSite: SSACallSite): LLVMValueRef {
        val function = callSite.irOrigin.symbol.owner
        return when {
            function.origin == IrBuiltIns.BUILTIN_OPERATOR -> {
                val args = callSite.operands.map(this::emitValue)
                evaluateOperatorCall(function, args)
            }
            function.isTypedIntrinsic -> {
                val args = callSite.operands.map(this::emitValue)
                intrinsicGenerator.evaluateCall(callSite, args)
            }
            else -> when (callSite) {
                is SSAInvoke -> emitMethodInvoke(callSite)
                is SSADirectCall -> emitDirectCall(callSite)
                is SSAVirtualCall -> emitVirtualCall(callSite)
                is SSAInterfaceCall -> emitInterfaceCall(callSite)
            }
        }
    }

    private fun evaluateOperatorCall(function: IrFunction, args: List<LLVMValueRef>): LLVMValueRef {
        val ib = context.irModule!!.irBuiltins

        with(codegen) {
            val functionSymbol = function.symbol
            return when {
                functionSymbol == ib.eqeqeqSymbol -> icmpEq(args[0], args[1])
                functionSymbol == ib.booleanNotSymbol -> icmpNe(args[0], constTrue)
                functionSymbol.isComparisonFunction(ib.greaterFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpGt(args[0], args[1])
                    else icmpGt(args[0], args[1])
                }
                functionSymbol.isComparisonFunction(ib.greaterOrEqualFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpGe(args[0], args[1])
                    else icmpGe(args[0], args[1])
                }
                functionSymbol.isComparisonFunction(ib.lessFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpLt(args[0], args[1])
                    else icmpLt(args[0], args[1])
                }
                functionSymbol.isComparisonFunction(ib.lessOrEqualFunByOperandType) -> {
                    if (args[0].type.isFloatingPoint()) fcmpLe(args[0], args[1])
                    else icmpLe(args[0], args[1])
                }
                else -> error(function.name.toString())
            }
        }
    }

    private fun emitAlloc(insn: SSAAlloc): LLVMValueRef {
        val typeInfo = when (insn.type) {
            SSAUnitType -> TODO()
            SSAAny -> TODO()
            SSAStringType -> TODO()
            SSANothingType -> TODO()
            is SSAClass -> llvmDeclarations.forClass(insn.type.origin)
        }
        return escapeResults?.let {
            if (it.getValue(insn).escapeState == EscapeState.Local) {
                stackAlloc(typeInfo)
            } else {
                null
            }
        } ?: heapAlloc(typeInfo, insn)
    }

    private fun stackAlloc(typeInfo: ClassLlvmDeclarations): LLVMValueRef {
        val bodyType = typeInfo.bodyType
        val memory = codegen.alloca(bodyType)
        val typeSize = Int32(LLVMSizeOfTypeInBits(codegen.llvmTargetData, bodyType).toInt() / 8)
        val asRawPtr = codegen.bitcast(int8TypePtr, memory)
        codegen.call(context.llvm.memsetFunction,
                listOf(asRawPtr, Int8(0).llvm,
                        typeSize.llvm,
                        Int1(0).llvm))
        val asObjHeader = codegen.bitcast(codegen.kObjHeaderPtr, memory)
        setTypeInfoForLocalObject(asObjHeader, typeInfo.typeInfo.llvm)
        return asObjHeader
    }

    fun setTypeInfoForLocalObject(objectHeader: LLVMValueRef, typeInfoPointer: LLVMValueRef) = with (codegen) {
        val typeInfo = structGep(objectHeader, 0, "typeInfoOrMeta_")
        val typeInfoValue = intToPtr(ptrToInt(typeInfoPointer, codegen.intPtrType), kTypeInfoPtr)
        store(typeInfoValue, typeInfo)
    }


    private fun heapAlloc(typeInfo: ClassLlvmDeclarations, insn: SSAAlloc): LLVMValueRef {
        val slotIndex = slots.allocs.indexOf(insn)
        val slotPtr = codegen.gep(llvmSlots, Int64(slotIndex.toLong()).llvm)
        val ptrToAllocatedMemory = codegen.heapAlloc(typeInfo.typeInfo.llvm)
        codegen.store(ptrToAllocatedMemory, slotPtr)
        return codegen.load(slotPtr)
    }

    private fun emitCondBr(insn: SSACondBr): LLVMValueRef {
        mapArgsToPhis(insn.truEdge)
        mapArgsToPhis(insn.flsEdge)
        val cond = emitValue(insn.condition)
        val truDest = blocksMap.getValue(insn.truEdge.to)
        val flsDest = blocksMap.getValue(insn.flsEdge.to)
        return codegen.condBr(cond, truDest, flsDest)
    }

    private fun emitBr(insn: SSABr): LLVMValueRef {
        mapArgsToPhis(insn.edge)
        val dest = blocksMap.getValue(insn.edge.to)
        return codegen.br(dest)
    }

    private fun emitReturn(insn: SSAReturn): LLVMValueRef {
        val retval = insn.retVal?.let(this::emitValue)
        return codegen.ret(retval)
    }

    private fun emitDirectCall(insn: SSADirectCall): LLVMValueRef {
        val callee = llvmDeclarations.forFunction(insn.callee.irOrigin).llvmFunction
        val args = insn.operands.map(this::emitValue)
        val slot = if (codegen.isObjectReturn(callee.type)) {
            codegen.alloca(codegen.kObjHeaderPtr)
        } else {
            null
        }
        return codegen.call(callee, args + listOfNotNull(slot))
    }

    private fun emitVirtualCall(insn: SSAVirtualCall): LLVMValueRef =
            emitVCall(insn.callee.irOrigin, insn.operands)

    private fun IrSimpleFunction.findOverriddenMethodOfAny(): IrSimpleFunction? {
        if (modality == Modality.ABSTRACT) return null
        val resolved = resolveFakeOverride()
        if ((resolved.parent as IrClass).isAny()) {
            return resolved
        }

        return null
    }

    private fun loadTypeInfo(objPtr: LLVMValueRef): LLVMValueRef = with (codegen) {
        val typeInfoOrMetaPtr = structGep(objPtr, 0  /* typeInfoOrMeta_ */)
        val typeInfoOrMetaWithFlags = load(typeInfoOrMetaPtr)
        // Clear two lower bits.
        val typeInfoOrMetaWithFlagsRaw = ptrToInt(typeInfoOrMetaWithFlags, codegen.intPtrType)
        val typeInfoOrMetaRaw = and(typeInfoOrMetaWithFlagsRaw, codegen.immTypeInfoMask)
        val typeInfoOrMeta = intToPtr(typeInfoOrMetaRaw, kTypeInfoPtr)
        val typeInfoPtrPtr = structGep(typeInfoOrMeta, 0 /* typeInfo */)
        return load(typeInfoPtrPtr)
    }

    private fun emitVCall(irFunction: IrFunction, args: List<SSAValue>): LLVMValueRef {
        val llvmArgs = args.map(this::emitValue)
        val typeInfoPtr: LLVMValueRef = loadTypeInfo(llvmArgs[0])
        /*
         * Resolve owner of the call with special handling of Any methods:
         * if toString/eq/hc is invoked on an interface instance, we resolve
         * owner as Any and dispatch it via vtable.
         */
        val anyMethod = (irFunction as IrSimpleFunction).findOverriddenMethodOfAny()
        val owner = (anyMethod ?: irFunction).parentAsClass
        val methodHash = irFunction.functionName.localHash.llvm

        val llvmMethod = when {
            !owner.isInterface -> {
                // If this is a virtual method of the class - we can call via vtable.
                val index = context.getLayoutBuilder(owner).vtableIndex(anyMethod ?: irFunction)
                val vtablePlace = codegen.gep(typeInfoPtr, Int32(1).llvm) // typeInfoPtr + 1
                val vtable = codegen.bitcast(kInt8PtrPtr, vtablePlace)
                val slot = codegen.gep(vtable, Int32(index).llvm)
                codegen.load(slot)
            }

            else -> codegen.call(context.llvm.lookupOpenMethodFunction, listOf(typeInfoPtr, methodHash))
        }
        val functionPtrType = pointerType(codegen.getLlvmFunctionType(irFunction))
        val llvmCallee = codegen.bitcast(functionPtrType, llvmMethod)
        val llvmArgsWithSlot = if (codegen.isObjectReturn(llvmCallee.type)) {
            val slot = codegen.alloca(codegen.kObjHeaderPtr)
            llvmArgs + slot
        } else {
            llvmArgs
        }
        return codegen.call(llvmCallee, llvmArgsWithSlot)
    }

    private fun emitInterfaceCall(insn: SSAInterfaceCall): LLVMValueRef =
            emitVCall(insn.callee.irOrigin, insn.operands)

    private fun emitMethodInvoke(insn: SSAInvoke): LLVMValueRef {
        val callee = llvmDeclarations.forFunction(insn.callee.irOrigin).llvmFunction
        val args = insn.operands.map(this::emitValue)
        mapArgsToPhis(insn.continuation)
        mapArgsToPhis(insn.exception)
        val thenBlock = blocksMap.getValue(insn.continuation.to)
        val catchBlock = blocksMap.getValue(insn.exception.to)
        return codegen.invoke(callee, args, thenBlock, catchBlock)
    }

    private fun emitCatch(insn: SSACatch): LLVMValueRef {
        val exception = codegen.landingPad()
        return codegen.resume(exception)
    }

    private fun mapArgsToPhis(edge: SSAEdge) {
        val src = blocksMap.getValue(edge.from)
        edge.args.forEachIndexed { idx, value ->
            val blockParam = edge.to.params[idx]
            val phi = blockParamToPhi.getValue(blockParam)
            codegen.addIncoming(phi, src to emitValue(value))
        }
    }
}

