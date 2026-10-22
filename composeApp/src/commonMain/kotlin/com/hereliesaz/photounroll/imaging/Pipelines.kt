package com.hereliesaz.photounroll.imaging

/** Finished render: pixels to show, plus how to write them out. */
class Rendered(val image: RgbaImage, val wraps: Boolean, private val xmp: String?) {
    fun toJpeg(quality: Int = 92): ByteArray {
        val jpeg = ImageCodec.encodeJpeg(image, quality)
        return if (xmp == null) jpeg else PanoramaXmp.inject(jpeg, xmp)
    }
}

/** The two user-facing operations, end to end. CPU-bound; call off the main thread. */
object Pipelines {

    /** Walk-around photos of a cylinder -> its flattened surface. */
    fun unrollCylinder(
        photos: List<RgbaImage>,
        guides: List<CylinderGuides>,
        maxAngleDeg: Float,
        fullTurn: Boolean,
        progress: (Float) -> Unit = {},
    ): Rendered {
        require(photos.size == guides.size)
        val strips = photos.indices.map { i ->
            progress(0.3f * i / photos.size)
            CylinderUnroller.unroll(photos[i], guides[i], maxAngleDeg)
        }
        val result = if (strips.size == 1) StitchResult(strips[0].image, false, null)
        else Stitcher.stitch(strips, fullTurn) { progress(0.3f + 0.7f * it) }
        return Rendered(result.image, result.closedLoop, xmp = null)
    }

    /** Photos taken turning in place -> equirectangular band with Photo Sphere metadata. */
    fun panorama(
        photos: List<RgbaImage>,
        hfovDeg: Float,
        fullTurn: Boolean,
        progress: (Float) -> Unit = {},
    ): Rendered {
        val strips = photos.mapIndexed { i, p ->
            progress(0.3f * i / photos.size)
            CylindricalProjector.project(p, hfovDeg)
        }
        val stitched = if (strips.size == 1) StitchResult(strips[0].image, false, strips[0].pxPerRadian)
        else Stitcher.stitch(strips, fullTurn) { progress(0.3f + 0.6f * it) }
        val ppr = stitched.pxPerRadian ?: strips[0].pxPerRadian!!
        val band = CylindricalProjector.toEquirectangular(stitched.image, ppr)
        progress(1f)
        return Rendered(band, stitched.closedLoop, PanoramaXmp.gpano(band.width, band.height, ppr))
    }
}
