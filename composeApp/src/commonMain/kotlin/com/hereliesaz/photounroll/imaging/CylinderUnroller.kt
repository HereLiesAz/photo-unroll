package com.hereliesaz.photounroll.imaging

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * An unwrapped (or reprojected) image plus a per-column confidence used when blending.
 * [pxPerRadian] is set when columns are uniform in angle (panorama), so the stitched
 * result can be mapped to a sphere.
 */
class WeightedStrip(
    val image: RgbaImage,
    val columnWeight: FloatArray,
    val pxPerRadian: Float? = null,
) {
    init { require(columnWeight.size == image.width) }
}

/**
 * Flattens the visible face of a cylinder.
 *
 * Model: orthographic view, axis vertical in the frame. A surface point at angle θ
 * from the line of sight projects to x = cx + r·sinθ, and its rim rows follow the
 * guide ellipses: y = rimY + bulge·cosθ. Sampling uniformly in θ (not x) is what
 * undoes the foreshortening: each output column is the same arc length, r·dθ.
 */
object CylinderUnroller {

    /**
     * @param maxAngleDeg half-width of the arc to keep. Near 90° the surface is seen
     *   edge-on and resolution collapses, so 50–70° is the useful range.
     */
    fun unroll(photo: RgbaImage, guides: CylinderGuides, maxAngleDeg: Float = 60f): WeightedStrip {
        val g = guides.normalized()
        require(g.isValid) { "Guides do not enclose a cylinder" }
        val w = photo.width.toFloat()
        val h = photo.height.toFloat()
        val radius = (g.right - g.left) * w / 2f
        val cx = (g.left + g.right) * w / 2f
        val thetaMax = (maxAngleDeg.coerceIn(5f, 89f) * PI / 180.0).toFloat()

        val outW = max(2, (2f * radius * thetaMax).roundToInt())
        val outH = max(2, ((g.bottomY - g.topY) * h).roundToInt())
        val out = RgbaImage(outW, outH)
        val weight = FloatArray(outW)

        for (ox in 0 until outW) {
            val theta = -thetaMax + (ox + 0.5f) / outW * 2f * thetaMax
            val c = cos(theta)
            val sx = cx + radius * sin(theta)
            val yTop = (g.topY + g.topBulge * c) * h
            val yBottom = (g.bottomY + g.bottomBulge * c) * h
            // cosθ is the foreshortening factor: how many source pixels back one output pixel.
            weight[ox] = c
            for (oy in 0 until outH) {
                val v = (oy + 0.5f) / outH
                out[ox, oy] = photo.sample(sx, yTop + v * (yBottom - yTop))
            }
        }
        return WeightedStrip(out, weight)
    }
}
