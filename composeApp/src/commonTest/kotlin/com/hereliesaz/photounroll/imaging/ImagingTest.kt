package com.hereliesaz.photounroll.imaging

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImagingTest {
    /** Non-repeating texture around the circumference, varying along the axis too. */
    private fun texture(u: Double, v: Double): Int {
        val s = 0.45 * sin(u) + 0.3 * sin(7 * u + 1) + 0.25 * sin(17 * u + 6 * v)
        val g = (128 + 120 * s).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private val guides = CylinderGuides(left = 0.25f, right = 0.75f, topY = 0.2f, topBulge = 0.04f, bottomY = 0.85f, bottomBulge = 0.06f)

    /** Orthographic render of the textured cylinder turned by [phi]. */
    private fun renderCylinder(phi: Double, w: Int = 400, h: Int = 300): RgbaImage {
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF404040.toInt() })
        val r = (guides.right - guides.left) * w / 2.0
        val cx = (guides.left + guides.right) * w / 2.0
        for (x in 0 until w) {
            val sx = (x + 0.5 - cx) / r
            if (abs(sx) >= 1) continue
            val theta = asin(sx)
            val c = cos(theta)
            val yt = (guides.topY + guides.topBulge * c) * h
            val yb = (guides.bottomY + guides.bottomBulge * c) * h
            for (y in 0 until h) {
                val v = (y + 0.5 - yt) / (yb - yt)
                if (v in 0.0..1.0) img[x, y] = texture(theta + phi, v)
            }
        }
        return img
    }

    private fun gray(p: Int) = (p shr 8) and 0xFF

    @Test
    fun unrollRecoversSurfaceUniformlyInAngle() {
        val strip = CylinderUnroller.unroll(renderCylinder(0.0), guides, 60f).image
        val r = (guides.right - guides.left) * 400 / 2.0
        assertEquals((2 * r * PI / 3).roundToInt(), strip.width, "arc length of ±60°")
        var err = 0.0
        var n = 0
        for (ox in 4 until strip.width - 4 step 3) for (oy in 4 until strip.height - 4 step 5) {
            val theta = -PI / 3 + (ox + 0.5) / strip.width * 2 * PI / 3
            err += abs(gray(strip[ox, oy]) - gray(texture(theta, (oy + 0.5) / strip.height)))
            n++
        }
        assertTrue(err / n < 12, "mean abs error ${err / n}")
    }

    @Test
    fun walkAroundClosesToOneCircumference() {
        val views = 8
        val strips = (0 until views).map { k ->
            CylinderUnroller.unroll(renderCylinder(2 * PI * k / views), guides, 60f)
        }
        val result = Stitcher.stitch(strips, closeLoop = true)
        val expected = 2 * PI * (guides.right - guides.left) * 400 / 2
        assertTrue(result.closedLoop)
        assertTrue(abs(result.image.width - expected) / expected < 0.03, "width ${result.image.width} vs $expected")
    }

    @Test
    fun reversedCaptureOrderIsDetected() {
        val strips = (0 until 6).map { k ->
            CylinderUnroller.unroll(renderCylinder(-2 * PI * k / 6), guides, 60f)
        }
        val result = Stitcher.stitch(strips, closeLoop = true)
        val expected = 2 * PI * (guides.right - guides.left) * 400 / 2
        assertTrue(abs(result.image.width - expected) / expected < 0.03, "width ${result.image.width} vs $expected")
    }

    @Test
    fun panoramaFromRotatingCamera() {
        // Pinhole frames of a textured world cylinder, camera turning 45° per frame.
        val w = 320; val h = 200; val hfov = 70.0
        val f = (w / 2) / tan(hfov * PI / 360)
        val frames = (0 until 8).map { k ->
            RgbaImage(w, h, IntArray(w * h) { i ->
                val x = i % w + 0.5 - w / 2.0
                val y = i / w + 0.5 - h / 2.0
                val theta = kotlin.math.atan2(x, f)
                val elev = y / kotlin.math.sqrt(x * x + f * f)
                texture(theta + 2 * PI * k / 8, elev)
            })
        }
        val rendered = Pipelines.panorama(frames, hfov.toFloat(), fullTurn = true)
        assertTrue(rendered.wraps)
        val expected = 2 * PI * f
        assertTrue(abs(rendered.image.width - expected) / expected < 0.03, "width ${rendered.image.width} vs $expected")
    }

    @Test
    fun exifOrientationAndXmpInjection() {
        // SOI, APP1 Exif (big-endian TIFF, one IFD entry: orientation = 6), SOS, EOI.
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
            0x00, 0x01, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, 0x06, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
        val body = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val len = body.size + 2
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) +
            body + byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 0xFF.toByte(), 0xD9.toByte())
        assertEquals(6, Exif.orientation(jpeg))

        val out = PanoramaXmp.inject(jpeg, PanoramaXmp.gpano(1000, 200, 1000 / (2 * PI).toFloat()))
        assertEquals(6, Exif.orientation(out), "EXIF still first")
        val text = out.decodeToString()
        assertTrue("GPano:FullPanoWidthPixels=\"1000\"" in text)
        assertTrue("GPano:CroppedAreaTopPixels=\"150\"" in text)
        assertTrue(text.indexOf("ns.adobe.com/xap") > text.indexOf("Exif"))
    }

    @Test
    fun orientationRotatesUpright() {
        val img = RgbaImage(2, 1, intArrayOf(1, 2))
        val r = img.withExifOrientation(6)
        assertEquals(1, r.width); assertEquals(2, r.height)
        assertEquals(1, r[0, 0]); assertEquals(2, r[0, 1])
    }
}

