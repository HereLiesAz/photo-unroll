package com.hereliesaz.photounroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.photounroll.imaging.PhotoAdjust
import com.hereliesaz.photounroll.ui.GuideEditor
import com.hereliesaz.photounroll.ui.Ink
import com.hereliesaz.photounroll.ui.MIN_TARGET_DP
import com.hereliesaz.photounroll.ui.PhotoUnrollTheme
import com.hereliesaz.photounroll.ui.RotatePreview
import com.hereliesaz.photounroll.ui.StripViewer
import com.hereliesaz.photounroll.ui.WarpEditor
import com.hereliesaz.photounroll.ui.ZoomableImage
import com.hereliesaz.photounroll.ui.snapAngle
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val TARGET = MIN_TARGET_DP.dp

@Composable
fun App() = PhotoUnrollTheme {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    val picker = rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) state.import(files)
    }
    val videoPicker = rememberFilePickerLauncher(type = FileKitType.Video, mode = FileKitMode.Single) { file ->
        if (file != null) state.importVideo(file)
    }
    val save = rememberJpegSaver()
    val addPhotos = { picker.launch() }
    val addVideo = { videoPicker.launch() }

    Column(Modifier.fillMaxSize().background(Ink.Black).safeDrawingPadding().padding(horizontal = 12.dp)) {
        ModeTabs(state)
        state.progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Ink.Bone,
                trackColor = Ink.Ash,
            )
        }
        state.error?.let { Text("— $it", color = Ink.Smoke, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp)) }

        val result = state.result
        when {
            result != null -> ResultView(result, onBack = { state.result = null }, onSave = {
                scope.launch { save(state.encode(result), result.mode.label.lowercase()) }
            })
            state.photos.isEmpty() -> Empty(state, addPhotos, addVideo, Modifier.weight(1f))
            else -> Editor(state, addPhotos, addVideo, Modifier.weight(1f))
        }
    }
}

/** A text tab at least [TARGET] tall, so a thumb can hit it. */
@Composable
private fun TabLabel(label: String, selected: Boolean, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = TARGET)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Ink.Bone else Ink.Smoke,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 2.sp,
            fontSize = 14.sp,
        )
        if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Ink.Bone))
    }
}

@Composable
private fun ModeTabs(state: AppState) {
    Row(Modifier.fillMaxWidth()) {
        Mode.entries.forEach { m ->
            TabLabel(m.label, m == state.mode, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                state.mode = m; state.result = null
            }
        }
    }
}

@Composable
private fun Empty(state: AppState, addPhotos: () -> Unit, addVideo: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            when (state.mode) {
                Mode.Unroll ->
                    "Walk around the cylinder.\nOne photo every 30–45°, same height, same distance.\nIt will lie flat for you."
                Mode.Panorama ->
                    "Stand still. Turn.\nOne photo every 30–40°, overlapping by a third.\nThe horizon never ends."
            },
            color = Ink.Smoke,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp,
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("PHOTOS", enabled = !state.busy, onClick = addPhotos)
            BigButton("VIDEO", enabled = !state.busy, onClick = addVideo)
        }
    }
}

@Composable
private fun BigButton(label: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        border = BorderStroke(1.dp, if (enabled) Ink.Bone else Ink.Ash),
    ) { Text(label, letterSpacing = 3.sp, fontSize = 15.sp) }
}

