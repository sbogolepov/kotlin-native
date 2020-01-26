package org.jetbrains.kotlin.backend.konan.ssa.llvm

import kotlinx.cinterop.cValuesOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues
import llvm.*
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.llvm.*

internal class LLVMCodeGenerator(
        override val context: Context,
        private  val llvmFn: LLVMValueRef
) : ContextUtils {

    val llvm = context.llvm

    val builder: LLVMBuilderRef = LLVMCreateBuilderInContext(llvmContext)!!

    val intPtrType = LLVMIntPtrTypeInContext(llvmContext, llvmTargetData)!!

    fun positionAtEnd(block: LLVMBasicBlockRef) {
        LLVMPositionBuilderAtEnd(builder, block)
    }

    fun br(dest: LLVMBasicBlockRef) = LLVMBuildBr(builder, dest)!!

    fun ret(value: LLVMValueRef?): LLVMValueRef =
            if (value == null) LLVMBuildRetVoid(builder)!! else LLVMBuildRet(builder, value)!!

    fun phi(ty: LLVMTypeRef) = LLVMBuildPhi(builder, ty, "")!!

    fun addIncoming(phi: LLVMValueRef, vararg incoming: Pair<LLVMBasicBlockRef, LLVMValueRef>) {
        memScoped {
            val incomingValues = incoming.map { it.second }.toCValues()
            val incomingBlocks = incoming.map { it.first }.toCValues()

            LLVMAddIncoming(phi, incomingValues, incomingBlocks, incoming.size)
        }
    }

    fun call(callee: LLVMValueRef, args: List<LLVMValueRef>): LLVMValueRef {
        return LLVMBuildCall(builder, callee, args.toCValues(), args.size, "")!!
    }

    fun invoke(callee: LLVMValueRef, args: List<LLVMValueRef>,
               thenBlock: LLVMBasicBlockRef, catchBlock: LLVMBasicBlockRef): LLVMValueRef {
        return LLVMBuildInvoke(builder, callee, args.toCValues(), args.size, thenBlock, catchBlock, "")!!
    }

    fun heapAlloc(type: LLVMTypeRef): LLVMValueRef = TODO()

    fun condBr(condVal: LLVMValueRef, truBlock: LLVMBasicBlockRef, flsBlock: LLVMBasicBlockRef) =
            LLVMBuildCondBr(builder, condVal, truBlock, flsBlock)!!

    fun gep(base: LLVMValueRef, index: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildGEP(builder, base, cValuesOf(index), 1, name)!!

    fun getParam(paramIndex: Int): LLVMValueRef =
            LLVMGetParam(llvmFn, paramIndex)!!

    fun icmpEq(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntEQ, arg0, arg1, name)!!

    fun icmpGt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSGT, arg0, arg1, name)!!

    fun icmpGe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSGE, arg0, arg1, name)!!

    fun icmpLt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSLT, arg0, arg1, name)!!

    fun icmpLe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntSLE, arg0, arg1, name)!!

    fun icmpNe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntNE, arg0, arg1, name)!!

    fun icmpULt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntULT, arg0, arg1, name)!!

    fun icmpUGt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildICmp(builder, LLVMIntPredicate.LLVMIntUGT, arg0, arg1, name)!!

    /* floating-point comparisons */
    fun fcmpEq(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFCmp(builder, LLVMRealPredicate.LLVMRealOEQ, arg0, arg1, name)!!

    fun fcmpGt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFCmp(builder, LLVMRealPredicate.LLVMRealOGT, arg0, arg1, name)!!

    fun fcmpGe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFCmp(builder, LLVMRealPredicate.LLVMRealOGE, arg0, arg1, name)!!

    fun fcmpLt(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFCmp(builder, LLVMRealPredicate.LLVMRealOLT, arg0, arg1, name)!!

    fun fcmpLe(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFCmp(builder, LLVMRealPredicate.LLVMRealOLE, arg0, arg1, name)!!

    fun sub(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildSub(builder, arg0, arg1, name)!!

    fun add(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildAdd(builder, arg0, arg1, name)!!

    fun fsub(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFSub(builder, arg0, arg1, name)!!

    fun fadd(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildFAdd(builder, arg0, arg1, name)!!

    fun select(ifValue: LLVMValueRef, thenValue: LLVMValueRef, elseValue: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildSelect(builder, ifValue, thenValue, elseValue, name)!!

    fun bitcast(type: LLVMTypeRef?, value: LLVMValueRef, name: String = "") =
            LLVMBuildBitCast(builder, value, type, name)!!

    fun intToPtr(value: LLVMValueRef?, DestTy: LLVMTypeRef, Name: String = "") =
            LLVMBuildIntToPtr(builder, value, DestTy, Name)!!

    fun ptrToInt(value: LLVMValueRef?, DestTy: LLVMTypeRef, Name: String = "") =
            LLVMBuildPtrToInt(builder, value, DestTy, Name)!!

    fun not(arg: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildNot(builder, arg, name)!!

    fun and(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildAnd(builder, arg0, arg1, name)!!

    fun or(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildOr(builder, arg0, arg1, name)!!

    fun xor(arg0: LLVMValueRef, arg1: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildXor(builder, arg0, arg1, name)!!

    fun zext(arg: LLVMValueRef, type: LLVMTypeRef): LLVMValueRef =
            LLVMBuildZExt(builder, arg, type, "")!!

    fun sext(arg: LLVMValueRef, type: LLVMTypeRef): LLVMValueRef =
            LLVMBuildSExt(builder, arg, type, "")!!

    fun ext(arg: LLVMValueRef, type: LLVMTypeRef, signed: Boolean): LLVMValueRef =
            if (signed) {
                sext(arg, type)
            } else {
                zext(arg, type)
            }

    fun trunc(arg: LLVMValueRef, type: LLVMTypeRef): LLVMValueRef =
            LLVMBuildTrunc(builder, arg, type, "")!!

    fun load(value: LLVMValueRef, name: String = ""): LLVMValueRef =
            LLVMBuildLoad(builder, value, name)!!

    fun store(value: LLVMValueRef, ptr: LLVMValueRef) {
        LLVMBuildStore(builder, value, ptr)
    }

    private fun shift(op: LLVMOpcode, arg: LLVMValueRef, amount: Int) =
            if (amount == 0) {
                arg
            } else {
                LLVMBuildBinOp(builder, op, arg, LLVMConstInt(arg.type, amount.toLong(), 0), "")!!
            }

    fun shl(arg: LLVMValueRef, amount: Int) =
            shift(LLVMOpcode.LLVMShl, arg, amount)

    fun shr(arg: LLVMValueRef, amount: Int, signed: Boolean) =
            shift(if (signed) LLVMOpcode.LLVMAShr else LLVMOpcode.LLVMLShr, arg, amount)

    fun emitStringConst(value: String): LLVMValueRef =
        llvm.staticData.kotlinStringLiteral(value).llvm

    fun resume(exception: LLVMValueRef): LLVMValueRef =
            LLVMBuildResume(builder, exception)!!

    fun landingPad(): LLVMValueRef {
        val landingpadType = structType(int8TypePtr, int32Type)
        return LLVMBuildLandingPad(builder, landingpadType, llvm.gxxPersonalityFunction, 0, "")!!.also {
            LLVMSetCleanup(it, 1)
        }
    }
}