package com.hereliesaz.photounroll.imaging

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Panorama mode: the camera turns in place, so each frame is reprojected onto a
 * cylinder around the camera. After this, pure horizontal rotation becomes pure
 * horizontal translation and frames stitch by sliding.
 */
object CylindricalProjector {

    /** @param hfovDeg the lens's horizontal field of view; phone main cameras sit near 65–75°. */
    fun project(photo: RgbaImage, hfovDeg: Float): WeightedStrip {
        val w = photo.width
        val h = photo.height
        val half = (hfovDeg.coerceIn(10f, 170f) * PI / 360.0).toFloat()
        val f = (w / 2f) / tan(half)
        val outW = max(2, (2f * f * half).roundToInt())
        val out = RgbaImage(outW, h)
        for (ox in 0 until outW) {
            val theta = (ox + 0.5f - outW / 2f) / f
            val c = cos(theta)
            val sx = w / 2f + f * tan(theta)
            for (oy in 0 until h) {
                out[ox, oy] = photo.sample(sx, h / 2f + (oy + 0.5f - h / 2f) / c)
            }
        }
        return WeightedStrip(out, FloatArray(outW) { 1f }, pxPerRadian = f)
    }

    /**
     * Cylindrical strip -> equirectangular band (vertical axis linear in latitude).
     * Returns the band; its vertical placement on the full sphere is centered.
     */
    fun toEquirectangular(strip: RgbaImage, pxPerRadian: Float): RgbaImage {
        val f = pxPerRadian
        val h = strip.height
        val phiMax = atan((h / 2f) / f)
        val outH = max(2, (2f * phiMax * f).roundToInt())
        val out = RgbaImage(strip.width, outH)
        for (oy in 0 until outH) {
            val sy = h / 2f + f * tan((oy + 0.5f - outH / 2f) / f)
            for (x in 0 until strip.width) out[x, oy] = strip.sample(x + 0.5f, sy)
        }
        return out
    }
}
