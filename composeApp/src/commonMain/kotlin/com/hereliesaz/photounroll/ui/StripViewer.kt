package com.hereliesaz.photounroll.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Fits the strip to the viewer's height and scrolls it horizontally with fling.
 * When [wraps], the strip repeats end-to-end forever; otherwise scrolling stops at its edges.
 */
@Composable
fun StripViewer(bitmap: ImageBitmap, wraps: Boolean, modifier: Modifier = Modifier) {
    val offset = remember(bitmap) { Animatable(0f) }
    val decay = rememberSplineBasedDecay<Float>()
    val scope = rememberCoroutineScope()

    Canvas(
        modifier.pointerInput(bitmap, wraps) {
            val tracker = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onHorizontalDrag = { change, dx ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offset.snapTo(offset.value + dx) }
                },
                onDragEnd = {
                    val v = tracker.calculateVelocity().x
                    scope.launch { offset.animateDecay(v, decay) }
                },
            )
        },
    ) {
        val scale = size.height / bitmap.height
        val tileW = bitmap.width * scale
        val h = size.height.roundToInt()
        if (wraps) {
            // Draw enough copies to cover the viewport, starting one tile left of the phase.
            val phase = offset.value - floor(offset.value / tileW) * tileW
            val first = phase - tileW
            val copies = ceil((size.width - first) / tileW).toInt()
            for (i in 0 until copies) {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset((first + i * tileW).roundToInt(), 0),
                    dstSize = IntSize(ceil(tileW).toInt() + 1, h),
                    filterQuality = FilterQuality.Medium,
                )
            }
        } else {
            val min = (size.width - tileW).coerceAtMost(0f)
            offset.updateBounds(min, 0f)
            val x = if (tileW < size.width) (size.width - tileW) / 2 else offset.value
            drawImage(
                bitmap,
                dstOffset = IntOffset(x.roundToInt(), 0),
                dstSize = IntSize(tileW.roundToInt(), h),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}
