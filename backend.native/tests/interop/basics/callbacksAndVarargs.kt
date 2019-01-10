/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

import kotlin.test.*
import kotlinx.cinterop.*
import ccallbacksAndVarargs.*

fun main(args: Array<String>) {
    assertEquals(42, getX(staticCFunction { -> cValue<S> { x = 42 } }))
    applyCallback(cValue { x = 17 }, staticCFunction { it: CValue<S> ->
        assertEquals(17, it.useContents { x })
    })

    assertEquals(66, makeS(66, 111).useContents { x })
    assertEquals(E.ONE, makeE(1))
}
