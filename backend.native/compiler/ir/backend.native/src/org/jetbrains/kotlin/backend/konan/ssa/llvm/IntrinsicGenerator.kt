package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.konan.descriptors.TypedIntrinsic
import org.jetbrains.kotlin.backend.konan.descriptors.isTypedIntrinsic
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.backend.konan.ssa.SSACallSite
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.name.Name

internal enum class IntrinsicType {
    PLUS,
    MINUS,
    TIMES,
    SIGNED_DIV,
    SIGNED_REM,
    UNSIGNED_DIV,
    UNSIGNED_REM,
    INC,
    DEC,
    UNARY_PLUS,
    UNARY_MINUS,
    SHL,
    SHR,
    USHR,
    AND,
    OR,
    XOR,
    INV,
    SIGN_EXTEND,
    ZERO_EXTEND,
    INT_TRUNCATE,
    FLOAT_TRUNCATE,
    FLOAT_EXTEND,
    SIGNED_TO_FLOAT,
    UNSIGNED_TO_FLOAT,
    FLOAT_TO_SIGNED,
    SIGNED_COMPARE_TO,
    UNSIGNED_COMPARE_TO,
    NOT,
    REINTERPRET,
    ARE_EQUAL_BY_VALUE,
    IEEE_754_EQUALS,
    // OBJC
    OBJC_GET_MESSENGER,
    OBJC_GET_MESSENGER_STRET,
    OBJC_GET_OBJC_CLASS,
    OBJC_GET_RECEIVER_OR_SUPER,
    OBJC_INIT_BY,
    // Other
    GET_CLASS_TYPE_INFO,
    CREATE_UNINITIALIZED_INSTANCE,
    LIST_OF_INTERNAL,
    IDENTITY,
    IMMUTABLE_BLOB,
    INIT_INSTANCE,
    // Coroutines
    GET_CONTINUATION,
    RETURN_IF_SUSPEND,
    // Interop
    INTEROP_READ_BITS,
    INTEROP_WRITE_BITS,
    INTEROP_READ_PRIMITIVE,
    INTEROP_WRITE_PRIMITIVE,
    INTEROP_GET_POINTER_SIZE,
    INTEROP_NATIVE_PTR_TO_LONG,
    INTEROP_NATIVE_PTR_PLUS_LONG,
    INTEROP_GET_NATIVE_NULL_PTR,
    INTEROP_CONVERT,
    INTEROP_BITS_TO_FLOAT,
    INTEROP_BITS_TO_DOUBLE,
    INTEROP_SIGN_EXTEND,
    INTEROP_NARROW,
    INTEROP_STATIC_C_FUNCTION,
    INTEROP_FUNPTR_INVOKE,
    // Worker
    WORKER_EXECUTE
}

// Explicit and single interface between Intrinsic Generator and IrToBitcode.
internal interface IntrinsicGeneratorEnvironment {
    val codegen: LLVMCodeGenerator

//    val continuation: LLVMValueRef

//    val exceptionHandler: ExceptionHandler
//
//    fun calculateLifetime(element: IrElement): Lifetime
//
//    fun evaluateCall(function: IrFunction, args: List<LLVMValueRef>, resultLifetime: Lifetime): LLVMValueRef
//
//    fun evaluateExplicitArgs(expression: IrMemberAccessExpression): List<LLVMValueRef>
//
//    fun evaluateExpression(value: IrExpression): LLVMValueRef
}

internal fun tryGetIntrinsicType(callSite: IrFunctionAccessExpression): IntrinsicType? =
        if (callSite.symbol.owner.isTypedIntrinsic) getIntrinsicType(callSite) else null

private fun getIntrinsicType(callSite: IrFunctionAccessExpression): IntrinsicType {
    val function = callSite.symbol.owner
    val annotation = function.descriptor.annotations.findAnnotation(TypedIntrinsic)!!
    val value = annotation.allValueArguments.getValue(Name.identifier("kind")).value as String
    return IntrinsicType.valueOf(value)
}

internal class IntrinsicGenerator(private val environment: IntrinsicGeneratorEnvironment) {

    private val codegen = environment.codegen

    private val context = codegen.context

    private val IrCall.llvmReturnType: LLVMTypeRef
        get() = LLVMGetReturnType(codegen.getLlvmFunctionType(symbol.owner))!!

