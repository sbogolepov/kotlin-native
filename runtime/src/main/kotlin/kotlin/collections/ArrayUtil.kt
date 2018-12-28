/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package kotlin.collections

import kotlin.native.internal.PointsTo

/**
 * Returns an array of objects of the given type with the given [size], initialized with _uninitialized_ values.
 * Attempts to read _uninitialized_ values from this array work in implementation-dependent manner,
 * either throwing exception or returning some kind of implementation-specific default value.
 */
@PublishedApi
internal fun <E> arrayOfUninitializedElements(size: Int): Array<E> {
    // TODO: special case for size == 0?
    @Suppress("TYPE_PARAMETER_AS_REIFIED")
    return Array<E>(size)
}


/**
 * Returns a new array which is a copy of the original array with new elements filled with null values.
 */
internal fun <E> Array<E>.copyOfNulls(newSize: Int): Array<E?>  = copyOfNulls(0, newSize)

internal fun <E> Array<E>.copyOfNulls(fromIndex: Int, toIndex: Int): Array<E?> {
    val newSize = toIndex - fromIndex
    if (newSize < 0) {
        throw IllegalArgumentException("$fromIndex > $toIndex")
    }
    val result = @Suppress("TYPE_PARAMETER_AS_REIFIED") arrayOfNulls<E>(newSize)
    this.copyInto(result, 0, fromIndex, toIndex.coerceAtMost(size))
    return result
}

/**
 * Copies elements of the [collection] into the given [array].
 * If the array is too small, allocates a new one of collection.size size.
 * @return [array] with the elements copied from the collection.
 */
internal fun <E, T> collectionToArray(collection: Collection<E>, array: Array<T>): Array<T> {
    val toArray = if (collection.size > array.size) {
        arrayOfUninitializedElements<T>(collection.size)
    } else {
        array
    }
    var i = 0
    for (v in collection) {
        @Suppress("UNCHECKED_CAST")
        toArray[i] = v as T
        i++
    }
    return toArray
}

/**
 * Creates an array of collection.size size and copies elements of the [collection] into it.
 * @return [array] with the elements copied from the collection.
 */
internal fun <E> collectionToArray(collection: Collection<E>): Array<E>
        = collectionToArray(collection, arrayOfUninitializedElements(collection.size))


/**
 * Resets an array element at a specified index to some implementation-specific _uninitialized_ value.
 * In particular, references stored in this element are released and become available for garbage collection.
 * Attempts to read _uninitialized_ value work in implementation-dependent manner,
 * either throwing exception or returning some kind of implementation-specific default value.
 */
internal fun <E> Array<E>.resetAt(index: Int) {
    (@Suppress("UNCHECKED_CAST")(this as Array<Any?>))[index] = null
}

@SymbolName("Kotlin_Array_fillImpl")
@PointsTo(0b01000, 0, 0, 0b00001) // <array> points to <value>, <value> points to <array>.
external private fun fillImpl(array: Array<Any?>, fromIndex: Int, toIndex: Int, value: Any?)

@SymbolName("Kotlin_IntArray_fillImpl")
external private fun fillImpl(array: IntArray, fromIndex: Int, toIndex: Int, value: Int)

/**
 * Resets a range of array elements at a specified [fromIndex] (inclusive) to [toIndex] (exclusive) range of indices
 * to some implementation-specific _uninitialized_ value.
 * In particular, references stored in these elements are released and become available for garbage collection.
 * Attempts to read _uninitialized_ values work in implementation-dependent manner,
 * either throwing exception or returning some kind of implementation-specific default value.
 */
internal fun <E> Array<E>.resetRange(fromIndex: Int, toIndex: Int) {
    fillImpl(@Suppress("UNCHECKED_CAST") (this as Array<Any?>), fromIndex, toIndex, null)
}

internal fun IntArray.fill(fromIndex: Int, toIndex: Int, value: Int) {
    fillImpl(this, fromIndex, toIndex, value)
}

@SymbolName("Kotlin_Array_copyImpl")
@PointsTo(0b000100, 0, 0b000001) // <array> points to <destination>, <destination> points to <array>.
internal external fun arrayCopy(array: Array<Any?>, fromIndex: Int, destination: Array<Any?>, toIndex: Int, count: Int)

@SymbolName("Kotlin_ByteArray_copyImpl")
internal external fun arrayCopy(array: ByteArray, fromIndex: Int, destination: ByteArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_ShortArray_copyImpl")
internal external fun arrayCopy(array: ShortArray, fromIndex: Int, destination: ShortArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_CharArray_copyImpl")
internal external fun arrayCopy(array: CharArray, fromIndex: Int, destination: CharArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_IntArray_copyImpl")
internal external fun arrayCopy(array: IntArray, fromIndex: Int, destination: IntArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_LongArray_copyImpl")
internal external fun arrayCopy(array: LongArray, fromIndex: Int, destination: LongArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_FloatArray_copyImpl")
internal external fun arrayCopy(array: FloatArray, fromIndex: Int, destination: FloatArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_DoubleArray_copyImpl")
internal external fun arrayCopy(array: DoubleArray, fromIndex: Int, destination: DoubleArray, toIndex: Int, count: Int)

@SymbolName("Kotlin_BooleanArray_copyImpl")
internal external fun arrayCopy(array: BooleanArray, fromIndex: Int, destination: BooleanArray, toIndex: Int, count: Int)


// TODO: Remove references to copyRangeTo from IR backend

/**
 * Copies a range of array elements at a specified [fromIndex] (inclusive) to [toIndex] (exclusive) range of indices
 * to another [destination] array starting at [destinationIndex].
 */
@PublishedApi
internal fun <E> Array<out E>.copyRangeTo(
        destination: Array<in E>, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(@Suppress("UNCHECKED_CAST") (this as Array<Any?>), fromIndex,
             @Suppress("UNCHECKED_CAST") (destination as Array<Any?>),
             destinationIndex, toIndex - fromIndex)
}

internal fun ByteArray.copyRangeTo(destination: ByteArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun ShortArray.copyRangeTo(destination: ShortArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun CharArray.copyRangeTo(destination: CharArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun IntArray.copyRangeTo(destination: IntArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun LongArray.copyRangeTo(destination: LongArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun FloatArray.copyRangeTo(destination: FloatArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun DoubleArray.copyRangeTo(destination: DoubleArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

internal fun BooleanArray.copyRangeTo(destination: BooleanArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    arrayCopy(this, fromIndex, destination, destinationIndex, toIndex - fromIndex)
}

@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
internal fun UByteArray.copyRangeTo(destination: UByteArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    copyInto(destination, destinationIndex, fromIndex, toIndex)
}

@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
internal fun UShortArray.copyRangeTo(destination: UShortArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    copyInto(destination, destinationIndex, fromIndex, toIndex)
}

@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
internal fun UIntArray.copyRangeTo(destination: UIntArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    copyInto(destination, destinationIndex, fromIndex, toIndex)
}

@SinceKotlin("1.3")
@ExperimentalUnsignedTypes
internal fun ULongArray.copyRangeTo(destination: ULongArray, fromIndex: Int, toIndex: Int, destinationIndex: Int = 0) {
    copyInto(destination, destinationIndex, fromIndex, toIndex)
}

internal fun <E> Collection<E>.collectionToString(): String {
    val sb = StringBuilder(2 + size * 3)
    sb.append("[")
    var i = 0
    val it = iterator()
    while (it.hasNext()) {
        if (i > 0) sb.append(", ")
        val next = it.next()
        if (next == this) sb.append("(this Collection)") else sb.append(next)
        i++
    }
    sb.append("]")
    return sb.toString()
}
