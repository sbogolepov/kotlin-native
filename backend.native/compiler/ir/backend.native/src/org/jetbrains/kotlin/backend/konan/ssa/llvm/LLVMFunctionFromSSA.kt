package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import llvm.*
import org.jetbrains.kotlin.backend.konan.llvm.addFunctionSignext
import org.jetbrains.kotlin.backend.konan.ssa.*

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



private class LLVMFunctionFromSSA(
        val ssaFunc: SSAFunction,
        val llvmFunc: LLVMValueRef,
        val typeMapper: LLVMTypeMapper
) {

    private fun SSAType.map() = typeMapper.map(this)

    val blocksMap = mutableMapOf<SSABlock, LLVMBasicBlockRef>()

    fun generate(): LLVMValueRef {
        for (block in ssaFunc.blocks) {
            blocksMap[block] = LLVMAppendBasicBlock(llvmFunc, block.id.toString())!!
        }
        for (block in ssaFunc.blocks) {
            generateBlock(block)
        }
        return llvmFunc
    }

    private fun generateBlock(block: SSABlock) {

    }

    private fun emitValue(value: SSAValue): LLVMValueRef = when (value) {
        is SSAConstant -> emitConstant(value)
        is SSAInstruction -> emitInstruction(value)
    }

    private fun emitConstant(value: SSAConstant): LLVMValueRef {
        return when (value) {
            SSAConstant.Undef -> TODO()
            SSAConstant.Null -> TODO()
            is SSAConstant.Bool -> LLVMConstInt(value.type.map(), value.value )
            is SSAConstant.Byte -> TODO()
            is SSAConstant.Char -> TODO()
            is SSAConstant.Short -> TODO()
            is SSAConstant.Int -> TODO()
            is SSAConstant.Long -> TODO()
            is SSAConstant.Float -> TODO()
            is SSAConstant.Double -> TODO()
            is SSAConstant.String -> TODO()
        }
    }

    private fun emitInstruction(insn: SSAInstruction): LLVMValueRef = when (insn) {
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
        LLVMBuildRet()
    }

    private fun generateCall(insn: SSACall): LLVMValueRef {
        TODO()
    }
}