    fun evaluateCall(callSite: SSACallSite, args: List<LLVMValueRef>): LLVMValueRef =
            environment.codegen.evaluateCall(callSite.irOrigin, args)

    // Assuming that we checked for `TypedIntrinsic` annotation presence.
    private fun LLVMCodeGenerator.evaluateCall(callSite: IrCall, args: List<LLVMValueRef>): LLVMValueRef =
            when (val intrinsicType = getIntrinsicType(callSite)) {
                IntrinsicType.PLUS -> emitPlus(args)
                IntrinsicType.MINUS -> emitMinus(args)
                IntrinsicType.TIMES -> emitTimes(args)
                IntrinsicType.SIGNED_DIV -> emitSignedDiv(args)
                IntrinsicType.SIGNED_REM -> emitSignedRem(args)
                IntrinsicType.UNSIGNED_DIV -> emitUnsignedDiv(args)
                IntrinsicType.UNSIGNED_REM -> emitUnsignedRem(args)
                IntrinsicType.INC -> emitInc(args)
                IntrinsicType.DEC -> emitDec(args)
                IntrinsicType.UNARY_PLUS -> emitUnaryPlus(args)
                IntrinsicType.UNARY_MINUS -> emitUnaryMinus(args)
                IntrinsicType.SHL -> emitShl(args)
                IntrinsicType.SHR -> emitShr(args)
                IntrinsicType.USHR -> emitUshr(args)
                IntrinsicType.AND -> emitAnd(args)
                IntrinsicType.OR -> emitOr(args)
                IntrinsicType.XOR -> emitXor(args)
                IntrinsicType.INV -> emitInv(args)
                IntrinsicType.SIGNED_COMPARE_TO -> emitSignedCompareTo(args)
                IntrinsicType.UNSIGNED_COMPARE_TO -> emitUnsignedCompareTo(args)
                IntrinsicType.NOT -> emitNot(args)
                IntrinsicType.REINTERPRET -> emitReinterpret(callSite, args)
                IntrinsicType.SIGN_EXTEND -> emitSignExtend(callSite, args)
                IntrinsicType.ZERO_EXTEND -> emitZeroExtend(callSite, args)
                IntrinsicType.INT_TRUNCATE -> emitIntTruncate(callSite, args)
                IntrinsicType.SIGNED_TO_FLOAT -> emitSignedToFloat(callSite, args)
                IntrinsicType.UNSIGNED_TO_FLOAT -> emitUnsignedToFloat(callSite, args)
                IntrinsicType.FLOAT_TO_SIGNED -> emitFloatToSigned(callSite, args)
                IntrinsicType.FLOAT_EXTEND -> emitFloatExtend(callSite, args)
                IntrinsicType.FLOAT_TRUNCATE -> emitFloatTruncate(callSite, args)
                IntrinsicType.ARE_EQUAL_BY_VALUE -> emitAreEqualByValue(args)
                IntrinsicType.IEEE_754_EQUALS -> emitIeee754Equals(args)
                IntrinsicType.OBJC_GET_MESSENGER -> TODO()//emitObjCGetMessenger(args, isStret = false)
                IntrinsicType.OBJC_GET_MESSENGER_STRET -> TODO()//emitObjCGetMessenger(args, isStret = true)
                IntrinsicType.OBJC_GET_OBJC_CLASS -> TODO()//emitGetObjCClass(callSite)
                IntrinsicType.OBJC_GET_RECEIVER_OR_SUPER -> TODO()//emitGetReceiverOrSuper(args)
                IntrinsicType.GET_CLASS_TYPE_INFO -> TODO()//emitGetClassTypeInfo(callSite)
                IntrinsicType.INTEROP_READ_BITS -> emitReadBits(args)
                IntrinsicType.INTEROP_WRITE_BITS -> emitWriteBits(args)
                IntrinsicType.INTEROP_READ_PRIMITIVE -> emitReadPrimitive(callSite, args)
                IntrinsicType.INTEROP_WRITE_PRIMITIVE -> emitWritePrimitive(callSite, args)
                IntrinsicType.INTEROP_GET_POINTER_SIZE -> emitGetPointerSize()
                IntrinsicType.CREATE_UNINITIALIZED_INSTANCE -> emitCreateUninitializedInstance(callSite)
                IntrinsicType.INTEROP_NATIVE_PTR_TO_LONG -> emitNativePtrToLong(callSite, args)
                IntrinsicType.INTEROP_NATIVE_PTR_PLUS_LONG -> emitNativePtrPlusLong(args)
                IntrinsicType.INTEROP_GET_NATIVE_NULL_PTR -> emitGetNativeNullPtr()
                IntrinsicType.LIST_OF_INTERNAL -> emitListOfInternal(callSite, args)
                IntrinsicType.IDENTITY -> emitIdentity(args)
                IntrinsicType.GET_CONTINUATION -> emitGetContinuation()
                IntrinsicType.RETURN_IF_SUSPEND,
                IntrinsicType.INTEROP_BITS_TO_FLOAT,
                IntrinsicType.INTEROP_BITS_TO_DOUBLE,
                IntrinsicType.INTEROP_SIGN_EXTEND,
                IntrinsicType.INTEROP_NARROW,
                IntrinsicType.INTEROP_STATIC_C_FUNCTION,
                IntrinsicType.INTEROP_FUNPTR_INVOKE,
                IntrinsicType.INTEROP_CONVERT,
                IntrinsicType.WORKER_EXECUTE ->
                    reportNonLoweredIntrinsic(intrinsicType)
                IntrinsicType.INIT_INSTANCE,
                IntrinsicType.OBJC_INIT_BY,
                IntrinsicType.IMMUTABLE_BLOB ->
                    reportSpecialIntrinsic(intrinsicType)
            }