class EdgeSuggesterTest {
    @Test
    fun findsSilhouetteAndRims() {
        val g = CylinderGuides(left = 0.3f, right = 0.7f, topY = 0.2f, topBulge = 0.04f, bottomY = 0.8f, bottomBulge = 0.06f)
        val w = 400; val h = 400
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF303840.toInt() })
        val r = (g.right - g.left) * w / 2f; val cx = (g.left + g.right) * w / 2f
        for (x in 0 until w) {
            val s = (x + 0.5f - cx) / r
            if (abs(s) >= 1f) continue
            val c = kotlin.math.sqrt(1f - s * s)
            for (y in 0 until h) {
                val yy = (y + 0.5f) / h
                // Stripes inside the body must not be mistaken for edges.
                if (yy > g.topY + g.topBulge * c && yy < g.bottomY + g.bottomBulge * c) {
                    img[x, y] = if ((x / 9) % 2 == 0) 0xFFE0D0C0.toInt() else 0xFFB02020.toInt()
                }
            }
        }
        val s = EdgeSuggester.suggest(img)
        val tol = 0.015f
        assertTrue(abs(s.left - g.left) < tol, "left ${s.left}")
        assertTrue(abs(s.right - g.right) < tol, "right ${s.right}")
        assertTrue(abs(s.topY - g.topY) < tol, "topY ${s.topY}")
        assertTrue(abs(s.topBulge - g.topBulge) < tol, "topBulge ${s.topBulge}")
        assertTrue(abs(s.bottomY - g.bottomY) < tol, "bottomY ${s.bottomY}")
        assertTrue(abs(s.bottomBulge - g.bottomBulge) < tol, "bottomBulge ${s.bottomBulge}")
    }
}

class PhotoAdjustTest {
    private fun pattern(w: Int, h: Int) = RgbaImage(w, h, IntArray(w * h) { i ->
        val g = ((i % w) * 7 + (i / w) * 13) and 0xFF
        (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    })

    @Test
    fun identityReturnsSameImage() {
        val img = pattern(20, 10)
        assertTrue(PhotoAdjust().apply(img) === img)
    }

    @Test
    fun homographyHitsCorners() {
        val dst = listOf(NPoint(2f, 3f), NPoint(50f, 1f), NPoint(60f, 40f), NPoint(-5f, 30f))
        val h = Homography.fromUnitSquare(dst)
        PhotoAdjust.FULL_FRAME.zip(dst).forEach { (s, d) ->
            val p = h.map(s.x, s.y)
            assertTrue(abs(p.x - d.x) < 1e-3f && abs(p.y - d.y) < 1e-3f, "$s -> $p, want $d")
        }
    }

    @Test
    fun quarterTurnMatchesExactRotation() {
        val img = pattern(16, 16)
        val turned = PhotoAdjust(rotationDeg = 90f).apply(img)
        val exact = img.rotated90()
        var diff = 0
        for (y in 2 until 14) for (x in 2 until 14) diff += abs(((turned[x, y] shr 8) and 0xFF) - ((exact[x, y] shr 8) and 0xFF))
        assertTrue(diff / 144 < 2, "mean diff ${diff / 144}")
    }

    @Test
    fun warpCropsToQuad() {
        val img = pattern(40, 40)
        // Selecting the right half stretches it to fill the frame.
        val out = PhotoAdjust(quad = listOf(NPoint(0.5f, 0f), NPoint(1f, 0f), NPoint(1f, 1f), NPoint(0.5f, 1f))).apply(img)
        assertEquals(img.sample(30.25f, 10.5f), out.sample(20.5f, 10.5f))
    }
}

class ArrangeTest {
    private val guides = CylinderGuides(left = 0.25f, right = 0.75f, topY = 0.2f, topBulge = 0.04f, bottomY = 0.85f, bottomBulge = 0.06f)

