package com.hereliesaz.photounroll.imaging

import androidx.compose.ui.graphics.ImageBitmap

/** The only platform-specific imaging surface: compressed bytes <-> [RgbaImage] <-> Compose. */
expect object ImageCodec {
    /** Decodes JPEG/PNG/WebP bytes. Does NOT apply EXIF orientation; see [loadPhoto]. */
    fun decode(bytes: ByteArray): RgbaImage

    /** Encodes opaque JPEG at [quality] 0..100. */
    fun encodeJpeg(image: RgbaImage, quality: Int = 92): ByteArray

    fun toImageBitmap(image: RgbaImage): ImageBitmap
}

/** Decodes, rights it per EXIF, and caps the longest side so every target stays inside its memory budget. */
fun loadPhoto(bytes: ByteArray, maxDim: Int = 1600): RgbaImage =
    ImageCodec.decode(bytes).withExifOrientation(Exif.orientation(bytes)).scaledToMax(maxDim)