    private fun reportSpecialIntrinsic(intrinsicType: IntrinsicType): Nothing =
            context.reportCompilationError("$intrinsicType should be handled by `tryEvaluateSpecialCall`")

    private fun reportNonLoweredIntrinsic(intrinsicType: IntrinsicType): Nothing =
            context.reportCompilationError("Intrinsic of type $intrinsicType should be handled by previos lowering phase")

    private fun LLVMCodeGenerator.emitGetContinuation(): LLVMValueRef =
            TODO("")

    private fun LLVMCodeGenerator.emitIdentity(args: List<LLVMValueRef>): LLVMValueRef =
            args.single()

    private fun LLVMCodeGenerator.emitListOfInternal(callSite: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val varargExpression = callSite.getValueArgument(0) as IrVararg
        val vararg = args.single()

        val length = varargExpression.elements.size
        // TODO: store length in `vararg` itself when more abstract types will be used for values.

        val array = constPointer(vararg)
        // Note: dirty hack here: `vararg` has type `Array<out E>`, but `createConstArrayList` expects `Array<E>`;
        // however `vararg` is immutable, and in current implementation it has type `Array<E>`,
        // so let's ignore this mismatch currently for simplicity.

        return context.llvm.staticData.createConstArrayList(array, length).llvm
    }

    private fun emitGetNativeNullPtr(): LLVMValueRef =
            kNullInt8Ptr

    private fun LLVMCodeGenerator.emitNativePtrPlusLong(args: List<LLVMValueRef>): LLVMValueRef =
        gep(args[0], args[1])

    private fun LLVMCodeGenerator.emitNativePtrToLong(callSite: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val intPtrValue = ptrToInt(args.single(), codegen.intPtrType)
        val resultType = callSite.llvmReturnType
        return if (resultType == intPtrValue.type) {
            intPtrValue
        } else {
            LLVMBuildSExt(builder, intPtrValue, resultType, "")!!
        }
    }

    private fun LLVMCodeGenerator.emitCreateUninitializedInstance(callSite: IrCall): LLVMValueRef {
        val typeParameterT = context.ir.symbols.createUninitializedInstance.descriptor.typeParameters[0]
        val enumClass = callSite.getTypeArgument(typeParameterT)!!
        val enumIrClass = enumClass.getClass()!!
        return TODO()//allocInstance(enumIrClass, environment.calculateLifetime(callSite))
    }

    private fun LLVMCodeGenerator.emitGetPointerSize(): LLVMValueRef =
            Int32(LLVMPointerSize(codegen.llvmTargetData)).llvm

    private fun LLVMCodeGenerator.emitReadPrimitive(callSite: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val pointerType = pointerType(callSite.llvmReturnType)
        val rawPointer = args.last()
        val pointer = bitcast(pointerType, rawPointer)
        return load(pointer)
    }

