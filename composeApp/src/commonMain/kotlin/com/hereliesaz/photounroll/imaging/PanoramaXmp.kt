package com.hereliesaz.photounroll.imaging

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Photo Sphere (GPano) metadata. An equirectangular band plus these fields is what
 * Google Photos, Facebook and most 360° viewers recognize; the cropped-area fields let
 * us ship only the photographed band instead of padding a full 2:1 sphere with black.
 */
object PanoramaXmp {
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"

    fun gpano(bandWidth: Int, bandHeight: Int, pxPerRadian: Float): String {
        val fullW = max(bandWidth, (2 * PI * pxPerRadian).roundToInt())
        val fullH = fullW / 2
        val top = ((fullH - bandHeight) / 2).coerceAtLeast(0)
        return """<?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/"
   GPano:UsePanoramaViewer="True"
   GPano:ProjectionType="equirectangular"
   GPano:CroppedAreaImageWidthPixels="$bandWidth"
   GPano:CroppedAreaImageHeightPixels="$bandHeight"
   GPano:FullPanoWidthPixels="$fullW"
   GPano:FullPanoHeightPixels="$fullH"
   GPano:CroppedAreaLeftPixels="0"
   GPano:CroppedAreaTopPixels="$top"/>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }

    /** Inserts an XMP APP1 segment after SOI and any JFIF/EXIF headers. */
    fun inject(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size >= 4 && jpeg.u8(0) == 0xFF && jpeg.u8(1) == 0xD8) { "Not a JPEG" }
        val payload = (XMP_HEADER + xmp).encodeToByteArray()
        val segLen = payload.size + 2
        require(segLen <= 0xFFFF) { "XMP too large for one APP1 segment" }

        var at = 2
        while (at + 4 <= jpeg.size && jpeg.u8(at) == 0xFF && jpeg.u8(at + 1) in 0xE0..0xE1) {
            at += 2 + ((jpeg.u8(at + 2) shl 8) or jpeg.u8(at + 3))
        }
        val seg = ByteArray(4 + payload.size)
        seg[0] = 0xFF.toByte(); seg[1] = 0xE1.toByte()
        seg[2] = (segLen shr 8).toByte(); seg[3] = segLen.toByte()
        payload.copyInto(seg, 4)
        return jpeg.copyOfRange(0, at) + seg + jpeg.copyOfRange(at, jpeg.size)
    }
}
