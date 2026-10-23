package com.hereliesaz.photounroll.video

import android.content.Context
import android.media.MediaMetadataRetriever
import com.hereliesaz.photounroll.imaging.RgbaImage
import io.github.vinceglb.filekit.AndroidFile
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Set once from [com.hereliesaz.photounroll.MainActivity]; needed to open content:// videos. */
internal object AppContext {
    lateinit var app: Context
}

actual object VideoFrames {
    actual suspend fun extract(file: PlatformFile, maxDim: Int, onProgress: (Float) -> Unit): List<RgbaImage> =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                when (val f = file.androidFile) {
                    is AndroidFile.UriWrapper -> retriever.setDataSource(AppContext.app, f.uri)
                    is AndroidFile.FileWrapper -> retriever.setDataSource(f.file.absolutePath)
                }
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: error("Could not read the video's length")
                require(durationMs > 0) { "The video is empty" }
                val n = frameCountFor(durationMs / 1000.0)
                val frames = ArrayList<RgbaImage>(n)
                for (i in 0 until n) {
                    val timeUs = durationMs * 1000L * (2 * i + 1) / (2L * n)
                    // OPTION_CLOSEST decodes to the exact time; sync-frame options repeat the same keyframe.
                    val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    frames += RgbaImage(bmp.width, bmp.height, px).scaledToMax(maxDim)
                    bmp.recycle()
                    onProgress((i + 1f) / n)
                }
                check(frames.size >= 2) { "Could not decode frames from that video" }
                frames
            } finally {
                retriever.release()
            }
        }
}
