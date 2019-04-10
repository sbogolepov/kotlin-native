package org.jetbrains.kotlin.backend.konan.ssa.passes

import org.jetbrains.kotlin.backend.konan.ssa.SSAFunction
import org.jetbrains.kotlin.backend.konan.ssa.SSAModule
import org.jetbrains.kotlin.backend.konan.ssa.WorkList

class ClassHierarchyAnalysisResults()

class ClassHierarchyAnalysis : ModulePass<ClassHierarchyAnalysisResults> {
    override val name: String = "Class Hierarchy Analysis"

    override fun apply(module: SSAModule): ClassHierarchyAnalysisResults {
        val roots = findRoots(module)
        val workList = WorkList(roots)

        while (!workList.isEmpty()) {
            val currentFunction = workList.get()
        }
    }

    private fun findRoots(module: SSAModule): List<SSAFunction> {
        return listOf(module.functions.find { it.name == "main" }
                ?: error("No main in module"))
    }
}