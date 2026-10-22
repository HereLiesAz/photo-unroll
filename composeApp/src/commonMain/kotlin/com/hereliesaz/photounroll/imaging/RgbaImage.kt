package com.hereliesaz.photounroll.imaging

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Platform-neutral raster. Pixels are packed ARGB (0xAARRGGBB), row-major.
 * Every algorithm in this package works on this type so the math is shared by
 * all targets; only decode/encode cross the platform boundary (see [ImageCodec]).
 */
class RgbaImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(width > 0 && height > 0) { "Empty image: ${width}x$height" }
        require(pixels.size == width * height) { "Pixel buffer does not match ${width}x$height" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }

    /**
     * Bilinear sample at continuous pixel coordinates (pixel centers at +0.5).
     * Returns fully transparent outside the image so callers can tell "no data" from black.
     */
    fun sample(x: Float, y: Float): Int {
        val fx = x - 0.5f
        val fy = y - 0.5f
        if (fx < -0.5f || fy < -0.5f || fx > width - 0.5f || fy > height - 0.5f) return 0
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        val xa = x0.coerceIn(0, width - 1)
        val xb = (x0 + 1).coerceIn(0, width - 1)
        val ya = y0.coerceIn(0, height - 1)
        val yb = (y0 + 1).coerceIn(0, height - 1)
        return lerpArgb(
            lerpArgb(this[xa, ya], this[xb, ya], tx),
            lerpArgb(this[xa, yb], this[xb, yb], tx),
            ty,
        )
    }

    /** Resampled copy at an exact size (box-filtered when shrinking hard, bilinear otherwise). */
    fun resized(newWidth: Int, newHeight: Int): RgbaImage {
        if (newWidth == width && newHeight == height) return this
        var src = this
        // Halve repeatedly first so bilinear never skips more than one source pixel.
        while (src.width >= newWidth * 2 && src.height >= newHeight * 2) src = src.halved()
        val out = RgbaImage(newWidth, newHeight)
        val sx = src.width.toFloat() / newWidth
        val sy = src.height.toFloat() / newHeight
        for (y in 0 until newHeight) {
            val fy = (y + 0.5f) * sy
            for (x in 0 until newWidth) out[x, y] = src.sample((x + 0.5f) * sx, fy)
        }
        return out
    }

    /** Copy whose longest side is at most [maxDim]; returns this when already small enough. */
    fun scaledToMax(maxDim: Int): RgbaImage {
        val longest = max(width, height)
        if (longest <= maxDim) return this
        val s = maxDim.toFloat() / longest
        return resized(max(1, (width * s).roundToInt()), max(1, (height * s).roundToInt()))
    }

    private fun halved(): RgbaImage {
        val w = max(1, width / 2)
        val h = max(1, height / 2)
        val out = RgbaImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = min(2 * x, width - 1); val x1 = min(2 * x + 1, width - 1)
            val y0 = min(2 * y, height - 1); val y1 = min(2 * y + 1, height - 1)
            out[x, y] = average4(this[x0, y0], this[x1, y0], this[x0, y1], this[x1, y1])
        }
        return out
    }

    /** Luma plane in 0..255, used by alignment and edge detection. */
    fun luma(): FloatArray = FloatArray(width * height) { i ->
        val p = pixels[i]
        0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
    }

    fun rotated90(): RgbaImage {
        val out = RgbaImage(height, width)
        for (y in 0 until height) for (x in 0 until width) out[height - 1 - y, x] = this[x, y]
        return out
    }

    fun rotated180(): RgbaImage = RgbaImage(width, height, pixels.reversedArray())

    fun rotated270(): RgbaImage {
        val out = RgbaImage(height, width)
        for (y in 0 until height) for (x in 0 until width) out[y, width - 1 - x] = this[x, y]
        return out
    }

    fun flippedHorizontally(): RgbaImage {
        val out = RgbaImage(width, height)
        for (y in 0 until height) for (x in 0 until width) out[width - 1 - x, y] = this[x, y]
        return out
    }

    /**
     * Applies an EXIF orientation tag (1..8) so the result is upright.
     * Phones store sensor-oriented pixels plus this tag; decoders here ignore it.
     */
    fun withExifOrientation(orientation: Int): RgbaImage = when (orientation) {
        2 -> flippedHorizontally()
        3 -> rotated180()
        4 -> rotated180().flippedHorizontally()
        5 -> rotated90().flippedHorizontally()
        6 -> rotated90()
        7 -> rotated270().flippedHorizontally()
        8 -> rotated270()
        else -> this
    }
}

internal fun lerpArgb(a: Int, b: Int, t: Float): Int {
    if (a == b) return a
    fun ch(shift: Int): Int {
        val ca = (a ushr shift) and 0xFF
        val cb = (b ushr shift) and 0xFF
        return (ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun average4(a: Int, b: Int, c: Int, d: Int): Int {
    fun ch(shift: Int) =
        (((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) + ((c ushr shift) and 0xFF) + ((d ushr shift) and 0xFF) + 2) / 4
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}
