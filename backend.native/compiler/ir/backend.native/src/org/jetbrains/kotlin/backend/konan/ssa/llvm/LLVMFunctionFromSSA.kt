package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.addFunctionSignext
import org.jetbrains.kotlin.backend.konan.llvm.kNullObjHeaderPtr
import org.jetbrains.kotlin.backend.konan.ssa.*

internal class LLVMModuleFromSSA(val context: Context, val ssaModule: SSAModule) {

    private val typeMapper = LLVMTypeMapper()

    private val llvmModule = LLVMModuleCreateWithName(ssaModule.name)!!

    private val codegen = LLVMCodeGenerator(context)

    private fun SSAType.map() = typeMapper.map(this)

    fun generate(): LLVMModuleRef {
        for (importedFn in ssaModule.imports) {
            emitFunctionImport(importedFn)
        }

        for (function in ssaModule.functions) {
            val llvmFn = TODO()
            LLVMFunctionFromSSA(function, llvmFn, typeMapper, codegen)
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
        val typeMapper: LLVMTypeMapper,
        val codegen: LLVMCodeGenerator
) {

    private fun SSAType.map() = typeMapper.map(this)

    val blocksMap = mutableMapOf<SSABlock, LLVMBasicBlockRef>()
    val blockToPhis = mutableMapOf<LLVMBasicBlockRef, MutableList<LLVMValueRef>>()

    fun generate(): LLVMValueRef {
        for (block in ssaFunc.blocks) {
            blocksMap[block] = LLVMAppendBasicBlock(llvmFunc, block.id.toString())!!
            if (block.params.isNotEmpty()) {
                val bb = blocksMap.getValue(block)
                codegen.positionAtEnd(bb)
                blockToPhis[bb] = mutableListOf()
                for (param in block.params) {
                    val phi = codegen.phi(param.type.map())
                    blockToPhis.getValue(bb).add(phi)
                }
            }
        }
        for (block in ssaFunc.blocks) {
            generateBlock(block)
        }
        return llvmFunc
    }

    private fun generateBlock(block: SSABlock) {
        for (insn in block.body) {
            emitInstruction(insn)
        }
    }

    private fun emitValue(value: SSAValue): LLVMValueRef = when (value) {
        is SSAConstant -> emitConstant(value)
        is SSAInstruction -> emitInstruction(value)
        else -> error("Unsupported value type $value")
    }

    private fun emitConstant(value: SSAConstant): LLVMValueRef {
        return when (value) {
            SSAConstant.Undef -> TODO()
            SSAConstant.Null -> codegen.kNullObjHeaderPtr
            is SSAConstant.Bool -> when (value.value) {
                true -> LLVMConstInt(LLVMInt1Type(), 1, 1)!!
                false -> LLVMConstInt(LLVMInt1Type(), 0, 1)!!
            }
            is SSAConstant.Byte -> LLVMConstInt(LLVMInt8Type(), value.value.toLong(), 1)!!
            is SSAConstant.Char -> LLVMConstInt(LLVMInt16Type(), value.value.toLong(), 0)!!
            is SSAConstant.Short -> LLVMConstInt(LLVMInt16Type(), value.value.toLong(), 1)!!
            is SSAConstant.Int -> LLVMConstInt(LLVMInt32Type(), value.value.toLong(), 1)!!
            is SSAConstant.Long -> LLVMConstInt(LLVMInt64Type(), value.value, 1)!!
            is SSAConstant.Float -> LLVMConstRealOfString(LLVMFloatType(), value.value.toString())!!
            is SSAConstant.Double -> LLVMConstRealOfString(LLVMDoubleType(), value.value.toString())!!
            is SSAConstant.String -> TODO()
        }
    }

    private fun emitInstruction(insn: SSAInstruction): LLVMValueRef = when (insn) {
        is SSACall -> emitCall(insn)
        is SSAReturn -> emitReturn(insn)
        is SSABr -> emitBr(insn)
        is SSACondBr -> emitCondBr(insn)
        is SSAAlloc -> emitAlloc(insn)
        else -> error("Unsupported insntruction: $insn")
    }

    private fun emitAlloc(insn: SSAAlloc): LLVMValueRef {
        return codegen.heapAlloc(insn.type.map())
    }

    private fun emitCondBr(insn: SSACondBr): LLVMValueRef {
        mapArgsToPhis(insn.truEdge)
        mapArgsToPhis(insn.flsEdge)
        val cond = emitValue(insn.condition)
        val truDest = blocksMap.getValue(insn.truEdge.to)
        val flsDest = blocksMap.getValue(insn.flsEdge.to)
        return codegen.condBr(cond, truDest, flsDest)
    }

    // TODO: translate edge args to phi
    private fun emitBr(insn: SSABr): LLVMValueRef {
        mapArgsToPhis(insn.edge)
        val dest = blocksMap.getValue(insn.edge.to)
        return codegen.br(dest)
    }

    private fun emitReturn(insn: SSAReturn): LLVMValueRef {
        val retval = emitValue(insn.retVal)
        return codegen.ret(retval)
    }

    private fun emitCall(insn: SSACall): LLVMValueRef {
        TODO()
    }

    private fun mapArgsToPhis(edge: SSAEdge) {
        val dest = blocksMap.getValue(edge.to)
        edge.args.forEachIndexed { idx, value ->
            val phi = blockToPhis.getValue(dest)[idx]
            codegen.addIncoming(phi, dest to emitValue(value))
        }
    }
}

