package com.hereliesaz.photounroll.video

import com.hereliesaz.photounroll.imaging.ImageCodec
import com.hereliesaz.photounroll.imaging.RgbaImage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** The JDK cannot decode video, so the desktop app borrows `ffmpeg`/`ffprobe` from the PATH. */
actual object VideoFrames {
    actual suspend fun extract(file: PlatformFile, maxDim: Int, onProgress: (Float) -> Unit): List<RgbaImage> =
        withContext(Dispatchers.IO) {
            val src = file.file.absolutePath
            val duration = probeDuration(src)
            val n = frameCountFor(duration)
            val dir = Files.createTempDirectory("photo-unroll-frames").toFile()
            try {
                // fps picks n frames across the duration; scale caps the long side and never upsizes.
                val filter = "fps=${n / duration},scale='min($maxDim,iw)':'min($maxDim,ih)':force_original_aspect_ratio=decrease"
                val proc = start(
                    "ffmpeg", "-v", "error", "-y", "-i", src, "-vf", filter,
                    "-frames:v", "$n", "-q:v", "2", File(dir, "f%04d.jpg").absolutePath,
                )
                while (!proc.waitFor(200, TimeUnit.MILLISECONDS)) {
                    onProgress((dir.list()?.size ?: 0).toFloat() / n)
                    delay(1)
                }
                val files = dir.listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name }.orEmpty()
                check(proc.exitValue() == 0 || files.size >= 2) {
                    "ffmpeg could not read that video: " + proc.errorStream.bufferedReader().readText().take(300)
                }
                files.mapIndexed { i, f ->
                    onProgress((i + 1f) / files.size)
                    ImageCodec.decode(f.readBytes())
                }
            } finally {
                dir.deleteRecursively()
            }
        }

    private fun probeDuration(src: String): Double {
        val proc = start("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", src)
        val text = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        return text.toDoubleOrNull()?.takeIf { it > 0 } ?: error("Could not read the video's length")
    }

    private fun start(vararg cmd: String): Process = try {
        ProcessBuilder(*cmd).redirectErrorStream(false).start()
    } catch (e: IOException) {
        throw IllegalStateException("Video import on desktop needs ffmpeg on your PATH (https://ffmpeg.org).", e)
    }
}
