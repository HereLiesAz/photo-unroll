package com.hereliesaz.photounroll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.hereliesaz.photounroll.imaging.NPoint

/**
 * Rotation by hand. Two fingers twist the photo (it snaps to square at 0/±90/180°, with a tick),
 * pinch zooms to check the level against the fixed grid. The photo turns on the GPU; the real
 * resample happens once, when [onCommit] fires at the end of the gesture.
 */
@Composable
fun RotatePreview(
    original: ImageBitmap,
    angleDeg: Float,
    onAngle: (Float) -> Unit,
    onCommit: () -> Unit,
    viewKey: Any,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = remember(viewKey) { ViewState() }
    val angle by rememberUpdatedState(angleDeg)
    val setAngle by rememberUpdatedState(onAngle)
    val commit by rememberUpdatedState(onCommit)
    val haptic by rememberUpdatedState(onHaptic)
    var canvas by remember { mutableStateOf(Size.Zero) }
    // Where the twist is, unsnapped, so a snap does not trap the fingers.
    var raw by remember(viewKey) { mutableStateOf<Float?>(null) }

    Box(
        modifier
            // The turned photo's corners swing past its box; they must not paint over the tabs.
            .clipToBounds()
            .onSizeChanged { canvas = it.toSize() }
            .pointerInput(viewKey) {
                editorGestures(
                    view = view,
                    canvas = { canvas },
                    hitRadius = 0f,
                    handles = { emptyList() },
                    onTwist = { deg ->
                        val next = wrapDegrees((raw ?: angle) + deg)
                        raw = next
                        val snapped = snapAngle(next)
                        val shown = snapped ?: next
                        if (snapped != null && shown != angle) haptic()
                        setAngle(shown)
                    },
                    onTwistEnd = { raw = null; commit() },
                )
            }
            .pointerInput(viewKey) { wheelZoom(view) { canvas } },
    ) {
        Image(
            original, null,
            Modifier.fillMaxSize().graphicsLayer {
                transformOrigin = TransformOrigin(0f, 0f)
                // Screen = offset + scale * (rotate about the canvas center); the grid below is fixed.
                translationX = view.offset.x
                translationY = view.offset.y
                scaleX = view.scale
                scaleY = view.scale
            }.graphicsLayer { rotationZ = angleDeg },
            contentScale = ContentScale.Fit,
        )
        Canvas(Modifier.fillMaxSize()) {
            val line = Ink.Bone.copy(alpha = 0.35f)
            val n = 6
            for (i in 1 until n) {
                val x = size.width * i / n
                val y = size.height * i / n
                drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
        }
    }
}

/**
 * Perspective picker: drag the four corners onto anything that should be rectangular (a label's
 * edges, a doorframe). That quadrilateral is stretched to fill the frame. [quad] is clockwise
 * from top-left in normalized coordinates of [bitmap]. Corners snap to the photo's own edges and
 * corners (with a tick); pinch to zoom for precision, and a loupe shows what the fingertip covers.
 */
@Composable
fun WarpEditor(
    bitmap: ImageBitmap,
    quad: List<NPoint>,
    onQuadChange: (List<NPoint>) -> Unit,
    viewKey: Any,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = remember(viewKey) { ViewState() }
    val committed by rememberUpdatedState(quad)
    val onCommit by rememberUpdatedState(onQuadChange)
    val haptic by rememberUpdatedState(onHaptic)
    var live by remember(quad) { mutableStateOf(quad) }
    var canvas by remember { mutableStateOf(Size.Zero) }
    var engaged by remember { mutableStateOf<Engagement?>(null) }
    // The photo sits at 80% so corners can be pulled outside it.
    val frame = remember(bitmap.width, bitmap.height, canvas) {
        if (canvas == Size.Zero) Rect.Zero else fitRect(Size(bitmap.width.toFloat(), bitmap.height.toFloat()), canvas, 0.8f)
    }
    val frameNow by rememberUpdatedState(frame)
    val liveNow by rememberUpdatedState(live)

    fun toBase(p: NPoint, f: Rect) = Offset(f.left + p.x * f.width, f.top + p.y * f.height)

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { canvas = it.toSize() }
            .pointerInput(viewKey) {
                val snapPx = 12.dp.toPx()
                var snappedKey = Int.MIN_VALUE
                editorGestures(
                    view = view,
                    canvas = { canvas },
                    hitRadius = 40.dp.toPx(),
                    handles = { liveNow.map { view.toScreen(toBase(it, frameNow)) } },
                    onGrab = { live = committed; haptic() },
                    onEngaged = { engaged = it },
                    onMove = { i, target ->
                        val f = frameNow
                        val b = view.toBase(target)
                        var nx = ((b.x - f.left) / f.width).coerceIn(-0.5f, 1.5f)
                        var ny = ((b.y - f.top) / f.height).coerceIn(-0.5f, 1.5f)
                        // Snap to the photo's edges (0 or 1) when close on screen.
                        val tol = snapPx / (f.width * view.scale)
                        val tolY = snapPx / (f.height * view.scale)
                        var key = 0
                        for (edge in floatArrayOf(0f, 1f)) {
                            if (kotlin.math.abs(nx - edge) < tol) { nx = edge; key = key or (if (edge == 0f) 1 else 2) }
                            if (kotlin.math.abs(ny - edge) < tolY) { ny = edge; key = key or (if (edge == 0f) 4 else 8) }
                        }
                        if (key != 0 && key != snappedKey) haptic()
                        snappedKey = key
                        live = live.toMutableList().also { it[i] = NPoint(nx, ny) }
                    },
                    onRelease = { snappedKey = Int.MIN_VALUE; onCommit(liveNow) },
                )
            }
            .pointerInput(viewKey) { wheelZoom(view) { canvas } },
    ) {
        if (frame == Rect.Zero) return@Canvas
        drawZoomed(bitmap, frame, view)
        val pts = live.map { view.toScreen(toBase(it, frame)) }
        val outline = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        val thin = 1.dp.toPx()
        drawPath(outline, Ink.Bone, style = Stroke(thin * 1.5f))
        // Thirds inside the quad hint at how the warp will straighten things.
        for (i in 1..2) {
            val t = i / 3f
            drawLine(Ink.Bone.copy(alpha = 0.4f), lerp(pts[0], pts[1], t), lerp(pts[3], pts[2], t), thin)
            drawLine(Ink.Bone.copy(alpha = 0.4f), lerp(pts[0], pts[3], t), lerp(pts[1], pts[2], t), thin)
        }
        val r = 11.dp.toPx()
        pts.forEachIndexed { i, p ->
            val big = i == engaged?.handle
            drawCircle(Ink.Black, (if (big) r * 1.4f else r) + thin * 2, p)
            drawCircle(Ink.Bone, if (big) r * 1.4f else r, p)
        }
        engaged?.takeIf { it.touch }?.let { e ->
            drawLoupe(bitmap, frame, view, pts[e.handle], e.finger, 120.dp.toPx(), ring = Ink.Bone, back = Ink.Black)
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

/** A photo you can pinch, pan and wheel-zoom, for views with nothing to edit. */
@Composable
fun ZoomableImage(bitmap: ImageBitmap, viewKey: Any, modifier: Modifier = Modifier) {
    val view = remember(viewKey) { ViewState() }
    var canvas by remember { mutableStateOf(Size.Zero) }
    val frame = remember(bitmap.width, bitmap.height, canvas) {
        if (canvas == Size.Zero) Rect.Zero else fitRect(Size(bitmap.width.toFloat(), bitmap.height.toFloat()), canvas)
    }
    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { canvas = it.toSize() }
            .pointerInput(viewKey) {
                editorGestures(view = view, canvas = { canvas }, hitRadius = 0f, handles = { emptyList() })
            }
            .pointerInput(viewKey) { wheelZoom(view) { canvas } },
    ) {
        if (frame != Rect.Zero) drawZoomed(bitmap, frame, view)
    }
}
