package com.hereliesaz.photounroll.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A point in normalized image coordinates (0..1 on each axis). */
data class NPoint(val x: Float, val y: Float)

/**
 * Per-photo correction applied before unrolling or stitching: a free rotation about the
 * center, then a perspective warp that maps the [quad] (corners picked on the rotated
 * photo, clockwise from top-left) onto the full frame. Output keeps the photo's size.
 */
data class PhotoAdjust(
    val rotationDeg: Float = 0f,
    val quad: List<NPoint> = FULL_FRAME,
) {
    init { require(quad.size == 4) }

    val isIdentity: Boolean
        get() = abs(rotationDeg % 360f) < 0.01f && quad.zip(FULL_FRAME).all { (a, b) -> abs(a.x - b.x) < 1e-4f && abs(a.y - b.y) < 1e-4f }

    /** Single resample: output pixel -> warp quad -> un-rotate -> source. */
    fun apply(src: RgbaImage): RgbaImage {
        if (isIdentity) return src
        val w = src.width.toFloat()
        val h = src.height.toFloat()
        val h3 = Homography.fromUnitSquare(quad.map { NPoint(it.x * w, it.y * h) })
        val a = (-rotationDeg * PI / 180.0).toFloat()
        val c = cos(a)
        val s = sin(a)
        val cx = w / 2f
        val cy = h / 2f
        val out = RgbaImage(src.width, src.height)
        for (oy in 0 until src.height) {
            val v = (oy + 0.5f) / h
            for (ox in 0 until src.width) {
                val p = h3.map((ox + 0.5f) / w, v)
                // p lies on the rotated photo; rotate back by -angle to read the source.
                val dx = p.x - cx
                val dy = p.y - cy
                out[ox, oy] = src.sample(cx + dx * c - dy * s, cy + dx * s + dy * c)
            }
        }
        return out
    }

    companion object {
        val FULL_FRAME = listOf(NPoint(0f, 0f), NPoint(1f, 0f), NPoint(1f, 1f), NPoint(0f, 1f))
    }
}

/** 3x3 projective transform, row-major, h33 = 1. */
class Homography(private val m: FloatArray) {
    fun map(x: Float, y: Float): NPoint {
        val d = m[6] * x + m[7] * y + m[8]
        return NPoint((m[0] * x + m[1] * y + m[2]) / d, (m[3] * x + m[4] * y + m[5]) / d)
    }

    companion object {
        /** Maps (0,0),(1,0),(1,1),(0,1) onto [dst] in that order. */
        fun fromUnitSquare(dst: List<NPoint>): Homography {
            val src = PhotoAdjust.FULL_FRAME
            // Standard DLT with h33 = 1: 8 equations, 8 unknowns.
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i].x.toDouble() to src[i].y.toDouble()
                val (u, v) = dst[i].x.toDouble() to dst[i].y.toDouble()
                a[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val sol = solve(a)
            return Homography(FloatArray(9) { if (it == 8) 1f else sol[it].toFloat() })
        }

        /** Gaussian elimination with partial pivoting on an augmented n x (n+1) system. */
        private fun solve(a: Array<DoubleArray>): DoubleArray {
            val n = a.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                require(abs(a[pivot][col]) > 1e-12) { "Degenerate warp corners" }
                val t = a[col]; a[col] = a[pivot]; a[pivot] = t
                for (r in 0 until n) if (r != col) {
                    val f = a[r][col] / a[col][col]
                    for (k in col..n) a[r][k] -= f * a[col][k]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}
