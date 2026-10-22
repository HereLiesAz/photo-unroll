package com.hereliesaz.photounroll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.photounroll.imaging.CylinderGuides
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Draggable points on the guide overlay. */
private enum class Handle { Left, Right, TopRim, TopFront, BottomRim, BottomFront }

/**
 * Shows a photo with the cylinder guides over it. Drag the side lines to the silhouette,
 * the hollow handles to where each rim meets the silhouette, and the solid handles to
 * the rim's nearest point.
 */
@Composable
fun GuideEditor(
    bitmap: ImageBitmap,
    guides: CylinderGuides,
    onGuidesChange: (CylinderGuides) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(guides)
    val onChange by rememberUpdatedState(onGuidesChange)
    var active by remember { mutableStateOf<Handle?>(null) }
    var frame by remember { mutableStateOf(Rect.Zero) }

    Canvas(
        modifier.pointerInput(bitmap) {
            val grab = 28.dp.toPx()
            detectDragGestures(
                onDragStart = { pos ->
                    active = handles(latest, frame)
                        .minByOrNull { (_, p) -> (p - pos).getDistance() }
                        ?.takeIf { (_, p) -> (p - pos).getDistance() < grab }
                        ?.first
                },
                onDragEnd = { active = null; onChange(latest.normalized()) },
                onDragCancel = { active = null },
                onDrag = { change, d ->
                    val h = active ?: return@detectDragGestures
                    change.consume()
                    val dx = d.x / frame.width
                    val dy = d.y / frame.height
                    val g = latest
                    onChange(
                        when (h) {
                            Handle.Left -> g.copy(left = (g.left + dx).coerceIn(0f, 1f))
                            Handle.Right -> g.copy(right = (g.right + dx).coerceIn(0f, 1f))
                            Handle.TopRim -> g.copy(topY = (g.topY + dy).coerceIn(0f, 1f))
                            Handle.TopFront -> g.copy(topBulge = g.topBulge + dy)
                            Handle.BottomRim -> g.copy(bottomY = (g.bottomY + dy).coerceIn(0f, 1f))
                            Handle.BottomFront -> g.copy(bottomBulge = g.bottomBulge + dy)
                        },
                    )
                },
            )
        },
    ) {
        frame = fit(bitmap, size)
        drawImage(
            bitmap,
            dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
            dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        drawGuides(guides, frame, active)
    }
}

/** Letterboxed image rectangle inside the canvas. */
private fun fit(bitmap: ImageBitmap, size: Size): Rect {
    val s = min(size.width / bitmap.width, size.height / bitmap.height)
    val w = bitmap.width * s
    val h = bitmap.height * s
    return Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
}

private fun handles(g: CylinderGuides, f: Rect): List<Pair<Handle, Offset>> {
    fun at(x: Float, y: Float) = Offset(f.left + x * f.width, f.top + y * f.height)
    val cx = (g.left + g.right) / 2
    val midY = (g.topY + g.bottomY) / 2
    return listOf(
        Handle.Left to at(g.left, midY),
        Handle.Right to at(g.right, midY),
        Handle.TopRim to at(g.left, g.topY),
        Handle.TopFront to at(cx, g.topY + g.topBulge),
        Handle.BottomRim to at(g.left, g.bottomY),
        Handle.BottomFront to at(cx, g.bottomY + g.bottomBulge),
    )
}

private fun DrawScope.drawGuides(g: CylinderGuides, f: Rect, active: Handle?) {
    val line = Ink.Bone
    val dim = Ink.Bone.copy(alpha = 0.45f)
    val thin = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    fun x(v: Float) = f.left + v * f.width
    fun y(v: Float) = f.top + v * f.height

    drawLine(line, Offset(x(g.left), f.top), Offset(x(g.left), f.bottom), thin)
    drawLine(line, Offset(x(g.right), f.top), Offset(x(g.right), f.bottom), thin)

    for ((rim, bulge) in listOf(g.topY to g.topBulge, g.bottomY to g.bottomBulge)) {
        val b = abs(bulge) * f.height
        val oval = Rect(x(g.left), y(rim) - b, x(g.right), y(rim) + b)
        drawOval(line, oval.topLeft, oval.size, style = Stroke(thin * 1.5f))
        drawLine(dim, Offset(x(g.left), y(rim)), Offset(x(g.right), y(rim)), thin, pathEffect = dash)
    }

    val r = 7.dp.toPx()
    for ((h, p) in handles(g, f)) {
        val hollow = h == Handle.TopRim || h == Handle.BottomRim
        val radius = if (h == active) r * 1.5f else r
        drawCircle(Ink.Black, radius + thin * 2, p)
        if (hollow) drawCircle(line, radius, p, style = Stroke(thin * 2)) else drawCircle(line, radius, p)
    }
}
