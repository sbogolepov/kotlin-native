package org.jetbrains.kotlin.backend.konan.ssa.passes.ConnectionGraph

sealed class CGNode {
    class LocalReference : CGNode()

    class GlobalReference : CGNode()

    class FieldReference : CGNode()

    class ActualReference : CGNode()
}

