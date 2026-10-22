package com.hereliesaz.photounroll.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.ByteArrayOutputStream

actual object ImageCodec {
    actual fun decode(bytes: ByteArray): RgbaImage {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("Unsupported image")
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return RgbaImage(bmp.width, bmp.height, px).also { bmp.recycle() }
    }

    actual fun encodeJpeg(image: RgbaImage, quality: Int): ByteArray {
        val bmp = toBitmap(image)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    actual fun toImageBitmap(image: RgbaImage): ImageBitmap = toBitmap(image).asImageBitmap()

    private fun toBitmap(image: RgbaImage): Bitmap =
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
}