    private fun LLVMCodeGenerator.emitWritePrimitive(callSite: IrCall, args: List<LLVMValueRef>): LLVMValueRef {
        val function = callSite.symbol.owner
        val pointerType = pointerType(codegen.getLLVMType(function.valueParameters.last().type))
        val rawPointer = args[1]
        val pointer = bitcast(pointerType, rawPointer)
        store(args[2], pointer)
        return codegen.theUnitInstanceRef.llvm
    }

    private fun LLVMCodeGenerator.emitReadBits(args: List<LLVMValueRef>): LLVMValueRef {
        val ptr = args[0]
        assert(ptr.type == int8TypePtr)

        val offset = extractConstUnsignedInt(args[1])
        val size = extractConstUnsignedInt(args[2]).toInt()
        val signed = extractConstUnsignedInt(args[3]) != 0L

        val prefixBitsNum = (offset % 8).toInt()
        val suffixBitsNum = (8 - ((size + offset) % 8).toInt()) % 8

        // Note: LLVM allows to read without padding tail up to byte boundary, but the result seems to be incorrect.

        val bitsWithPaddingNum = prefixBitsNum + size + suffixBitsNum
        val bitsWithPaddingType = LLVMIntType(bitsWithPaddingNum)!!

        val bitsWithPaddingPtr = bitcast(org.jetbrains.kotlin.backend.konan.llvm.pointerType(bitsWithPaddingType), gep(ptr, org.jetbrains.kotlin.backend.konan.llvm.Int64(offset / 8).llvm))
        val bitsWithPadding = load(bitsWithPaddingPtr).setUnaligned()

        val bits = shr(
                shl(bitsWithPadding, suffixBitsNum),
                prefixBitsNum + suffixBitsNum, signed
        )
        return when {
            bitsWithPaddingNum == 64 -> bits
            bitsWithPaddingNum > 64 -> trunc(bits, org.jetbrains.kotlin.backend.konan.llvm.int64Type)
            else -> ext(bits, org.jetbrains.kotlin.backend.konan.llvm.int64Type, signed)
        }
    }

    private fun LLVMCodeGenerator.emitWriteBits(args: List<LLVMValueRef>): LLVMValueRef {
        val ptr = args[0]
        assert(ptr.type == int8TypePtr)

        val offset = extractConstUnsignedInt(args[1])
        val size = extractConstUnsignedInt(args[2]).toInt()

        val value = args[3]
        assert(value.type == int64Type)

        val bitsType = LLVMIntType(size)!!

        val prefixBitsNum = (offset % 8).toInt()
        val suffixBitsNum = (8 - ((size + offset) % 8).toInt()) % 8

        val bitsWithPaddingNum = prefixBitsNum + size + suffixBitsNum
        val bitsWithPaddingType = LLVMIntType(bitsWithPaddingNum)!!

        // 0011111000:
        val discardBitsMask = LLVMConstShl(
                LLVMConstZExt(
                        LLVMConstAllOnes(bitsType), // 11111
                        bitsWithPaddingType
                ), // 1111100000
                LLVMConstInt(bitsWithPaddingType, prefixBitsNum.toLong(), 0)
        )

        val preservedBitsMask = LLVMConstNot(discardBitsMask)!!

        val bitsWithPaddingPtr = bitcast(pointerType(bitsWithPaddingType), gep(ptr, Int64(offset / 8).llvm))

        val bits = trunc(value, bitsType)

        val bitsToStore = if (prefixBitsNum == 0 && suffixBitsNum == 0) {
            bits
        } else {
            val previousValue = load(bitsWithPaddingPtr).setUnaligned()
            val preservedBits = and(previousValue, preservedBitsMask)
            val bitsWithPadding = shl(zext(bits, bitsWithPaddingType), prefixBitsNum)

            or(bitsWithPadding, preservedBits)
        }
        llvm.LLVMBuildStore(builder, bitsToStore, bitsWithPaddingPtr)!!.setUnaligned()
        return codegen.theUnitInstanceRef.llvm
    }

    private fun extractConstUnsignedInt(value: LLVMValueRef): Long {
        assert(LLVMIsConstant(value) != 0)
        return LLVMConstIntGetZExtValue(value)
    }

