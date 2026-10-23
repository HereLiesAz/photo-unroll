package com.hereliesaz.photounroll.video

import com.hereliesaz.photounroll.imaging.ImageCodec
import com.hereliesaz.photounroll.imaging.RgbaImage
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

@JsFun(
    """(file) => new Promise((resolve, reject) => {
  const v = document.createElement('video');
  v.muted = true; v.playsInline = true; v.preload = 'auto';
  v.onloadedmetadata = () => resolve(v);
  v.onerror = () => reject(new Error('This browser cannot play that video'));
  v.src = URL.createObjectURL(file);
})""",
)
private external fun openVideo(file: JsAny): Promise<JsAny>

@JsFun("(v) => v.duration")
private external fun duration(v: JsAny): Double

@JsFun("(v) => { URL.revokeObjectURL(v.src); v.removeAttribute('src'); v.load(); }")
private external fun closeVideo(v: JsAny)

/** Seeks to [t], draws the frame to a canvas and returns it as JPEG packed into an Int32Array (word 0 = byte length). */
@JsFun(
    """(v, t, maxDim) => new Promise((resolve, reject) => {
  const draw = () => {
    v.removeEventListener('seeked', draw);
    const s = Math.min(1, maxDim / Math.max(v.videoWidth, v.videoHeight));
    const c = document.createElement('canvas');
    c.width = Math.round(v.videoWidth * s); c.height = Math.round(v.videoHeight * s);
    c.getContext('2d').drawImage(v, 0, 0, c.width, c.height);
    c.toBlob((b) => b.arrayBuffer().then((buf) => {
      const u8 = new Uint8Array(buf);
      const words = new Int32Array(1 + ((u8.length + 3) >> 2));
      words[0] = u8.length;
      new Uint8Array(words.buffer, 4).set(u8);
      resolve(words);
    }), 'image/jpeg', 0.92);
  };
  v.addEventListener('seeked', draw);
  v.currentTime = t;
})""",
)
private external fun grabFrame(v: JsAny, t: Double, maxDim: Int): Promise<JsAny>

@JsFun("(a, i) => a[i]")
private external fun wordAt(a: JsAny, i: Int): Int

actual object VideoFrames {
    actual suspend fun extract(file: PlatformFile, maxDim: Int, onProgress: (Float) -> Unit): List<RgbaImage> {
        val video = try {
            openVideo(file.file).await<JsAny>()
        } catch (e: Throwable) {
            throw IllegalStateException("This browser cannot play that video. Try an MP4 (H.264) or WebM.")
        }
        try {
            val length = duration(video)
            require(length.isFinite() && length > 0) { "Could not read the video's length" }
            val n = frameCountFor(length)
            val frames = ArrayList<RgbaImage>(n)
            for (i in 0 until n) {
                val words = grabFrame(video, length * (i + 0.5) / n, maxDim).await<JsAny>()
                val size = wordAt(words, 0)
                val bytes = ByteArray(size)
                var k = 0
                var w = 1
                while (k < size) {
                    val x = wordAt(words, w++)
                    var shift = 0
                    while (shift < 32 && k < size) { bytes[k++] = (x shr shift).toByte(); shift += 8 }
                }
                frames += ImageCodec.decode(bytes)
                onProgress((i + 1f) / n)
            }
            return frames
        } finally {
            closeVideo(video)
        }
    }
}
