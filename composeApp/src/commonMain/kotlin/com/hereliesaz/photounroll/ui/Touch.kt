package com.hereliesaz.photounroll.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Smallest pointer target worth designing for (dp). Used for hit radii and button heights. */
const val MIN_TARGET_DP = 48

/**
 * Zoom and pan of a canvas. Coordinates are "base" (the canvas at scale 1): a base point p is
 * drawn at `p * scale + offset`. Zoom never goes below 1, and the content can never be panned
 * off the canvas.
 */
@Stable
class ViewState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun toScreen(base: Offset): Offset = base * scale + offset
    fun toBase(screen: Offset): Offset = (screen - offset) / scale

    /** Zooms by [factor] keeping the content under [about] (screen coordinates) where it is. */
    fun zoomAbout(about: Offset, factor: Float, canvas: Size) {
        val next = (scale * factor).coerceIn(1f, MAX_SCALE)
        val k = next / scale
        offset = about - (about - offset) * k
        scale = next
        clamp(canvas)
    }

    fun panBy(delta: Offset, canvas: Size) {
        offset += delta
        clamp(canvas)
    }

    fun reset() { scale = 1f; offset = Offset.Zero }

    private fun clamp(canvas: Size) {
        offset = Offset(
            offset.x.coerceIn(min(0f, canvas.width * (1f - scale)), 0f),
            offset.y.coerceIn(min(0f, canvas.height * (1f - scale)), 0f),
        )
    }

    companion object { const val MAX_SCALE = 8f }
}

/** How a finger (or mouse) is currently engaging a handle; drives the loupe. */
class Engagement(val handle: Int, val finger: Offset, val touch: Boolean)

/**
 * The gesture model shared by every editor.
 *
 *  - One pointer that lands on a handle drags it, and the handle keeps the offset at which it was
 *    grabbed. There is no touch-slop delay, so it follows the fingertip from the first pixel.
 *  - One pointer elsewhere pans when zoomed in.
 *  - Two pointers zoom, pan and (if [onTwist] is given) rotate; a second finger cancels a handle drag.
 *  - Mouse wheel zooms about the cursor.
 *
 * [handles] returns handle positions in screen coordinates at the moment a pointer goes down.
 */
suspend fun PointerInputScope.editorGestures(
    view: ViewState,
    canvas: () -> Size,
    hitRadius: Float,
    handles: () -> List<Offset>,
    onGrab: (handle: Int) -> Unit = {},
    onMove: (handle: Int, target: Offset) -> Unit = { _, _ -> },
    onRelease: (handle: Int) -> Unit = {},
    onEngaged: (Engagement?) -> Unit = {},
    onTwist: (degrees: Float) -> Unit = {},
    onTwistEnd: () -> Unit = {},
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val touch = down.type != PointerType.Mouse
        val positions = handles()
        var active = positions.indices
            .minByOrNull { (positions[it] - down.position).getDistance() }
            ?.takeIf { (positions[it] - down.position).getDistance() <= hitRadius } ?: -1
        val grabOffset = if (active >= 0) positions[active] - down.position else Offset.Zero
        if (active >= 0) {
            onGrab(active)
            onEngaged(Engagement(active, down.position, touch))
            down.consume()
        }
        var twisted = false
        do {
            val event = awaitPointerEvent()
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2) {
                if (active >= 0) { onRelease(active); active = -1; onEngaged(null) }
                twisted = true
                val zoom = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (zoom != 1f) view.zoomAbout(centroid, zoom, canvas())
                view.panBy(event.calculatePan(), canvas())
                val turn = event.calculateRotation() // degrees, clockwise positive
                if (turn != 0f) onTwist(turn)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (pressed == 1) {
                val change = event.changes.first { it.pressed }
                if (active >= 0) {
                    onMove(active, change.position + grabOffset)
                    onEngaged(Engagement(active, change.position, touch))
                    change.consume()
                } else if (change.positionChanged() && view.scale > 1f) {
                    view.panBy(change.position - change.previousPosition, canvas())
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })
        if (active >= 0) onRelease(active)
        if (twisted) onTwistEnd()
        onEngaged(null)
    }
}