    private fun LLVMCodeGenerator.emitAreEqualByValue(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        assert (first.type == second.type) { "Types are different: '${llvmtype2string(first.type)}' and '${llvmtype2string(second.type)}'" }

        return when (val typeKind = LLVMGetTypeKind(first.type)) {
            llvm.LLVMTypeKind.LLVMFloatTypeKind, llvm.LLVMTypeKind.LLVMDoubleTypeKind -> {
                val numBits = llvm.LLVMSizeOfTypeInBits(codegen.llvmTargetData, first.type).toInt()
                val integerType = llvm.LLVMIntType(numBits)!!
                icmpEq(bitcast(integerType, first), bitcast(integerType, second))
            }
            llvm.LLVMTypeKind.LLVMIntegerTypeKind, llvm.LLVMTypeKind.LLVMPointerTypeKind -> icmpEq(first, second)
            else -> error(typeKind)
        }
    }

    private fun LLVMCodeGenerator.emitIeee754Equals(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        assert (first.type == second.type)
                { "Types are different: '${llvmtype2string(first.type)}' and '${llvmtype2string(second.type)}'" }
        val type = LLVMGetTypeKind(first.type)
        assert (type == LLVMTypeKind.LLVMFloatTypeKind || type == LLVMTypeKind.LLVMDoubleTypeKind)
                { "Should be of floating point kind, not: '${llvmtype2string(first.type)}'"}
        return fcmpEq(first, second)
    }

    private fun LLVMCodeGenerator.emitReinterpret(callSite: IrCall, args: List<LLVMValueRef>) =
            bitcast(callSite.llvmReturnType, args[0])

    private fun LLVMCodeGenerator.emitNot(args: List<LLVMValueRef>) =
            not(args[0])

    private fun LLVMCodeGenerator.emitPlus(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return if (first.type.isFloatingPoint()) {
            fadd(first, second)
        } else {
            add(first, second)
        }
    }

    private fun LLVMCodeGenerator.emitSignExtend(callSite: IrCall, args: List<LLVMValueRef>) =
            sext(args[0], callSite.llvmReturnType)

    private fun LLVMCodeGenerator.emitZeroExtend(callSite: IrCall, args: List<LLVMValueRef>) =
            zext(args[0], callSite.llvmReturnType)

    private fun LLVMCodeGenerator.emitIntTruncate(callSite: IrCall, args: List<LLVMValueRef>) =
            trunc(args[0], callSite.llvmReturnType)

    private fun LLVMCodeGenerator.emitSignedToFloat(callSite: IrCall, args: List<LLVMValueRef>) =
            LLVMBuildSIToFP(builder, args[0], callSite.llvmReturnType, "")!!

    private fun LLVMCodeGenerator.emitUnsignedToFloat(callSite: IrCall, args: List<LLVMValueRef>) =
            LLVMBuildUIToFP(builder, args[0], callSite.llvmReturnType, "")!!

    private fun LLVMCodeGenerator.emitFloatToSigned(callSite: IrCall, args: List<LLVMValueRef>) =
            LLVMBuildFPToSI(builder, args[0], callSite.llvmReturnType, "")!!

    private fun LLVMCodeGenerator.emitFloatExtend(callSite: IrCall, args: List<LLVMValueRef>) =
            LLVMBuildFPExt(builder, args[0], callSite.llvmReturnType, "")!!

    private fun LLVMCodeGenerator.emitFloatTruncate(callSite: IrCall, args: List<LLVMValueRef>) =
            LLVMBuildFPTrunc(builder, args[0], callSite.llvmReturnType, "")!!

    private fun LLVMCodeGenerator.emitShift(op: LLVMOpcode, args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        val shift = if (first.type == int64Type) {
            val tmp = and(second, Int32(63).llvm)
            zext(tmp, int64Type)
        } else {
            and(second, Int32(31).llvm)
        }
        return LLVMBuildBinOp(builder, op, first, shift, "")!!
    }

    private fun LLVMCodeGenerator.emitShl(args: List<LLVMValueRef>) =
            emitShift(LLVMOpcode.LLVMShl, args)

    private fun LLVMCodeGenerator.emitShr(args: List<LLVMValueRef>) =
            emitShift(LLVMOpcode.LLVMAShr, args)

    private fun LLVMCodeGenerator.emitUshr(args: List<LLVMValueRef>) =
            emitShift(LLVMOpcode.LLVMLShr, args)

    private fun LLVMCodeGenerator.emitAnd(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return and(first, second)
    }

    private fun LLVMCodeGenerator.emitOr(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return or(first, second)
    }

