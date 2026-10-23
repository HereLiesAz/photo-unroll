package com.hereliesaz.photounroll.video

import com.hereliesaz.photounroll.imaging.RgbaImage
import io.github.vinceglb.filekit.PlatformFile
import kotlin.math.roundToInt

/**
 * Pulls evenly spaced, upright frames out of a video of someone circling a column (or turning
 * on the spot). Each platform uses its own decoder; everything after this is shared.
 */
expect object VideoFrames {
    /** [maxDim] caps each frame's longest side. Throws with a readable message when the video can't be read. */
    suspend fun extract(file: PlatformFile, maxDim: Int, onProgress: (Float) -> Unit): List<RgbaImage>
}

/**
 * How many frames to take from a video of [durationSec]. A slow lap lasts tens of seconds;
 * about 1.5 frames per second keeps neighbors overlapping heavily without making stitching
 * (which compares every neighbor) crawl.
 */
fun frameCountFor(durationSec: Double): Int = (durationSec * 1.5).roundToInt().coerceIn(16, 40)

/** Longest side of video frames; videos yield dozens of frames, so they are kept smaller than photos. */
const val VIDEO_FRAME_MAX_DIM = 960
