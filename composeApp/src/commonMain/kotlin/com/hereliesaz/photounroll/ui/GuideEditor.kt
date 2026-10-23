package com.hereliesaz.photounroll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.hereliesaz.photounroll.imaging.CylinderGuides
import kotlin.math.abs

/** Draggable points on the guide overlay. */
private enum class Handle { Left, Right, TopRim, TopFront, BottomRim, BottomFront }

/**
 * Shows a photo with the cylinder guides over it. Drag the side lines to the silhouette, the
 * hollow handles to where each rim meets the silhouette, and the solid handles to the rim's
 * nearest point. Pinch to zoom, two fingers to pan; while a handle is held a loupe shows what
 * the fingertip covers. [viewKey] resets zoom when the photo changes.
 */
@Composable
fun GuideEditor(
    bitmap: ImageBitmap,
    guides: CylinderGuides,
    onGuidesChange: (CylinderGuides) -> Unit,
    viewKey: Any,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = remember(viewKey) { ViewState() }
    val latest by rememberUpdatedState(guides)
    val onChange by rememberUpdatedState(onGuidesChange)
    val haptic by rememberUpdatedState(onHaptic)
    var canvas by remember { mutableStateOf(Size.Zero) }
    var engaged by remember { mutableStateOf<Engagement?>(null) }
    val frame = remember(bitmap.width, bitmap.height, canvas) {
        if (canvas == Size.Zero) Rect.Zero else fitRect(Size(bitmap.width.toFloat(), bitmap.height.toFloat()), canvas)
    }
    // The gesture block outlives recompositions; it must see the current frame, not the first one.
    val frameNow by rememberUpdatedState(frame)

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { canvas = it.toSize() }
            .pointerInput(viewKey) {
                editorGestures(
                    view = view,
                    canvas = { canvas },
                    hitRadius = 40.dp.toPx(),
                    handles = { handles(latest, frameNow).map { view.toScreen(it.second) } },
                    onGrab = { haptic() },
                    onEngaged = { engaged = it },
                    onMove = { i, target ->
                        val b = view.toBase(target)
                        val f = frameNow
                        val nx = ((b.x - f.left) / f.width).coerceIn(0f, 1f)
                        val ny = ((b.y - f.top) / f.height).coerceIn(0f, 1f)
                        val g = latest
                        onChange(
                            when (Handle.entries[i]) {
                                Handle.Left -> g.copy(left = nx)
                                Handle.Right -> g.copy(right = nx)
                                Handle.TopRim -> g.copy(topY = ny)
                                Handle.TopFront -> g.copy(topBulge = ny - g.topY)
                                Handle.BottomRim -> g.copy(bottomY = ny)
                                Handle.BottomFront -> g.copy(bottomBulge = ny - g.bottomY)
                            },
                        )
                    },
                    onRelease = { onChange(latest.normalized()) },
                )
            }
            .pointerInput(viewKey) { wheelZoom(view) { canvas } },
    ) {
        if (frame == Rect.Zero) return@Canvas
        drawZoomed(bitmap, frame, view)
        drawGuides(guides, frame, view, engaged?.handle)
        engaged?.takeIf { it.touch }?.let { e ->
            val target = view.toScreen(handles(guides, frame)[e.handle].second)
            drawLoupe(bitmap, frame, view, target, e.finger, 120.dp.toPx(), ring = Ink.Bone, back = Ink.Black)
        }
    }
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

private fun DrawScope.drawGuides(g: CylinderGuides, f: Rect, view: ViewState, active: Int?) {
    val line = Ink.Bone
    val dim = Ink.Bone.copy(alpha = 0.45f)
    val thin = 1.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    fun pt(x: Float, y: Float) = view.toScreen(Offset(f.left + x * f.width, f.top + y * f.height))
    val sw = f.width * view.scale
    val sh = f.height * view.scale

    drawLine(line, pt(g.left, 0f), pt(g.left, 1f), thin)
    drawLine(line, pt(g.right, 0f), pt(g.right, 1f), thin)

    for ((rim, bulge) in listOf(g.topY to g.topBulge, g.bottomY to g.bottomBulge)) {
        val tl = pt(g.left, rim)
        val ry = abs(bulge) * sh
        drawOval(line, Offset(tl.x, tl.y - ry), Size((g.right - g.left) * sw, 2 * ry), style = Stroke(thin * 1.5f))
        drawLine(dim, pt(g.left, rim), pt(g.right, rim), thin, pathEffect = dash)
    }

    // Large visible handles: the grab area is 40 dp, the mark is sized so a fingertip can still see it.
    val r = 10.dp.toPx()
    for ((i, hp) in handles(g, f).withIndex()) {
        val (h, base) = hp
        val p = view.toScreen(base)
        val hollow = h == Handle.TopRim || h == Handle.BottomRim
        val radius = if (i == active) r * 1.4f else r
        drawCircle(Ink.Black, radius + thin * 2, p)
        if (hollow) drawCircle(line, radius, p, style = Stroke(thin * 2.5f)) else drawCircle(line, radius, p)
    }
}