    private fun LLVMCodeGenerator.emitXor(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return xor(first, second)
    }

    private fun LLVMCodeGenerator.emitInv(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val mask = makeConstOfType(first.type, -1)
        return xor(first, mask)
    }

    private fun LLVMCodeGenerator.emitMinus(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return if (first.type.isFloatingPoint()) {
            fsub(first, second)
        } else {
            sub(first, second)
        }
    }

    private fun LLVMCodeGenerator.emitTimes(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFMul(builder, first, second, "")
        } else {
            LLVMBuildMul(builder, first, second, "")
        }!!
    }

    private fun LLVMCodeGenerator.emitThrowIfZero(divider: LLVMValueRef) {
//        ifThen(icmpEq(divider, Zero(divider.type).llvm)) {
//            val throwArithExc = codegen.llvmFunction(context.ir.symbols.throwArithmeticException.owner)
//            call(throwArithExc, emptyList(), Lifetime.GLOBAL, environment.exceptionHandler)
//            unreachable()
//        }
    }

    private fun LLVMCodeGenerator.emitSignedDiv(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        if (!second.type.isFloatingPoint()) {
            emitThrowIfZero(second)
        }
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFDiv(builder, first, second, "")
        } else {
            LLVMBuildSDiv(builder, first, second, "")
        }!!
    }

    private fun LLVMCodeGenerator.emitSignedRem(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        if (!second.type.isFloatingPoint()) {
            emitThrowIfZero(second)
        }
        return if (first.type.isFloatingPoint()) {
            LLVMBuildFRem(builder, first, second, "")
        } else {
            LLVMBuildSRem(builder, first, second, "")
        }!!
    }

    private fun LLVMCodeGenerator.emitUnsignedDiv(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        emitThrowIfZero(second)
        return LLVMBuildUDiv(builder, first, second, "")!!
    }

    private fun LLVMCodeGenerator.emitUnsignedRem(args: List<LLVMValueRef>): LLVMValueRef {
        val (first, second) = args
        emitThrowIfZero(second)
        return LLVMBuildURem(builder, first, second, "")!!
    }

    private fun LLVMCodeGenerator.emitInc(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val const1 = makeConstOfType(first.type, 1)
        return if (first.type.isFloatingPoint()) {
            fadd(first, const1)
        } else {
            add(first, const1)
        }
    }

    private fun LLVMCodeGenerator.emitDec(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val const1 = makeConstOfType(first.type, 1)
        return if (first.type.isFloatingPoint()) {
            fsub(first, const1)
        } else {
            sub(first, const1)
        }
    }

    private fun LLVMCodeGenerator.emitUnaryPlus(args: List<LLVMValueRef>) =
            args[0]

    private fun LLVMCodeGenerator.emitUnaryMinus(args: List<LLVMValueRef>): LLVMValueRef {
        val first = args[0]
        val destTy = first.type
        val const0 = makeConstOfType(destTy, 0)
        return if (destTy.isFloatingPoint()) {
            fsub(const0, first)
        } else {
            sub(const0, first)
        }
    }

    private fun LLVMCodeGenerator.emitCompareTo(args: List<LLVMValueRef>, signed: Boolean): LLVMValueRef {
        val (first, second) = args
        val equal = icmpEq(first, second)
        val less = if (signed) icmpLt(first, second) else icmpULt(first, second)
        val tmp = select(less, Int32(-1).llvm, Int32(1).llvm)
        return select(equal, Int32(0).llvm, tmp)
    }

    private fun LLVMCodeGenerator.emitSignedCompareTo(args: List<LLVMValueRef>) =
            emitCompareTo(args, signed = true)

    private fun LLVMCodeGenerator.emitUnsignedCompareTo(args: List<LLVMValueRef>) =
            emitCompareTo(args, signed = false)

    private fun makeConstOfType(type: LLVMTypeRef, value: Int): LLVMValueRef = when (type) {
        int8Type -> Int8(value.toByte()).llvm
        int16Type -> Char16(value.toChar()).llvm
        int32Type -> Int32(value).llvm
        int64Type -> Int64(value.toLong()).llvm
        floatType -> Float32(value.toFloat()).llvm
        doubleType -> Float64(value.toDouble()).llvm
        else -> context.reportCompilationError("Unexpected primitive type: $type")
    }
}