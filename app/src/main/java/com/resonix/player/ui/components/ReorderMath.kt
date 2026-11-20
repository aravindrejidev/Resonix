package com.resonix.player.ui.components

import kotlin.math.roundToInt

/**
 * The arithmetic of dragging a row to another place in a list, kept apart from the screen code so it can be
 * tested on its own (no Compose in here).
 */
object ReorderMath {

    /** The index the dragged row [from] would take once it has been pulled [offsetPx] away from its place. */
    fun targetIndex(from: Int, offsetPx: Float, pitchPx: Float, count: Int): Int {
        if (count <= 0 || pitchPx <= 0f) return from.coerceIn(0, maxOf(count - 1, 0))
        return (from + (offsetPx / pitchPx).roundToInt()).coerceIn(0, count - 1)
    }

    /**
     * How far row [index] is pushed aside, in pixels, while row [from] is dragged towards [target]: the rows it
     * passes make room by one row's pitch, in the opposite direction.
     */
    fun shiftFor(index: Int, from: Int, target: Int, pitchPx: Float): Float = when {
        index == from -> 0f
        from < target && index in (from + 1)..target -> -pitchPx
        from > target && index in target until from -> pitchPx
        else -> 0f
    }

    /** Where the item that was at [index] is after the item at [from] has been moved to [to] (see [move]). */
    fun indexAfterMove(index: Int, from: Int, to: Int): Int = when {
        index == from -> to
        index > from && index <= to -> index - 1
        index < from && index >= to -> index + 1
        else -> index
    }

    /** The list with the item at [from] taken out and put in at [to]. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from == to || from !in list.indices || to !in list.indices) return list
        val result = list.toMutableList()
        val item = result.removeAt(from)
        result.add(to, item)
        return result
    }
}
