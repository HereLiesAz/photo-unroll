package com.hereliesaz.photounroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.hereliesaz.photounroll.imaging.CylinderGuides
import com.hereliesaz.photounroll.imaging.EdgeSuggester
import com.hereliesaz.photounroll.imaging.ImageCodec
import com.hereliesaz.photounroll.imaging.Pipelines
import com.hereliesaz.photounroll.imaging.PhotoAdjust
import com.hereliesaz.photounroll.video.VIDEO_FRAME_MAX_DIM
import com.hereliesaz.photounroll.video.VideoFrames
import com.hereliesaz.photounroll.imaging.Rendered
import com.hereliesaz.photounroll.imaging.RgbaImage
import com.hereliesaz.photounroll.imaging.loadPhoto
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Mode(val label: String, val action: String) {
    Unroll("UNROLL", "Unroll"),
    Panorama("PANORAMA", "Stitch"),
}

/**
 * One imported photo. Guides are per photo because the object drifts in frame as you walk.
 * [original] is never modified; [image] is it after [adjust] (rotation + perspective), and is
 * what guides, unrolling and stitching see. [fromVideo] frames are already in capture order. [rotatedBitmap] is the rotation alone, the canvas
 * the warp corners are picked on.
 */
class Photo(val name: String, val original: RgbaImage, guides: CylinderGuides, val fromVideo: Boolean = false) {
    var guides by mutableStateOf(guides)
    var adjust by mutableStateOf(PhotoAdjust())
        internal set
    var image by mutableStateOf(original)
        internal set
    var bitmap by mutableStateOf(ImageCodec.toImageBitmap(original))
        internal set
    var rotatedBitmap by mutableStateOf(bitmap)
        internal set
    val originalBitmap: ImageBitmap = bitmap
    internal var adjustJob: Job? = null
}

/** What the editor canvas is for: guides (or plain view in panorama), rotation, or perspective. */
enum class Tool(val label: String) { Main("GUIDES"), Rotate("ROTATE"), Warp("WARP") }

class Result(val rendered: Rendered, val bitmap: ImageBitmap, val mode: Mode)

/** All UI state. Heavy work runs on [Dispatchers.Default]; results land back in snapshot state. */
class AppState(private val scope: CoroutineScope) {
    var mode by mutableStateOf(Mode.Unroll)
    val photos = mutableStateListOf<Photo>()
    var selected by mutableStateOf(0)
    var tool by mutableStateOf(Tool.Main)

    /** Half-arc kept from each walk-around photo. */
    var unrollAngle by mutableStateOf(60f)

    /** Lens horizontal field of view for panorama reprojection. */
    var hfov by mutableStateOf(68f)

    /** Close the last photo onto the first so the output tiles seamlessly. */
    var fullTurn by mutableStateOf(true)

    var progress by mutableStateOf<Float?>(null)
        private set
    var result by mutableStateOf<Result?>(null)
    var error by mutableStateOf<String?>(null)

    private var job: Job? = null
    val busy get() = progress != null
    val current: Photo? get() = photos.getOrNull(selected)

    /** Ordering is only meaningful for loose photos; video frames arrive in sequence. */
    val canArrange get() = photos.size >= 3 && photos.none { it.fromVideo }

    /** Imports photos, then puts them in capture order (see [arrange]). */
    fun import(files: List<PlatformFile>) = task {
        files.forEachIndexed { i, file ->
            progress = 0.5f * i / files.size
            val bytes = file.readBytes()
            val photo = withContext(Dispatchers.Default) {
                val img = loadPhoto(bytes)
                Photo(file.name, img, EdgeSuggester.suggest(img))
            }
            photos += photo
        }
        arrangeNow(from = 0.5f)
    }

    /**
     * Imports a video of a slow lap around the subject as a run of ordered frames. One set of
     * guides (the median of every frame's own guess) is shared by all frames: walking in a
     * circle keeps the subject roughly where it was, and one steady guess beats forty noisy ones.
     */
    fun importVideo(file: PlatformFile) = task {
        val frames = VideoFrames.extract(file, VIDEO_FRAME_MAX_DIM) { progress = 0.6f * it }
        val guesses = withContext(Dispatchers.Default) { frames.map { EdgeSuggester.suggest(it) } }
        val shared = CylinderGuides.median(guesses)
        val base = file.name.substringBeforeLast('.')
        frames.forEachIndexed { i, img ->
            progress = 0.6f + 0.4f * i / frames.size
            photos += Photo("$base #${i + 1}", img, shared, fromVideo = true)
        }
    }

