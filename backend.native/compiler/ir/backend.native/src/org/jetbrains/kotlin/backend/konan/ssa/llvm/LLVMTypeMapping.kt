package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.cValuesOf
import llvm.*
import org.jetbrains.kotlin.backend.konan.ssa.SSAFuncType
import org.jetbrains.kotlin.backend.konan.ssa.SSAPrimitiveType
import org.jetbrains.kotlin.backend.konan.ssa.SSAType
import org.jetbrains.kotlin.backend.konan.ssa.VoidType

internal class LLVMTypeMapper {

    fun map(ssaType: SSAType): LLVMTypeRef = when (ssaType) {
        is SSAFuncType -> mapFunctionalType(ssaType)
        is SSAPrimitiveType -> mapPrimitiveType(ssaType)
        VoidType -> LLVMVoidType()!!
        else -> error("Unsupported SSA type: $ssaType")
    }

    private fun mapPrimitiveType(ssaType: SSAPrimitiveType): LLVMTypeRef = when (ssaType) {
        SSAPrimitiveType.BOOL -> LLVMInt1Type()!!
        SSAPrimitiveType.BYTE -> LLVMInt8Type()!!
        SSAPrimitiveType.CHAR -> LLVMInt16Type()!!
        SSAPrimitiveType.SHORT -> LLVMInt16Type()!!
        SSAPrimitiveType.INT -> LLVMInt32Type()!!
        SSAPrimitiveType.LONG -> LLVMInt64Type()!!
        SSAPrimitiveType.FLOAT -> LLVMFloatType()!!
        SSAPrimitiveType.DOUBLE -> LLVMDoubleType()!!
    }

    private fun mapFunctionalType(ssaType: SSAFuncType): LLVMTypeRef =
            LLVMFunctionType(
                    map(ssaType.returnType),
                    cValuesOf(*ssaType.parameterTypes.map { map(it) }.toTypedArray()),
                    ssaType.parameterTypes.size,
                    if (ssaType.isVararg) 1 else 0
            )!!
}