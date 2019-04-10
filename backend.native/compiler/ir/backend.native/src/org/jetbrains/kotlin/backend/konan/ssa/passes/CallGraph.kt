package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSACallSite
import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.SSAModule
import org.jetbrains.kotlin.backend.konan.ssa.WorkList

sealed class CallSite(val caller: SSAFunction, val callees: Set<SSAFunction>) {
    class Mono(caller: SSAFunction, callee: SSAFunction) : CallSite(caller, setOf(callee))

    class Virtual(caller: SSAFunction, callees: Set<SSAFunction>) : CallSite(caller, callees)
}


class CallGraph(val callSites: List<CallSite>)

class CallGraphBuilder(val subTypes: SubTypes) : ModulePass<CallGraph> {
    override val name: String = "Call graph builder"

    lateinit var workList: WorkList<SSAFunction>

    override fun apply(module: SSAModule): CallGraph {
        val roots = findRoots(module)
        workList = WorkList(roots)
        val callSites = mutableListOf<CallSite>()
        while (!workList.isEmpty()) {
            val ssaFunction = workList.get()
            ssaFunction.blocks.flatMap { it.body }.forEach {
                if (it is SSACallSite) {
                    callSites += processCallSite(ssaFunction, it)
                }
            }
        }
        return CallGraph(callSites)
    }

    private fun findRoots(module: SSAModule): List<SSAFunction> {
        return listOf(module.functions.find { it.name == "main" }
                ?: error("No main in module"))
    }

    private fun processCallSite(caller: SSAFunction, callSite: SSACallSite): CallSite {
        val callee = callSite.callee
        return if (callee is SSAFunction) {
            workList.add(callee)
            CallSite.Mono(caller, callee)
        } else {
            val coneTop = callee.irOrigin?.dispatchReceiverParameter?.type ?: error("")
            val subs = subTypes.getSubClasses(coneTop)

            CallSite.Virtual(caller, )
        }
    }
}