/** Mouse-wheel (and trackpad scroll) zoom about the cursor. Touch devices never send these. */
suspend fun PointerInputScope.wheelZoom(view: ViewState, canvas: () -> Size) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Scroll) {
                val change = event.changes.first()
                val dy = change.scrollDelta.y
                if (dy != 0f) {
                    view.zoomAbout(change.position, exp(-dy * 0.1f), canvas())
                    change.consume()
                }
            }
        }
    }
}

/** Letterboxed rectangle that fits [content] inside [canvas], optionally shrunk to leave a margin. */
fun fitRect(content: Size, canvas: Size, shrink: Float = 1f): Rect {
    val s = min(canvas.width / content.width, canvas.height / content.height) * shrink
    val w = content.width * s
    val h = content.height * s
    return Rect(Offset((canvas.width - w) / 2, (canvas.height - h) / 2), Size(w, h))
}

/** Draws [bitmap] into [frame] (base coordinates) through [view]. */
fun DrawScope.drawZoomed(bitmap: ImageBitmap, frame: Rect, view: ViewState) {
    val topLeft = view.toScreen(frame.topLeft)
    drawImage(
        bitmap,
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize((frame.width * view.scale).roundToInt(), (frame.height * view.scale).roundToInt()),
        filterQuality = if (view.scale > 1.5f) FilterQuality.High else FilterQuality.Medium,
    )
}

/**
 * A magnifier above (or, near the top edge, below) the fingertip, showing what the fingertip
 * hides: the photo around [target] at [mag]x, with a crosshair on the exact spot.
 * [frame] is the image's rectangle in base coordinates.
 */
fun DrawScope.drawLoupe(
    bitmap: ImageBitmap,
    frame: Rect,
    view: ViewState,
    target: Offset,
    finger: Offset,
    diameter: Float,
    mag: Float = 2.5f,
    ring: androidx.compose.ui.graphics.Color,
    back: androidx.compose.ui.graphics.Color,
) {
    val r = diameter / 2
    val cx = finger.x.coerceIn(r + 4f, size.width - r - 4f)
    var cy = finger.y - diameter * 0.95f
    if (cy < r + 4f) cy = finger.y + diameter * 0.95f
    cy = cy.coerceIn(r + 4f, size.height - r - 4f)
    val center = Offset(cx, cy)

    val imagePerScreen = bitmap.width / (frame.width * view.scale)
    val base = view.toBase(target)
    val ix = (base.x - frame.left) / frame.width * bitmap.width
    val iy = (base.y - frame.top) / frame.height * bitmap.height
    val srcSide = max(2f, diameter / mag * imagePerScreen)
    val disc = Path().apply { addOval(Rect(center, r)) }
    clipPath(disc) {
        drawCircle(back, r, center)
        drawImage(
            bitmap,
            srcOffset = IntOffset((ix - srcSide / 2).roundToInt(), (iy - srcSide / 2).roundToInt()),
            srcSize = IntSize(srcSide.roundToInt(), srcSide.roundToInt()),
            dstOffset = IntOffset((cx - r).roundToInt(), (cy - r).roundToInt()),
            dstSize = IntSize(diameter.roundToInt(), diameter.roundToInt()),
            filterQuality = FilterQuality.Low,
        )
        val arm = r * 0.22f
        drawLine(ring, Offset(cx - arm, cy), Offset(cx + arm, cy), 1.5f)
        drawLine(ring, Offset(cx, cy - arm), Offset(cx, cy + arm), 1.5f)
    }
    drawCircle(ring, r, center, style = Stroke(3f))
}

/** Rounds [deg] to the nearest multiple of [step] when within [tolerance] of it. Returns null if it should not snap. */
fun snapAngle(deg: Float, step: Float = 90f, tolerance: Float = 1.5f): Float? {
    val nearest = (deg / step).roundToInt() * step
    return if (kotlin.math.abs(deg - nearest) <= tolerance) nearest else null
}

/** Wraps an angle to [-180, 180). */
fun wrapDegrees(deg: Float): Float = ((deg + 180f) % 360f + 360f) % 360f - 180f
