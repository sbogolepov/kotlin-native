package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.*

class ClassHierarchyAnalysisResults()

class ClassHierarchyAnalysis : ModulePass<ClassHierarchyAnalysisResults> {
    override val name: String = "Class Hierarchy Analysis"

    override fun apply(module: SSAModule): ClassHierarchyAnalysisResults {
        val roots = findRoots(module)
        val workList = WorkList(roots)

        while (!workList.isEmpty()) {
            val currentFunction = workList.get()
            currentFunction.blocks.flatMap { it.body }.filterIsInstance<SSACallSite>().forEach {
                when (val callee = it.callee) {
                    is SSAFunction -> {
                        workList.add(callee)
                    }
                    is SSAVirtualFunction -> {

                    }
                }
            }
        }
    }

    private fun findRoots(module: SSAModule): List<SSAFunction> {
        return listOf(module.functions.find { it.name == "main" }
                ?: error("No main in module"))
    }
}