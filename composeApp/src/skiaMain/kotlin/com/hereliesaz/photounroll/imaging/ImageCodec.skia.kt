package com.hereliesaz.photounroll.imaging

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/** Desktop, iOS and Web all render through Skia, so they share this codec. */
actual object ImageCodec {
    actual fun decode(bytes: ByteArray): RgbaImage {
        val image = Image.makeFromEncoded(bytes)
        val bitmap = Bitmap()
        bitmap.allocPixels(info(image.width, image.height))
        check(image.readPixels(bitmap, 0, 0)) { "Could not read decoded pixels" }
        val raw = bitmap.readPixels() ?: error("Could not read decoded pixels")
        // BGRA_8888 in memory is little-endian ARGB: b, g, r, a.
        val px = IntArray(image.width * image.height) { i ->
            val o = i * 4
            (raw.u8(o + 3) shl 24) or (raw.u8(o + 2) shl 16) or (raw.u8(o + 1) shl 8) or raw.u8(o)
        }
        return RgbaImage(image.width, image.height, px)
    }

    actual fun encodeJpeg(image: RgbaImage, quality: Int): ByteArray =
        Image.makeFromBitmap(toBitmap(image))
            .encodeToData(EncodedImageFormat.JPEG, quality.coerceIn(0, 100))
            ?.bytes ?: error("JPEG encoding failed")

    actual fun toImageBitmap(image: RgbaImage): ImageBitmap = toBitmap(image).asComposeImageBitmap()

    private fun info(w: Int, h: Int) = ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL)

    private fun toBitmap(image: RgbaImage): Bitmap {
        val raw = ByteArray(image.width * image.height * 4)
        image.pixels.forEachIndexed { i, p ->
            val o = i * 4
            raw[o] = p.toByte()
            raw[o + 1] = (p shr 8).toByte()
            raw[o + 2] = (p shr 16).toByte()
            raw[o + 3] = (p ushr 24).toByte()
        }
        val bitmap = Bitmap()
        bitmap.allocPixels(info(image.width, image.height))
        bitmap.installPixels(raw)
        bitmap.setImmutable()
        return bitmap
    }
}