    fun remove(index: Int) {
        photos.removeAt(index)
        selected = selected.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    }

    /**
     * Reorders the photos by how well their flattened strips overlap, so it no longer matters
     * what order the picker returned them in. Leaves video frames alone (already in order).
     */
    fun arrange() = task { arrangeNow(from = 0f) }

    private suspend fun arrangeNow(from: Float) {
        if (photos.size < 3 || photos.any { it.fromVideo }) return
        photos.forEach { it.adjustJob?.join() }
        val snapshot = photos.toList()
        val m = mode
        val report: (Float) -> Unit = { progress = from + (1f - from) * it }
        val order = withContext(Dispatchers.Default) {
            when (m) {
                Mode.Unroll -> Pipelines.arrangeCylinder(
                    snapshot.map { it.image }, snapshot.map { it.guides }, unrollAngle, report,
                )
                Mode.Panorama -> Pipelines.arrangePanorama(snapshot.map { it.image }, hfov, report)
            }
        }
        val keep = current
        photos.clear()
        photos.addAll(order.map { snapshot[it] })
        selected = photos.indexOf(keep).coerceAtLeast(0)
    }

    fun autoGuides() {
        val p = current ?: return
        task { p.guides = withContext(Dispatchers.Default) { EdgeSuggester.suggest(p.image) } }
    }

    /**
     * Re-renders [photo] with [adjust] off the main thread. Superseded renders are cancelled,
     * so dragging a slider only pays for the last value.
     */
    fun setAdjust(photo: Photo, adjust: PhotoAdjust) {
        if (adjust == photo.adjust) return
        photo.adjust = adjust
        photo.adjustJob?.cancel()
        photo.adjustJob = scope.launch {
            val rotationOnly = adjust.copy(quad = PhotoAdjust.FULL_FRAME)
            val (img, bmp, rot) = withContext(Dispatchers.Default) {
                val img = adjust.apply(photo.original)
                val rot = if (rotationOnly == adjust) img else rotationOnly.apply(photo.original)
                val bmp = ImageCodec.toImageBitmap(img)
                Triple(img, bmp, if (rot === img) bmp else ImageCodec.toImageBitmap(rot))
            }
            photo.image = img
            photo.bitmap = bmp
            photo.rotatedBitmap = rot
        }
    }

    fun applyAdjustToAll() {
        val a = current?.adjust ?: return
        photos.forEach { setAdjust(it, a) }
    }

    fun applyGuidesToAll() {
        val g = current?.guides ?: return
        photos.forEach { it.guides = g }
    }

    fun process() {
        if (photos.isEmpty()) return
        val m = mode
        val trim = photos.any { it.fromVideo }
        task {
            // A rotation or warp may still be rendering; unroll what the user last set.
            photos.forEach { it.adjustJob?.join() }
            val snapshot = photos.toList()
            val rendered = withContext(Dispatchers.Default) {
                val report: (Float) -> Unit = { progress = it }
                when (m) {
                    Mode.Unroll -> Pipelines.unrollCylinder(
                        snapshot.map { it.image }, snapshot.map { it.guides }, unrollAngle, fullTurn, trim, report,
                    )
                    Mode.Panorama -> Pipelines.panorama(snapshot.map { it.image }, hfov, fullTurn, trim, report)
                }
            }
            result = Result(rendered, withContext(Dispatchers.Default) { ImageCodec.toImageBitmap(rendered.image) }, m)
        }
    }

    suspend fun encode(r: Result): ByteArray = withContext(Dispatchers.Default) { r.rendered.toJpeg() }

    /** Runs one task at a time with progress and error reporting. */
    private fun task(block: suspend () -> Unit) {
        if (job?.isActive == true) return
        error = null
        progress = 0f
        job = scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                progress = null
            }
        }
    }
}
