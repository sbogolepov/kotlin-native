package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.addFunctionSignext
import org.jetbrains.kotlin.backend.konan.ssa.*

private class Index {
    private val functions = mutableListOf<Pair<SSAFunction, LLVMValueRef>>()
}

internal class LLVMModuleFromSSA(val ssaModule: SSAModule) {

    private val typeMapper = LLVMTypeMapper()

    private val llvmModule = LLVMModuleCreateWithName(ssaModule.name)!!

    private fun SSAType.map() = typeMapper.map(this)

    fun generate(): LLVMModuleRef {
        for (importedFn in ssaModule.imports) {
            emitFunctionImport(importedFn)
        }

        for (function in ssaModule.functions) {

        }
        return llvmModule
    }

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
}



private class LLVMFunctionFromSSA(val func: SSAFunction) {

    val blocksMap = mutableMapOf<SSABlock, LLVMBasicBlockRef>()

    private fun generateBlock(block: SSABlock) {

    }

    private fun generateInstruction(insn: SSAInstruction): LLVMValueRef = when (insn) {
        is SSACall -> generateCall(insn)
        is SSAReturn -> generateReturn(insn)
        is SSABr -> generateBr(insn)
        is SSACondBr -> generateCondBr(insn)
        else -> error("Unsupported insntruction: $insn")
    }

    private fun generateCondBr(insn: SSACondBr): LLVMValueRef {
        TODO()
    }

    private fun generateBr(insn: SSABr): LLVMValueRef {
        TODO()
    }

    private fun generateReturn(insn: SSAReturn): LLVMValueRef {
        TODO()
    }

    private fun generateCall(insn: SSACall): LLVMValueRef {
        TODO()
    }
}