@Composable
private fun Editor(state: AppState, addPhotos: () -> Unit, addVideo: () -> Unit, modifier: Modifier) {
    val photo = state.current ?: return
    val haptics = LocalHapticFeedback.current
    val tick = remember(haptics) { { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
    // Rotation while the finger (or slider) is down; the real resample happens on release.
    var liveAngle by remember(photo, photo.adjust.rotationDeg) { mutableStateOf(photo.adjust.rotationDeg) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Tool.entries.forEach { t ->
                val label = if (t == Tool.Main && state.mode == Mode.Panorama) "VIEW" else t.label
                TabLabel(label, t == state.tool, modifier = Modifier.weight(1f)) { state.tool = t }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.tool == Tool.Rotate ->
                    RotatePreview(
                        photo.originalBitmap, liveAngle,
                        onAngle = { liveAngle = it },
                        onCommit = { state.setAdjust(photo, photo.adjust.copy(rotationDeg = liveAngle)) },
                        viewKey = photo, onHaptic = tick, modifier = Modifier.fillMaxSize(),
                    )
                state.tool == Tool.Warp ->
                    WarpEditor(
                        photo.rotatedBitmap, photo.adjust.quad,
                        { state.setAdjust(photo, photo.adjust.copy(quad = it)) },
                        viewKey = photo, onHaptic = tick, modifier = Modifier.fillMaxSize(),
                    )
                state.mode == Mode.Unroll ->
                    GuideEditor(photo.bitmap, photo.guides, { photo.guides = it }, viewKey = photo, onHaptic = tick, modifier = Modifier.fillMaxSize())
                else ->
                    ZoomableImage(photo.bitmap, viewKey = photo, modifier = Modifier.fillMaxSize())
            }
            Hint(state.tool, state.mode)
        }
        Spacer(Modifier.height(8.dp))
        Thumbnails(state, addPhotos, addVideo)
        Spacer(Modifier.height(4.dp))

        when {
            state.tool == Tool.Rotate -> {
                Text("ROTATE ${((liveAngle * 10).roundToInt() / 10f)}°", color = Ink.Smoke, letterSpacing = 2.sp, fontSize = 14.sp)
                Slider(
                    value = liveAngle,
                    onValueChange = { v ->
                        val snapped = snapAngle(v)
                        if (snapped != null && snapped != liveAngle) tick()
                        liveAngle = snapped ?: v
                    },
                    onValueChangeFinished = { state.setAdjust(photo, photo.adjust.copy(rotationDeg = liveAngle)) },
                    valueRange = -180f..180f,
                    colors = SliderDefaults.colors(thumbColor = Ink.Bone, activeTrackColor = Ink.Bone, inactiveTrackColor = Ink.Ash),
                )
                ActionRow {
                    fun turn(by: Float) {
                        val a = ((photo.adjust.rotationDeg + by + 540f) % 360f) - 180f
                        state.setAdjust(photo, photo.adjust.copy(rotationDeg = a))
                    }
                    TextButton(onClick = { turn(-90f) }) { Text("−90°") }
                    TextButton(onClick = { turn(90f) }) { Text("+90°") }
                    TextButton(onClick = { state.setAdjust(photo, photo.adjust.copy(rotationDeg = 0f)) }) { Text("RESET") }
                    TextButton(onClick = state::applyAdjustToAll, enabled = !state.busy) { Text("APPLY TO ALL") }
                }
            }
            state.tool == Tool.Warp -> ActionRow {
                TextButton(onClick = { state.setAdjust(photo, photo.adjust.copy(quad = PhotoAdjust.FULL_FRAME)) }) { Text("RESET") }
                TextButton(onClick = state::applyAdjustToAll, enabled = !state.busy) { Text("APPLY TO ALL") }
            }
            state.mode == Mode.Unroll -> {
                LabeledSlider("ARC ±${state.unrollAngle.roundToInt()}°", state.unrollAngle, 20f..85f) { state.unrollAngle = it }
                ActionRow {
                    TextButton(onClick = state::autoGuides, enabled = !state.busy) { Text("AUTO-DETECT") }
                    TextButton(onClick = state::applyGuidesToAll, enabled = !state.busy) { Text("APPLY TO ALL") }
                    TextButton(onClick = state::arrange, enabled = !state.busy && state.canArrange) { Text("ORDER") }
                }
            }
            else -> {
                LabeledSlider("LENS FOV ${state.hfov.roundToInt()}°", state.hfov, 30f..120f) { state.hfov = it }
                ActionRow {
                    TextButton(onClick = state::arrange, enabled = !state.busy && state.canArrange) { Text("ORDER") }
                }
            }
        }

        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.fullTurn,
                onCheckedChange = { state.fullTurn = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink.Black, checkedTrackColor = Ink.Bone,
                    uncheckedThumbColor = Ink.Smoke, uncheckedTrackColor = Ink.Coal, uncheckedBorderColor = Ink.Smoke,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text("FULL 360°", color = Ink.Smoke, letterSpacing = 2.sp, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            BigButton(
                state.mode.action.uppercase(),
                enabled = !state.busy && (state.mode == Mode.Panorama || state.photos.all { it.guides.isValid }),
                onClick = state::process,
            )
        }
    }
}

/** One line of what the fingers can do here; fades with the first interaction would be nicer, but a line costs nothing. */
@Composable
private fun Hint(tool: Tool, mode: Mode) {
    val text = when {
        tool == Tool.Rotate -> "twist with two fingers · pinch to zoom"
        tool == Tool.Warp -> "drag a corner · pinch to zoom"
        mode == Mode.Unroll -> "drag a handle · pinch to zoom"
        else -> "pinch to zoom"
    }
    Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomCenter) {
        Text(text, color = Ink.Smoke, fontSize = 12.sp, letterSpacing = 1.sp)
    }
}

/** Buttons that scroll sideways rather than wrap or clip on a narrow screen. */
@Composable
private fun ActionRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Thumbnails(state: AppState, addPhotos: () -> Unit, addVideo: () -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(state.photos) { i, p ->
            Box(
                Modifier
                    .size(80.dp)
                    .border(if (i == state.selected) 2.dp else 1.dp, if (i == state.selected) Ink.Bone else Ink.Ash)
                    .clickable { state.selected = i },
            ) {
                Image(p.bitmap, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                // Removal is a separate, full-size target in the corner, away from the selecting tap.
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(TARGET - 8.dp)
                        .clickable(enabled = !state.busy, role = Role.Button) { state.remove(i) },
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Text(
                        "×",
                        color = Ink.Bone,
                        fontSize = 20.sp,
                        modifier = Modifier.background(Ink.Black.copy(alpha = 0.65f)).padding(horizontal = 8.dp),
                    )
                }
            }
        }
        item { AddTile("+ PHOTOS", !state.busy, addPhotos) }
        item { AddTile("+ VIDEO", !state.busy, addVideo) }
    }
}

@Composable
private fun AddTile(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(80.dp)
            .border(1.dp, if (enabled) Ink.Smoke else Ink.Ash)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Ink.Bone else Ink.Smoke, fontSize = 12.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Ink.Smoke, letterSpacing = 2.sp, fontSize = 14.sp)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Ink.Bone, activeTrackColor = Ink.Bone, inactiveTrackColor = Ink.Ash),
        )
    }
}

@Composable
private fun ResultView(result: Result, onBack: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            StripViewer(result.bitmap, result.rendered.wraps, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.BottomCenter) {
                Text("drag · pinch or double-tap to zoom", color = Ink.Smoke, fontSize = 12.sp, letterSpacing = 1.sp)
            }
        }
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text("BACK", fontSize = 15.sp) }
            Spacer(Modifier.weight(1f))
            Text(
                "${result.rendered.image.width}×${result.rendered.image.height}" +
                    if (result.rendered.wraps) "  ·  seamless" else "",
                color = Ink.Smoke,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            BigButton("SAVE JPEG", onClick = onSave)
        }
    }
}
