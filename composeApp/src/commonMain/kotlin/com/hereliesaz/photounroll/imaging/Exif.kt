package com.hereliesaz.photounroll.imaging

/** Minimal JPEG/EXIF reader: only the orientation tag (0x0112) is needed. */
object Exif {
    fun orientation(jpeg: ByteArray): Int {
        if (jpeg.size < 4 || jpeg.u8(0) != 0xFF || jpeg.u8(1) != 0xD8) return 1
        var pos = 2
        while (pos + 4 <= jpeg.size) {
            if (jpeg.u8(pos) != 0xFF) return 1
            val marker = jpeg.u8(pos + 1)
            if (marker == 0xDA || marker == 0xD9) return 1 // image data reached: no EXIF
            val len = (jpeg.u8(pos + 2) shl 8) or jpeg.u8(pos + 3)
            val body = pos + 4
            if (marker == 0xE1 && len >= 8 && body + 6 <= jpeg.size &&
                jpeg.u8(body) == 'E'.code && jpeg.u8(body + 1) == 'x'.code &&
                jpeg.u8(body + 2) == 'i'.code && jpeg.u8(body + 3) == 'f'.code
            ) return readTiff(jpeg, body + 6, body + len - 2)
            pos = pos + 2 + len
        }
        return 1
    }

    private fun readTiff(b: ByteArray, tiff: Int, end: Int): Int {
        if (tiff + 8 > end) return 1
        val little = b.u8(tiff) == 0x49
        val ifd = tiff + u32(b, tiff + 4, little)
        if (ifd + 2 > end) return 1
        val count = u16(b, ifd, little)
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > end) break
            if (u16(b, entry, little) == 0x0112) {
                val o = u16(b, entry + 8, little)
                return if (o in 1..8) o else 1
            }
        }
        return 1
    }

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int =
        if (little) b.u8(at) or (b.u8(at + 1) shl 8) else (b.u8(at) shl 8) or b.u8(at + 1)

    private fun u32(b: ByteArray, at: Int, little: Boolean): Int =
        if (little) u16(b, at, true) or (u16(b, at + 2, true) shl 16)
        else (u16(b, at, false) shl 16) or u16(b, at + 2, false)
}

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
