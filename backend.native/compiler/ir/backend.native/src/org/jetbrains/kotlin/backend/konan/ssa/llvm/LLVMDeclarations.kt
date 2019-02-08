package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.addFunctionSignext
import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.SSAModule
import org.jetbrains.kotlin.backend.konan.ssa.SSAType


internal class LLVMDeclarationsBuilder(
        val ssaModule: SSAModule,
        val llvmModule: LLVMModuleRef,
        val typeMapper: LLVMTypeMapper
) {

    private fun SSAType.map() = typeMapper.map(this)

    private fun emitFunctionImport(func: SSAFunction): LLVMValueRef {
        val type: LLVMTypeRef = func.type.map()
        val llvmFunc = LLVMAddFunction(llvmModule, func.name, type)!!
        return memScoped {
            val paramCount = LLVMCountParamTypes(type)
            val paramTypes = allocArray<LLVMTypeRefVar>(paramCount)
            LLVMGetParamTypes(type, paramTypes)
            (0 until paramCount).forEach { index ->
                val paramType = paramTypes[index]
                addFunctionSignext(llvmFunc, index + 1, paramType)
            }
            val returnType = LLVMGetReturnType(type)
            addFunctionSignext(llvmFunc, 0, returnType)
            llvmFunc
        }
    }

    fun build(): LLVMDeclarations {
        val imports = ssaModule.imports.map { importedFn ->
            importedFn to emitFunctionImport(importedFn)
        }.toMap()

        val decls = ssaModule.functions.map { function ->
            function to LLVMAddFunction(llvmModule, function.name, function.type.map())!!
        }.toMap()

        return LLVMDeclarations(imports + decls, emptyMap())
    }
}

class LLVMDeclarations(
        val functions: Map<SSAFunction, LLVMValueRef>,
        val types: Map<SSAType, LLVMTypeRef>
)