package com.hereliesaz.photounroll.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.hereliesaz.photounroll.imaging.NPoint
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Live rotation preview: the untouched photo turned by [angleDeg] on the GPU, under a fixed
 * grid to level against. The real resample happens once the angle is committed.
 */
@Composable
fun RotatePreview(original: ImageBitmap, angleDeg: Float, modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(
            original, null,
            Modifier.fillMaxSize().graphicsLayer { rotationZ = angleDeg },
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
 * Perspective picker: drag the four corners onto anything that should be rectangular
 * (a label's edges, a doorframe). That quadrilateral is stretched to fill the frame.
 * [quad] is clockwise from top-left in normalized coordinates of [bitmap].
 */
@Composable
fun WarpEditor(
    bitmap: ImageBitmap,
    quad: List<NPoint>,
    onQuadChange: (List<NPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val committed by rememberUpdatedState(quad)
    val onCommit by rememberUpdatedState(onQuadChange)
    var live by remember(quad) { mutableStateOf(quad) }
    var active by remember { mutableStateOf(-1) }
    var frame by remember { mutableStateOf(Rect.Zero) }

    fun toScreen(p: NPoint) = Offset(frame.left + p.x * frame.width, frame.top + p.y * frame.height)

    Canvas(
        modifier.pointerInput(bitmap) {
            val grab = 32.dp.toPx()
            detectDragGestures(
                onDragStart = { pos ->
                    live = committed
                    active = live.indices.minByOrNull { (toScreen(live[it]) - pos).getDistance() }
                        ?.takeIf { (toScreen(live[it]) - pos).getDistance() < grab } ?: -1
                },
                onDragEnd = { if (active >= 0) onCommit(live); active = -1 },
                onDragCancel = { live = committed; active = -1 },
                onDrag = { change, d ->
                    if (active < 0) return@detectDragGestures
                    change.consume()
                    val p = live[active]
                    // Corners may sit outside the photo to pull in more than it shows.
                    val moved = NPoint(
                        (p.x + d.x / frame.width).coerceIn(-0.5f, 1.5f),
                        (p.y + d.y / frame.height).coerceIn(-0.5f, 1.5f),
                    )
                    live = live.toMutableList().also { it[active] = moved }
                },
            )
        },
    ) {
        val s = min(size.width / bitmap.width, size.height / bitmap.height) * 0.8f
        val w = bitmap.width * s
        val h = bitmap.height * s
        frame = Rect(Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h))
        drawImage(
            bitmap,
            dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        val pts = live.map(::toScreen)
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
        val r = 8.dp.toPx()
        pts.forEachIndexed { i, p ->
            drawCircle(Ink.Black, (if (i == active) r * 1.5f else r) + thin * 2, p)
            drawCircle(Ink.Bone, if (i == active) r * 1.5f else r, p)
        }
    }
}

private fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
