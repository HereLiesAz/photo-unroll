package com.hereliesaz.photounroll.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * First guess at [CylinderGuides], meant to be corrected by hand.
 *
 * Separates the object from the background by color distance from the frame border,
 * takes the silhouette's side columns as the edges, then fits each rim to the
 * silhouette's top and bottom outline: y(x) = rimY + k·cosθ with sinθ = (x - cx)/r.
 * The outline is whichever half of the rim ellipse lies outermost, so |k| is the bulge.
 * Assumes one upright cylinder against a background that differs from it.
 */
object EdgeSuggester {
    private const val WORK_SIZE = 320

    fun suggest(photo: RgbaImage): CylinderGuides {
        val img = photo.scaledToMax(WORK_SIZE)
        val w = img.width
        val h = img.height
        if (w < 16 || h < 16) return CylinderGuides()
        val mask = foreground(img) ?: return CylinderGuides()

        val colCount = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x]) colCount[x]++
        val peak = colCount.max()
        if (peak < h / 8) return CylinderGuides()
        val left = colCount.indexOfFirst { it > peak / 2 }
        val right = colCount.indexOfLast { it > peak / 2 }
        if (right - left < w / 20) return CylinderGuides()

        val cx = (left + right + 1) / 2f
        val r = (right + 1 - left) / 2f
        val xs = ArrayList<Float>()
        val tops = ArrayList<Float>()
        val bottoms = ArrayList<Float>()
        for (x in left + (r * 0.1f).toInt()..right - (r * 0.1f).toInt()) {
            val top = (0 until h).firstOrNull { mask[it * w + x] } ?: continue
            val bottom = (h - 1 downTo 0).first { mask[it * w + x] }
            val s = (x + 0.5f - cx) / r
            xs += sqrt(max(0f, 1f - s * s))
            tops += top.toFloat()
            bottoms += bottom + 1f
        }
        if (xs.size < 8) return CylinderGuides()
        val (topY, topK) = robustFit(xs, tops)
        val (bottomY, bottomK) = robustFit(xs, bottoms)

        return CylinderGuides(
            left = left.toFloat() / w,
            right = (right + 1f) / w,
            topY = topY / h,
            topBulge = abs(topK) / h,
            bottomY = bottomY / h,
            bottomBulge = abs(bottomK) / h,
        ).normalized().takeIf { it.isValid } ?: CylinderGuides()
    }

    /** Pixels that differ from the border's median color by more than its spread allows. */
    private fun foreground(img: RgbaImage): BooleanArray? {
        val w = img.width
        val h = img.height
        val band = max(1, min(w, h) / 30)
        val border = ArrayList<Int>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x < band || y < band || x >= w - band || y >= h - band) border += img[x, y]
        }
        fun ch(p: Int, s: Int) = (p shr s) and 0xFF
        val bg = intArrayOf(16, 8, 0).map { s -> border.map { ch(it, s) }.sorted()[border.size / 2] }
        fun dist(p: Int) = intArrayOf(16, 8, 0).withIndex().sumOf { (i, s) -> abs(ch(p, s) - bg[i]) }
        val spread = border.map { dist(it) }.sorted()[border.size * 9 / 10]
        val threshold = max(40, spread * 2)
        val mask = BooleanArray(w * h) { dist(img.pixels[it]) > threshold }
        return mask.takeIf { m -> m.count { it } > w * h / 50 }
    }

    /** Least squares y = a + k·c, refit once without the worst residuals. */
    private fun robustFit(c: List<Float>, y: List<Float>): Pair<Float, Float> {
        fun fit(idx: List<Int>): Pair<Float, Float> {
            val n = idx.size.toFloat()
            var sc = 0f; var sy = 0f; var scc = 0f; var scy = 0f
            for (i in idx) { sc += c[i]; sy += y[i]; scc += c[i] * c[i]; scy += c[i] * y[i] }
            val det = n * scc - sc * sc
            if (abs(det) < 1e-6f) return (sy / n) to 0f
            val k = (n * scy - sc * sy) / det
            return (sy - k * sc) / n to k
        }
        val all = c.indices.toList()
        val (a, k) = fit(all)
        val res = all.map { abs(y[it] - (a + k * c[it])) }
        val cut = res.sorted()[res.size * 4 / 5]
        val kept = all.filter { res[it] <= cut }
        return if (kept.size >= 4) fit(kept) else a to k
    }
}
