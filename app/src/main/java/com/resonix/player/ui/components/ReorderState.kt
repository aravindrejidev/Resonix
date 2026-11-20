package com.resonix.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.theme.LocalNeoColors

/**
 * Drag-to-reorder for a list of equally tall rows: which row is being dragged, how far, and (through
 * [ReorderMath]) where it would land and which rows make room. The change is reported once, when the finger lets go.
 */
@Stable
class ReorderState(private val onFinished: (from: Int, to: Int) -> Unit) {
    var draggingIndex by mutableIntStateOf(-1)
        private set
    var offsetPx by mutableFloatStateOf(0f)
        private set
    private var pitchPx = 0f
    private var count = 0

    /** The place the dragged row would take if it were dropped now; -1 when nothing is being dragged. */
    val target: Int
        get() = if (draggingIndex < 0) -1 else ReorderMath.targetIndex(draggingIndex, offsetPx, pitchPx, count)

    fun shiftFor(index: Int): Float =
        if (draggingIndex < 0) 0f else ReorderMath.shiftFor(index, draggingIndex, target, pitchPx)

    fun start(index: Int, pitch: Float, itemCount: Int) {
        draggingIndex = index
        offsetPx = 0f
        pitchPx = pitch
        count = itemCount
    }

    fun dragBy(dy: Float) {
        if (draggingIndex >= 0) offsetPx += dy
    }

    fun finish() {
        val from = draggingIndex
        val to = target
        draggingIndex = -1
        offsetPx = 0f
        if (from >= 0 && to >= 0 && to != from) onFinished(from, to)
    }

    fun cancel() {
        draggingIndex = -1
        offsetPx = 0f
    }
}

@Composable
fun rememberReorderState(onMove: (from: Int, to: Int) -> Unit): ReorderState {
    val latest by rememberUpdatedState(onMove)
    return remember { ReorderState { from, to -> latest(from, to) } }
}

/** The row follows the finger while it is dragged; the rows it passes slide aside to make room. */
@Composable
fun Modifier.reorderRow(state: ReorderState, index: Int): Modifier {
    val dragging = state.draggingIndex == index
    val shift by animateFloatAsState(targetValue = if (dragging) 0f else state.shiftFor(index), label = "reorderShift")
    return this
        .zIndex(if (dragging) 1f else 0f)
        .graphicsLayer { translationY = if (dragging) state.offsetPx else shift }
}

/**
 * The grip at the end of a row: dragging it moves the row. [rowHeightPx] is asked when the drag starts, so the
 * row height can be measured (onSizeChanged) without this composable recomposing for it.
 */
@Composable
fun ReorderHandle(
    state: ReorderState,
    index: Int,
    count: Int,
    rowHeightPx: () -> Int,
    spacingPx: Float,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val currentIndex by rememberUpdatedState(index)
    val currentCount by rememberUpdatedState(count)
    Box(
        modifier = modifier
            .size(38.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { state.start(currentIndex, rowHeightPx() + spacingPx, currentCount) },
                    onDrag = { change, amount ->
                        change.consume()
                        state.dragBy(amount.y)
                    },
                    onDragEnd = { state.finish() },
                    onDragCancel = { state.cancel() },
                )
            }
            .semantics { contentDescription = "Drag to reorder" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NeoIcons.DragHandle,
            contentDescription = null,
            tint = neo.textTertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}
