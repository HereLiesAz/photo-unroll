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

/** One imported photo. Guides are per photo because the object drifts in frame as you walk. */
class Photo(val name: String, val image: RgbaImage, val bitmap: ImageBitmap, guides: CylinderGuides) {
    var guides by mutableStateOf(guides)
}

class Result(val rendered: Rendered, val bitmap: ImageBitmap, val mode: Mode)

/** All UI state. Heavy work runs on [Dispatchers.Default]; results land back in snapshot state. */
class AppState(private val scope: CoroutineScope) {
    var mode by mutableStateOf(Mode.Unroll)
    val photos = mutableStateListOf<Photo>()
    var selected by mutableStateOf(0)

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

    fun import(files: List<PlatformFile>) = task {
        files.forEachIndexed { i, file ->
            progress = i.toFloat() / files.size
            val bytes = file.readBytes()
            val photo = withContext(Dispatchers.Default) {
                val img = loadPhoto(bytes)
                Photo(file.name, img, ImageCodec.toImageBitmap(img), EdgeSuggester.suggest(img))
            }
            photos += photo
        }
    }

    fun remove(index: Int) {
        photos.removeAt(index)
        selected = selected.coerceIn(0, (photos.size - 1).coerceAtLeast(0))
    }

    fun autoGuides() {
        val p = current ?: return
        task { p.guides = withContext(Dispatchers.Default) { EdgeSuggester.suggest(p.image) } }
    }

    fun applyGuidesToAll() {
        val g = current?.guides ?: return
        photos.forEach { it.guides = g }
    }

    fun process() {
        val snapshot = photos.toList()
        if (snapshot.isEmpty()) return
        val m = mode
        task {
            val rendered = withContext(Dispatchers.Default) {
                val report: (Float) -> Unit = { progress = it }
                when (m) {
                    Mode.Unroll -> Pipelines.unrollCylinder(
                        snapshot.map { it.image }, snapshot.map { it.guides }, unrollAngle, fullTurn, report,
                    )
                    Mode.Panorama -> Pipelines.panorama(snapshot.map { it.image }, hfov, fullTurn, report)
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
