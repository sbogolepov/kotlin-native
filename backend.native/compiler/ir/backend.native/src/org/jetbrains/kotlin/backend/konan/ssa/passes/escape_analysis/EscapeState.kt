package org.jetbrains.kotlin.backend.konan.ssa.passes.escape_analysis

enum class EscapeState {
    UNKNOWN, LOCAL, ESCAPE
}