package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.int1Type
import org.jetbrains.kotlin.backend.konan.llvm.int8Type
import org.jetbrains.kotlin.backend.konan.llvm.voidType
import org.jetbrains.kotlin.backend.konan.ssa.*
import org.jetbrains.kotlin.ir.types.isUnit

internal class LLVMTypeMapper(val runtime: Runtime) {

    fun map(ssaType: SSAType): LLVMTypeRef = when (ssaType) {
        is SSAFuncType -> mapFunctionalType(ssaType)
        is SSAPrimitiveType -> mapPrimitiveType(ssaType)
        is SSAWrapperType -> mapWrapperType(ssaType)
        is SSAClass -> mapClassType(ssaType)
        VoidType -> voidType
        else -> error("Unsupported SSA type: $ssaType")
    }

    private fun mapClassType(ssaClass: SSAClass): LLVMTypeRef {
        TODO()
    }

    fun mapReturnType(ssaType: SSAType): LLVMTypeRef = when (ssaType) {
        is SSAWrapperType -> if (ssaType.irType.isUnit()) voidType else map(ssaType)
        else -> map(ssaType)
    }

    private fun mapPrimitiveType(ssaType: SSAPrimitiveType): LLVMTypeRef = when (ssaType) {
        SSAPrimitiveType.BOOL -> int1Type
        SSAPrimitiveType.BYTE -> int8Type
        SSAPrimitiveType.CHAR -> int16Type
        SSAPrimitiveType.SHORT -> int16Type
        SSAPrimitiveType.INT -> int32Type
        SSAPrimitiveType.LONG -> int64Type
        SSAPrimitiveType.FLOAT -> floatType
        SSAPrimitiveType.DOUBLE -> doubleType
    }

    private fun mapFunctionalType(ssaType: SSAFuncType): LLVMTypeRef =
            LLVMFunctionType(
                    mapReturnType(ssaType.returnType),
                    cValuesOf(*ssaType.parameterTypes.map { map(it) }.toTypedArray()),
                    ssaType.parameterTypes.size,
                    if (ssaType.isVararg) 1 else 0
            )!!

    private fun mapWrapperType(ssaType: SSAWrapperType): LLVMTypeRef {
        return runtime.objHeaderPtrType
    }
}