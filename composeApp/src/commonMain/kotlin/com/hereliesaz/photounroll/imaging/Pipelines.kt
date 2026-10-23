package com.hereliesaz.photounroll.imaging

/** Finished render: pixels to show, plus how to write them out. */
class Rendered(val image: RgbaImage, val wraps: Boolean, private val xmp: String?) {
    fun toJpeg(quality: Int = 92): ByteArray {
        val jpeg = ImageCodec.encodeJpeg(image, quality)
        return if (xmp == null) jpeg else PanoramaXmp.inject(jpeg, xmp)
    }
}

/** The user-facing operations, end to end. CPU-bound; call off the main thread. */
object Pipelines {

    /**
     * Capture order of [photos], recovered from how their flattened strips overlap. Needs the
     * same guides (or lens FOV) as the final render, so redo it after correcting them.
     */
    fun arrangeCylinder(
        photos: List<RgbaImage>,
        guides: List<CylinderGuides>,
        maxAngleDeg: Float,
        progress: (Float) -> Unit = {},
    ): IntArray = Arranger.order(
        photos.indices.map { CylinderUnroller.unroll(photos[it], guides[it], maxAngleDeg).image },
        progress,
    )

    fun arrangePanorama(photos: List<RgbaImage>, hfovDeg: Float, progress: (Float) -> Unit = {}): IntArray =
        Arranger.order(photos.map { CylindricalProjector.project(it, hfovDeg).image }, progress)

    /**
     * Walk-around photos of a cylinder -> its flattened surface.
     * @param trimToTurn the photos are a continuous sequence (video) that may have gone past
     *   one full turn; frames after the first view returns are dropped.
     */
    fun unrollCylinder(
        photos: List<RgbaImage>,
        guides: List<CylinderGuides>,
        maxAngleDeg: Float,
        fullTurn: Boolean,
        trimToTurn: Boolean = false,
        progress: (Float) -> Unit = {},
    ): Rendered {
        require(photos.size == guides.size)
        var strips = photos.indices.map { i ->
            progress(0.3f * i / photos.size)
            CylinderUnroller.unroll(photos[i], guides[i], maxAngleDeg)
        }
        if (trimToTurn && fullTurn) strips = strips.take(Arranger.framesInOneTurn(strips.map { it.image }))
        val result = if (strips.size == 1) StitchResult(strips[0].image, false, null)
        else Stitcher.stitch(strips, fullTurn) { progress(0.3f + 0.7f * it) }
        return Rendered(result.image, result.closedLoop, xmp = null)
    }

    /** Photos taken turning in place -> equirectangular band with Photo Sphere metadata. */
    fun panorama(
        photos: List<RgbaImage>,
        hfovDeg: Float,
        fullTurn: Boolean,
        trimToTurn: Boolean = false,
        progress: (Float) -> Unit = {},
    ): Rendered {
        var strips = photos.mapIndexed { i, p ->
            progress(0.3f * i / photos.size)
            CylindricalProjector.project(p, hfovDeg)
        }
        if (trimToTurn && fullTurn) strips = strips.take(Arranger.framesInOneTurn(strips.map { it.image }))
        val stitched = if (strips.size == 1) StitchResult(strips[0].image, false, strips[0].pxPerRadian)
        else Stitcher.stitch(strips, fullTurn) { progress(0.3f + 0.6f * it) }
        val ppr = stitched.pxPerRadian ?: strips[0].pxPerRadian!!
        val band = CylindricalProjector.toEquirectangular(stitched.image, ppr)
        progress(1f)
        return Rendered(band, stitched.closedLoop, PanoramaXmp.gpano(band.width, band.height, ppr))
    }
}