    /** Pink-noise-like surface: many harmonics with fixed pseudo-random phases, so no two views alias. */
    private val harmonics = run {
        var seed = 12345L
        fun next(): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % 10000) / 10000.0 }
        List(60) { i -> doubleArrayOf((i + 1).toDouble(), 1.0 / (i + 1) * 0.9 + 0.02, next() * 2 * PI, (next() - 0.5) * 8) }
    }

    private fun texture(u: Double, v: Double): Int {
        var s = 0.0
        var norm = 0.0
        for (h in harmonics) { s += h[1] * sin(h[0] * u + h[2] + h[3] * v); norm += h[1] * h[1] }
        val g = (128 + 120 * s / kotlin.math.sqrt(norm)).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (g shl 16) or (g shl 8) or g
    }

    private fun render(phi: Double, w: Int = 400, h: Int = 300): RgbaImage {
        val img = RgbaImage(w, h, IntArray(w * h) { 0xFF404040.toInt() })
        val r = (guides.right - guides.left) * w / 2.0
        val cx = (guides.left + guides.right) * w / 2.0
        for (x in 0 until w) {
            val sx = (x + 0.5 - cx) / r
            if (abs(sx) >= 1) continue
            val theta = asin(sx)
            val c = cos(theta)
            val yt = (guides.topY + guides.topBulge * c) * h
            val yb = (guides.bottomY + guides.bottomBulge * c) * h
            for (y in 0 until h) {
                val v = (y + 0.5 - yt) / (yb - yt)
                if (v in 0.0..1.0) img[x, y] = texture(theta + phi, v)
            }
        }
        return img
    }

    /** True when [order] walks the ring one step at a time, in either direction. */
    private fun isRingOrder(order: IntArray, n: Int): Boolean {
        fun step(a: Int, b: Int) = (b - a + n) % n
        val steps = (0 until order.size - 1).map { step(order[it], order[it + 1]) }
        return steps.all { it == 1 } || steps.all { it == n - 1 }
    }

    @Test
    fun shuffledWalkAroundIsPutBackInOrder() {
        val n = 10
        val shuffle = intArrayOf(6, 2, 9, 0, 4, 7, 1, 8, 3, 5)
        val photos = shuffle.map { render(2 * PI * it / n) }
        val order = Pipelines.arrangeCylinder(photos, List(n) { guides }, 60f)
        val recovered = IntArray(n) { shuffle[order[it]] }
        // A ring has no start: the path may begin anywhere, so check it as a cyclic walk.
        val steps = (0 until n - 1).map { (recovered[it + 1] - recovered[it] + n) % n }
        assertTrue(steps.all { it == 1 } || steps.all { it == n - 1 }, "recovered ${recovered.toList()}")
    }

    @Test
    fun partialArcKeepsItsOrder() {
        val shuffle = intArrayOf(3, 0, 4, 1, 2)
        val photos = shuffle.map { render(0.5 * it) }
        val order = Pipelines.arrangeCylinder(photos, List(5) { guides }, 60f)
        val recovered = order.map { shuffle[it] }
        assertTrue(recovered == listOf(0, 1, 2, 3, 4) || recovered == listOf(4, 3, 2, 1, 0), "recovered $recovered")
    }

    @Test
    fun overshotVideoIsCutAtOneTurn() {
        // 48 frames across 1.25 turns: frame 38.4 would be the first view again.
        val total = 48
        val photos = (0 until total).map { render(2 * PI * 1.25 * it / total) }
        val strips = photos.map { CylinderUnroller.unroll(it, guides, 60f).image }
        val keep = Arranger.framesInOneTurn(strips)
        assertTrue(keep in 34..39, "kept $keep")
        val result = Pipelines.unrollCylinder(photos, List(total) { guides }, 60f, fullTurn = true, trimToTurn = true)
        val expected = 2 * PI * (guides.right - guides.left) * 400 / 2
        assertTrue(abs(result.image.width - expected) / expected < 0.04, "width ${result.image.width} vs $expected")
    }

    @Test
    fun undershotVideoIsLeftAlone() {
        val total = 30
        val photos = (0 until total).map { render(2 * PI * 0.9 * it / total) }
        val strips = photos.map { CylinderUnroller.unroll(it, guides, 60f).image }
        assertEquals(total, Arranger.framesInOneTurn(strips))
    }
}
