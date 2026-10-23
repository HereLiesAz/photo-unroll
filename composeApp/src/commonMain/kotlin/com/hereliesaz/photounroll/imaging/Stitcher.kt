package com.hereliesaz.photounroll.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * @property closedLoop true when the last strip was aligned back onto the first, so the
 *   image's right edge continues into its left edge and [image].width is exactly one turn.
 */
class StitchResult(
    val image: RgbaImage,
    val closedLoop: Boolean,
    val pxPerRadian: Float?,
)

/**
 * Aligns strips that each overlap their neighbor by translation only, then feather-blends.
 * Shared by both modes: unrolled cylinder faces and cylindrically projected frames
 * both differ from their neighbors by a slide.
 */
object Stitcher {
    private const val COARSE_H = 48
    private const val FINE_H = 192
    internal const val MIN_OVERLAP = 0.08f
    internal const val MAX_DY = 0.06f

    fun stitch(input: List<WeightedStrip>, closeLoop: Boolean, progress: (Float) -> Unit = {}): StitchResult {
        require(input.isNotEmpty()) { "Nothing to stitch" }
        val targetH = input.map { it.image.height }.sorted()[input.size / 2]
        var strips = input.map { it.scaledToHeight(targetH) }
        val loop = closeLoop && strips.size >= 2

        // Capture direction is unknown (walked/turned left or right). An open search
        // votes on it; then every pair is re-aligned with only forward slides allowed,
        // which removes the spurious thin-overlap matches a single pair can produce.
        val votes = links(strips, loop, forwardOnly = false) { progress(0.4f * it) }
        if (votes.count { it.dx < 0f } * 2 > votes.size) strips = strips.reversed()
        val links = links(strips, loop, forwardOnly = true) { progress(0.4f + 0.4f * it) }

        val n = strips.size
        // Offsets accumulate as floats and are rounded once per strip, so measurement error
        // stays per-strip instead of piling up around a loop.
        val exact = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 1 until n) {
            exact[i] = exact[i - 1] + links[i - 1].dx
            ys[i] = ys[i - 1] + links[i - 1].dy
        }
        val xs = IntArray(n) { exact[it].roundToInt() }
        val canvasW: Int
        if (loop) {
            canvasW = max(1, links.sumOf { it.dx.toDouble() }.roundToInt())
            // Spread vertical drift so the seam closes without a step.
            val drift = links.sumOf { it.dy.toDouble() }.toFloat()
            for (i in 0 until n) ys[i] -= drift * i / n
        } else {
            canvasW = (0 until n).maxOf { xs[it] + strips[it].image.width }
        }
        val yShift = ys.average().toFloat()

        val acc = FloatArray(canvasW * targetH * 3)
        val wsum = FloatArray(canvasW * targetH)
        strips.forEachIndexed { i, s ->
            val img = s.image
            val feather = max(1f, img.width * 0.15f)
            for (cx in 0 until img.width) {
                val edge = min(cx + 1, img.width - cx) / feather
                val wCol = s.columnWeight[cx].coerceAtLeast(0f) * min(1f, edge)
                if (wCol <= 0f) continue
                val wx = wCol * wCol
                var x = xs[i] + cx
                if (loop) x = ((x % canvasW) + canvasW) % canvasW else if (x !in 0 until canvasW) continue
                for (y in 0 until targetH) {
                    val p = img.sample(cx + 0.5f, y + 0.5f + ys[i] - yShift)
                    if ((p ushr 24) < 128) continue
                    val k = y * canvasW + x
                    acc[3 * k] += wx * ((p shr 16) and 0xFF)
                    acc[3 * k + 1] += wx * ((p shr 8) and 0xFF)
                    acc[3 * k + 2] += wx * (p and 0xFF)
                    wsum[k] += wx
                }
            }
        }
        val out = RgbaImage(canvasW, targetH)
        for (k in wsum.indices) {
            val w = wsum[k]
            out.pixels[k] = if (w <= 0f) 0xFF000000.toInt() else {
                val r = (acc[3 * k] / w).roundToInt().coerceIn(0, 255)
                val g = (acc[3 * k + 1] / w).roundToInt().coerceIn(0, 255)
                val b = (acc[3 * k + 2] / w).roundToInt().coerceIn(0, 255)
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        progress(1f)

        val ppr = when {
            loop -> (canvasW / (2 * kotlin.math.PI)).toFloat()
            else -> strips.mapNotNull { it.pxPerRadian }.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }
        }
        return StitchResult(out, loop, ppr?.takeIf { strips.all { s -> s.pxPerRadian != null } })
    }

    /** Offset of strip b's origin relative to strip a's, in a's pixels. */
    class Link(val dx: Float, val dy: Float)

    private fun links(strips: List<WeightedStrip>, loop: Boolean, forwardOnly: Boolean, progress: (Float) -> Unit): List<Link> {
        val count = if (loop) strips.size else strips.size - 1
        return (0 until count).map { i ->
            progress(i.toFloat() / max(1, count))
            align(strips[i].image, strips[(i + 1) % strips.size].image, forwardOnly)
        }
    }

