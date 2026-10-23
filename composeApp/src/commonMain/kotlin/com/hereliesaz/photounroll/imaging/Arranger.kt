package com.hereliesaz.photounroll.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Orders unordered photos of one subject by how well their flattened strips overlap, and
 * finds where a video that circled past 360° has come back to its start. Works on the same
 * strips the stitcher uses, so "adjacent" means exactly what stitching needs it to mean.
 */
object Arranger {
    private const val PLANE_W = 96
    private const val PLANE_H = 32

    /**
     * Returns indices of [strips] in capture order (either direction; the stitcher detects it).
     *
     * Builds the strongest chain through all strips: edges are taken best-first, skipping any
     * that would give a strip a third neighbor or close a loop early. A full walk-around
     * closes the loop on the last edge; that weakest seam becomes the cut.
     */
    fun order(strips: List<RgbaImage>, progress: (Float) -> Unit = {}): IntArray {
        val n = strips.size
        if (n <= 2) return IntArray(n) { it }
        val planes = strips.map(::plane)

        class Edge(val a: Int, val b: Int, val w: Float)
        val weight = FloatArray(n * n)
        val edges = ArrayList<Edge>(n * (n - 1) / 2)
        var done = 0
        val total = n * (n - 1) / 2
        for (i in 0 until n) for (j in i + 1 until n) {
            val w = strength(planes[i], planes[j])
            weight[i * n + j] = w; weight[j * n + i] = w
            edges += Edge(i, j, w)
            progress(++done / total.toFloat())
        }
        edges.sortByDescending { it.w }

        val degree = IntArray(n)
        val parent = IntArray(n) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) r = parent[r]; parent[x] = r; return r }
        val adj = Array(n) { ArrayList<Int>(2) }
        var taken = 0
        val chosen = ArrayList<Edge>()
        for (e in edges) {
            if (degree[e.a] >= 2 || degree[e.b] >= 2) continue
            val ra = find(e.a)
            val rb = find(e.b)
            // Only the very last edge may close the loop.
            if (ra == rb && taken != n - 1) continue
            if (ra == rb && e.w <= 0f) continue
            parent[ra] = rb
            degree[e.a]++; degree[e.b]++
            adj[e.a] += e.b; adj[e.b] += e.a
            chosen += e
            if (++taken == n) break
        }

        // A closed ring is cut at its weakest seam so it reads as a path.
        if (taken == n) {
            val weakest = chosen.minBy { it.w }
            adj[weakest.a].remove(weakest.b)
            adj[weakest.b].remove(weakest.a)
        }
        // Walk the path from one end; stray pieces (no overlap anywhere) are appended in turn.
        val seen = BooleanArray(n)
        val out = ArrayList<Int>(n)
        val starts = (0 until n).filter { adj[it].size <= 1 }
        for (s0 in starts) {
            if (seen[s0]) continue
            var prev = -1
            var cur = s0
            while (cur != -1 && !seen[cur]) {
                seen[cur] = true
                out += cur
                val next = adj[cur].firstOrNull { it != prev }
                prev = cur
                cur = next ?: -1
            }
        }
        for (i in 0 until n) if (!seen[i]) out += i
        refine(out, weight, n)
        return out.toIntArray()
    }

    /**
     * Greedy chaining is blind to the whole path, and with heavy overlap it slips locally
     * (… 2, 1, 3 …). Repeatedly lift one photo out and drop it at the position that gives the
     * strongest chain overall (Or-opt); stops when no move helps.
     */
    private fun refine(path: MutableList<Int>, w: FloatArray, n: Int) {
        fun e(a: Int, b: Int) = w[a * n + b]
        repeat(2 * n) {
            var improved = false
            for (from in 0 until path.size) {
                val v = path[from]
                val before = if (from > 0) path[from - 1] else -1
                val after = if (from < path.size - 1) path[from + 1] else -1
                // Chain strength gained by removing v and rejoining its neighbors.
                val removeGain = -(if (before >= 0) e(before, v) else 0f) - (if (after >= 0) e(v, after) else 0f) +
                    (if (before >= 0 && after >= 0) e(before, after) else 0f)
                val rest = path.toMutableList().also { it.removeAt(from) }
                var bestPos = from
                var bestDelta = 1e-4f
                for (pos in 0..rest.size) {
                    val l = if (pos > 0) rest[pos - 1] else -1
                    val r = if (pos < rest.size) rest[pos] else -1
                    val insert = (if (l >= 0) e(l, v) else 0f) + (if (r >= 0) e(v, r) else 0f) -
                        (if (l >= 0 && r >= 0) e(l, r) else 0f)
                    val delta = removeGain + insert
                    if (delta > bestDelta) { bestDelta = delta; bestPos = pos }
                }
                if (bestPos != from) {
                    path.clear(); path.addAll(rest); path.add(bestPos, v)
                    improved = true
                }
            }
            if (!improved) return
        }
    }

    /**
     * For a sequence that may have gone past a full turn: the number of leading frames to
     * keep so the last frame stops just short of coming back around to the first.
     * Returns `strips.size` when no frame in the second half sees what the first sees.
     */
    fun framesInOneTurn(strips: List<RgbaImage>): Int {
        val n = strips.size
        if (n < 6) return n
        val first = plane(strips[0])
        var best = -1
        var bestScore = SAME_VIEW
        for (k in n / 2 until n) {
            val p = plane(strips[k])
            val s = sameView(first, p)
            if (s > bestScore) { bestScore = s; best = k }
        }
        // One frame short of the match: never past the start, so the seam still slides forward.
        return if (best < 0) n else max(2, best - 1)
    }

    private const val SAME_VIEW = 0.8f

    /**
     * Texture-only plane: local average removed, then local contrast equalized. A cylinder is
     * lit and foreshortened differently in every view; without this, smooth shading dominates
     * the comparison and any two views look alike at the wrong overlap.
     */
    internal fun plane(img: RgbaImage): Stitcher.Plane {
        val src = Stitcher.Plane.fixed(img, PLANE_W, PLANE_H)
        val w = src.w
        val h = src.h
        val mean = boxMean(src.v, w, h, BOX_X, BOX_Y)
        val dev = FloatArray(w * h) { if (src.v[it] < 0f) -1f else src.v[it] - mean[it] }
        val energy = boxMean(FloatArray(w * h) { if (dev[it] == -1f && src.v[it] < 0f) -1f else dev[it] * dev[it] }, w, h, BOX_X, BOX_Y)
        val out = FloatArray(w * h) {
            if (src.v[it] < 0f) -1f
            // 256 keeps data cells non-negative: -1 is reserved for "no data".
            else 256f + 40f * dev[it] / (kotlin.math.sqrt(energy[it]) + 3f)
        }
        return Stitcher.Plane(w, h, out)
    }

    /** Mean over a (2r+1)² box, ignoring cells marked -1 (no data). */
    private const val BOX_X = 10
    private const val BOX_Y = 6

    private fun boxMean(v: FloatArray, w: Int, h: Int, rx: Int, ry: Int): FloatArray {
        val sumX = FloatArray(w * h)
        val cntX = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f; var c = 0
            for (k in max(0, x - rx)..min(w - 1, x + rx)) { val t = v[y * w + k]; if (t != -1f) { sum += t; c++ } }
            sumX[y * w + x] = sum; cntX[y * w + x] = c
        }
        return FloatArray(w * h) {
            val x = it % w
            val y = it / w
            var sum = 0f; var c = 0
            for (k in max(0, y - ry)..min(h - 1, y + ry)) { sum += sumX[k * w + x]; c += cntX[k * w + x] }
            if (c == 0) 0f else sum / c
        }
    }

    /** Best NCC with the two planes nearly on top of each other (small slide, tiny tilt). */
    private fun sameView(a: Stitcher.Plane, b: Stitcher.Plane): Float {
        val maxDx = max(1, (0.06f * min(a.w, b.w)).roundToInt())
        val maxDy = max(1, (Stitcher.MAX_DY * PLANE_H).roundToInt())
        var best = -1f
        for (dy in -maxDy..maxDy) for (dx in -maxDx..maxDx) {
            val (s, n) = Stitcher.ncc(a, b, dx, dy, max(16, a.w * a.h / 3))
            if (n > 0 && s > best) best = s
        }
        return best
    }

    /**
     * How strongly [b] continues [a]. Correlation quality decides (raised to the 4th power so a
     * clean match at 60% overlap beats a look-alike at 100%, and capped once a match is clean so
     * noise cannot reorder near-identical views); among equally clean matches the one
     * sharing more surface wins, which is what puts neighbors ahead of next-but-one views; a
     * sliver of overlap is vetoed outright because lucky matches on a few columns are common.
     */
    internal fun strength(a: Stitcher.Plane, b: Stitcher.Plane): Float {
        val minOv = max(3, (Stitcher.MIN_OVERLAP * min(a.w, b.w)).roundToInt())
        val dyR = 2
        val full = (min(a.w, b.w) * min(a.h, b.h)).toFloat()
        val minPixels = max(16, (full * Stitcher.MIN_OVERLAP / 2).toInt())
        var best = 0f
        for (dy in -dyR..dyR) for (dx in -(b.w - minOv)..(a.w - minOv)) {
            val (s, n) = Stitcher.ncc(a, b, dx, dy, minPixels)
            if (s <= 0f) continue
            val frac = n / full
            val q = min(1f, s / CLEAN_MATCH)
            val q2 = q * q
            val score = q2 * q2 * min(1f, frac / SLIVER) * (0.7f + 0.3f * frac)
            if (score > best) best = score
        }
        return best
    }

    private const val SLIVER = 0.25f

    /** Correlation at or above this counts as a perfect match; beyond it only noise differs. */
    private const val CLEAN_MATCH = 0.96f
}
