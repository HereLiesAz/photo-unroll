package com.hereliesaz.photounroll.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Fits the strip to the viewer's height and scrolls it horizontally with fling. Pinch (or
 * double-tap, or the mouse wheel) zooms about the touch; once zoomed, drag pans vertically too.
 * When [wraps], the strip repeats end-to-end forever; otherwise scrolling stops at its edges.
 */
@Composable
fun StripViewer(bitmap: ImageBitmap, wraps: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val decay = rememberSplineBasedDecay<Float>()
    val x = remember(bitmap) { Animatable(0f) }
    var y by remember(bitmap) { mutableFloatStateOf(0f) }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var canvas by remember { mutableStateOf(Size.Zero) }

    /** Zoom about [at] (screen), keeping the strip point under it fixed; clamps vertical pan. */
    fun zoomAbout(at: Offset, factor: Float, scope: CoroutineScope) {
        val next = (zoom * factor).coerceIn(1f, ViewState.MAX_SCALE)
        val k = next / zoom
        scope.launch { x.snapTo(at.x - (at.x - x.value) * k) }
        y = at.y - (at.y - y) * k
        zoom = next
        y = y.coerceIn(canvas.height * (1f - zoom), 0f)
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { canvas = it.toSize() }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { at ->
                    val target = if (zoom > 1.5f) 1f else 2.5f
                    zoomAbout(at, target / zoom, scope)
                })
            }
            .pointerInput(bitmap, wraps) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    scope.launch { x.stop() }
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var multi = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            multi = true
                            val z = event.calculateZoom()
                            val c = event.calculateCentroid(useCurrent = true)
                            if (z != 1f) zoomAbout(c, z, scope)
                            val pan = event.calculatePan()
                            scope.launch { x.snapTo(x.value + pan.x) }
                            y = (y + pan.y).coerceIn(canvas.height * (1f - zoom), 0f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressed == 1) {
                            val c = event.changes.first { it.pressed }
                            tracker.addPosition(c.uptimeMillis, c.position)
                            val d = c.position - c.previousPosition
                            if (c.positionChanged()) {
                                scope.launch { x.snapTo(x.value + d.x) }
                                if (zoom > 1f) y = (y + d.y).coerceIn(canvas.height * (1f - zoom), 0f)
                                c.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (!multi) {
                        val vx = tracker.calculateVelocity().x
                        scope.launch { x.animateDecay(vx, decay) }
                    }
                }
            }
            .pointerInput(bitmap) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val c = event.changes.first()
                            if (c.scrollDelta.y != 0f) {
                                zoomAbout(c.position, exp(-c.scrollDelta.y * 0.1f), scope)
                                c.consume()
                            }
                        }
                    }
                }
            },
    ) {
        val fit = size.height / bitmap.height
        val tileW = bitmap.width * fit * zoom
        val tileH = size.height * zoom
        val q = if (zoom > 1.5f) FilterQuality.High else FilterQuality.Medium
        if (wraps) {
            // Draw enough copies to cover the viewport, starting one tile left of the phase.
            val phase = x.value - floor(x.value / tileW) * tileW
            val first = phase - tileW
            val copies = ceil((size.width - first) / tileW).toInt()
            for (i in 0 until copies) {
                drawImage(
                    bitmap,
                    dstOffset = IntOffset((first + i * tileW).roundToInt(), y.roundToInt()),
                    dstSize = IntSize(ceil(tileW).toInt() + 1, tileH.roundToInt()),
                    filterQuality = q,
                )
            }
        } else {
            val min = (size.width - tileW).coerceAtMost(0f)
            x.updateBounds(min, 0f)
            val left = if (tileW < size.width) (size.width - tileW) / 2 else x.value
            drawImage(
                bitmap,
                dstOffset = IntOffset(left.roundToInt(), y.roundToInt()),
                dstSize = IntSize(tileW.roundToInt(), tileH.roundToInt()),
                filterQuality = q,
            )
        }
    }
}