    /** Coarse-to-fine normalized cross-correlation over horizontal and small vertical shifts. */
    fun align(a: RgbaImage, b: RgbaImage, forwardOnly: Boolean = false): Link {
        val ca = Plane.of(a, COARSE_H)
        val cb = Plane.of(b, COARSE_H)
        val minOv = max(3, (MIN_OVERLAP * min(ca.w, cb.w)).roundToInt())
        val dyR = max(1, (MAX_DY * COARSE_H).roundToInt())
        val dxs = if (forwardOnly) 1..(ca.w - minOv) else -(cb.w - minOv)..(ca.w - minOv)
        val coarse = search(ca, cb, dxs, -dyR..dyR)

        val fh = min(FINE_H, a.height)
        val fa = Plane.of(a, fh)
        val fb = Plane.of(b, fh)
        val ratio = fh.toFloat() / COARSE_H
        val r = ratio.roundToInt() + 1
        val cx = (coarse.first * ratio).roundToInt()
        val cy = (coarse.second * ratio).roundToInt()
        val fine = search(fa, fb, (cx - r)..(cx + r), (cy - r)..(cy + r))

        // Sub-pixel: the correlation peak is parabolic near its top, so the neighbors on either
        // side of the best slide say which way, and how far, the true peak lies.
        val minPixels = max(16, (MIN_OVERLAP * min(fa.w, fb.w) * fa.h / 2).toInt())
        val sm = ncc(fa, fb, fine.first - 1, fine.second, minPixels).first
        val s0 = ncc(fa, fb, fine.first, fine.second, minPixels).first
        val sp = ncc(fa, fb, fine.first + 1, fine.second, minPixels).first
        val curve = sm - 2 * s0 + sp
        val offset = if (curve < -1e-6f && sm > -1f && sp > -1f) (0.5f * (sm - sp) / curve).coerceIn(-0.5f, 0.5f) else 0f

        val toFull = a.height.toFloat() / fh
        return Link((fine.first + offset) * toFull, fine.second * toFull)
    }

    private fun search(a: Plane, b: Plane, dxs: IntRange, dys: IntRange): Pair<Int, Int> {
        var best = Float.NEGATIVE_INFINITY
        var bestDx = 0
        var bestDy = 0
        val minPixels = max(16, (MIN_OVERLAP * min(a.w, b.w) * a.h / 2).toInt())
        val fullOverlap = min(a.w, b.w).toFloat() * min(a.h, b.h)
        for (dy in dys) for (dx in dxs) {
            val (s, n) = ncc(a, b, dx, dy, minPixels)
            // Thin overlaps correlate by luck; discount them. Favor level alignment on ties.
            val support = sqrt(min(1f, n / (0.3f * fullOverlap)))
            val score = s * support - 1e-4f * abs(dy)
            if (score > best) { best = score; bestDx = dx; bestDy = dy }
        }
        return bestDx to bestDy
    }

    /** NCC of a(x, y) against b(x - dx, y - dy) over their valid overlap. */
    internal fun ncc(a: Plane, b: Plane, dx: Int, dy: Int, minPixels: Int): Pair<Float, Int> {
        val x0 = max(0, dx); val x1 = min(a.w, dx + b.w)
        val y0 = max(0, dy); val y1 = min(a.h, dy + b.h)
        if (x1 <= x0 || y1 <= y0) return -1f to 0
        var n = 0; var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0
        for (y in y0 until y1) {
            val ra = y * a.w
            val rb = (y - dy) * b.w - dx
            for (x in x0 until x1) {
                val va = a.v[ra + x]; val vb = b.v[rb + x]
                if (va < 0f || vb < 0f) continue
                n++; sa += va; sb += vb; saa += va * va; sbb += vb * vb; sab += va * vb
            }
        }
        if (n < minPixels) return -1f to n
        val cov = sab - sa * sb / n
        val va = saa - sa * sa / n
        val vb = sbb - sb * sb / n
        if (va <= 1e-6 || vb <= 1e-6) return -1f to n
        return (cov / sqrt(va * vb)).toFloat() to n
    }

    /** Luma at a working height; -1 marks pixels with no data (outside the source photo). */
    internal class Plane(val w: Int, val h: Int, val v: FloatArray) {
        companion object {
            fun of(img: RgbaImage, height: Int): Plane {
                val h = min(height, img.height)
                val w = max(1, (img.width * h.toFloat() / img.height).roundToInt())
                val s = img.resized(w, h)
                val l = s.luma()
                for (i in l.indices) if ((s.pixels[i] ushr 24) < 128) l[i] = -1f
                return Plane(w, h, l)
            }

            /** Exact size, aspect ignored: both sides of a comparison are squeezed the same way. */
            fun fixed(img: RgbaImage, w: Int, h: Int): Plane {
                val s = img.resized(w, h)
                val l = s.luma()
                for (i in l.indices) if ((s.pixels[i] ushr 24) < 128) l[i] = -1f
                return Plane(w, h, l)
            }
        }
    }
}

private fun WeightedStrip.scaledToHeight(h: Int): WeightedStrip {
    if (image.height == h) return this
    val s = h.toFloat() / image.height
    val w = max(2, (image.width * s).roundToInt())
    val src = columnWeight
    return WeightedStrip(
        image.resized(w, h),
        FloatArray(w) { src[min(src.size - 1, (it / s).toInt())] },
        pxPerRadian?.times(s),
    )
}
