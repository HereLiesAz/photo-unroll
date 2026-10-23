package com.hereliesaz.photounroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.photounroll.imaging.PhotoAdjust
import com.hereliesaz.photounroll.ui.GuideEditor
import com.hereliesaz.photounroll.ui.RotatePreview
import com.hereliesaz.photounroll.ui.WarpEditor
import com.hereliesaz.photounroll.ui.Ink
import com.hereliesaz.photounroll.ui.PhotoUnrollTheme
import com.hereliesaz.photounroll.ui.StripViewer
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun App() = PhotoUnrollTheme {
    val scope = rememberCoroutineScope()
    val state = remember { AppState(scope) }
    val picker = rememberFilePickerLauncher(type = FileKitType.Image, mode = FileKitMode.Multiple()) { files ->
        if (!files.isNullOrEmpty()) state.import(files)
    }
    val save = rememberJpegSaver()

    Column(Modifier.fillMaxSize().background(Ink.Black).safeDrawingPadding().padding(12.dp)) {
        Header(state, onAdd = { picker.launch() })
        Spacer(Modifier.height(8.dp))
        state.progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Ink.Bone,
                trackColor = Ink.Ash,
            )
        }
        state.error?.let { Text("— $it", color = Ink.Smoke, fontSize = 13.sp) }

        val result = state.result
        when {
            result != null -> ResultView(result, onBack = { state.result = null }, onSave = {
                scope.launch { save(state.encode(result), result.mode.label.lowercase()) }
            })
            state.photos.isEmpty() -> Empty(state.mode, Modifier.weight(1f))
            else -> Editor(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Header(state: AppState, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mode.entries.forEach { m ->
            Text(
                m.label,
                color = if (m == state.mode) Ink.Bone else Ink.Smoke,
                fontWeight = if (m == state.mode) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 3.sp,
                modifier = Modifier
                    .clickable(enabled = !state.busy) { state.mode = m; state.result = null }
                    .padding(horizontal = 8.dp, vertical = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAdd, enabled = !state.busy && state.result == null) { Text("+ PHOTOS") }
    }
}

@Composable
private fun Empty(mode: Mode, modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            when (mode) {
                Mode.Unroll ->
                    "Walk around the cylinder.\nOne photo every 30–45°, same height, same distance.\nIt will lie flat for you."
                Mode.Panorama ->
                    "Stand still. Turn.\nOne photo every 30–40°, overlapping by a third.\nThe horizon never ends."
            },
            color = Ink.Smoke,
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun Editor(state: AppState, modifier: Modifier) {
    val photo = state.current ?: return
    // Slider position while dragging; committed (and resampled) on release.
    var liveAngle by remember(photo, photo.adjust.rotationDeg) { mutableStateOf(photo.adjust.rotationDeg) }
    Column(modifier) {
        Row {
            Tool.entries.forEach { t ->
                val label = if (t == Tool.Main && state.mode == Mode.Panorama) "VIEW" else t.label
                Text(
                    label,
                    color = if (t == state.tool) Ink.Bone else Ink.Smoke,
                    fontWeight = if (t == state.tool) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 2.sp,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable { state.tool = t }.padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.tool == Tool.Rotate ->
                    RotatePreview(photo.originalBitmap, liveAngle, Modifier.fillMaxSize())
                state.tool == Tool.Warp ->
                    WarpEditor(
                        photo.rotatedBitmap, photo.adjust.quad,
                        { state.setAdjust(photo, photo.adjust.copy(quad = it)) },
                        Modifier.fillMaxSize(),
                    )
                state.mode == Mode.Unroll ->
                    GuideEditor(photo.bitmap, photo.guides, { photo.guides = it }, Modifier.fillMaxSize())
                else ->
                    Image(photo.bitmap, photo.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        Spacer(Modifier.height(8.dp))
        Thumbnails(state)
        Spacer(Modifier.height(8.dp))

        if (state.tool == Tool.Rotate) {
            Column {
                Text("ROTATE ${((liveAngle * 10).roundToInt() / 10f)}°", color = Ink.Smoke, letterSpacing = 2.sp, fontSize = 13.sp)
                Slider(
                    value = liveAngle,
                    onValueChange = { liveAngle = it },
                    onValueChangeFinished = { state.setAdjust(photo, photo.adjust.copy(rotationDeg = liveAngle)) },
                    valueRange = -180f..180f,
                    colors = SliderDefaults.colors(thumbColor = Ink.Bone, activeTrackColor = Ink.Bone, inactiveTrackColor = Ink.Ash),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fun turn(by: Float) {
                    val a = ((photo.adjust.rotationDeg + by + 540f) % 360f) - 180f
                    state.setAdjust(photo, photo.adjust.copy(rotationDeg = a))
                }
                TextButton(onClick = { turn(-90f) }) { Text("−90°") }
                TextButton(onClick = { turn(90f) }) { Text("+90°") }
                TextButton(onClick = { state.setAdjust(photo, photo.adjust.copy(rotationDeg = 0f)) }) { Text("RESET") }
                TextButton(onClick = state::applyAdjustToAll, enabled = !state.busy) { Text("APPLY TO ALL") }
            }
        } else if (state.tool == Tool.Warp) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { state.setAdjust(photo, photo.adjust.copy(quad = PhotoAdjust.FULL_FRAME)) }) { Text("RESET") }
                TextButton(onClick = state::applyAdjustToAll, enabled = !state.busy) { Text("APPLY TO ALL") }
            }
        } else if (state.mode == Mode.Unroll) {
            LabeledSlider("ARC ±${state.unrollAngle.roundToInt()}°", state.unrollAngle, 20f..85f) { state.unrollAngle = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = state::autoGuides, enabled = !state.busy) { Text("AUTO-DETECT") }
                TextButton(onClick = state::applyGuidesToAll, enabled = !state.busy) { Text("APPLY TO ALL") }
            }
        } else {
            LabeledSlider("LENS FOV ${state.hfov.roundToInt()}°", state.hfov, 30f..120f) { state.hfov = it }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.fullTurn,
                onCheckedChange = { state.fullTurn = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Ink.Black, checkedTrackColor = Ink.Bone,
                    uncheckedThumbColor = Ink.Smoke, uncheckedTrackColor = Ink.Coal, uncheckedBorderColor = Ink.Smoke,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text("FULL 360°", color = Ink.Smoke, letterSpacing = 2.sp, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = state::process,
                enabled = !state.busy && (state.mode == Mode.Panorama || state.photos.all { it.guides.isValid }),
                border = BorderStroke(1.dp, Ink.Bone),
            ) { Text(state.mode.action.uppercase(), letterSpacing = 3.sp) }
        }
    }
}

@Composable
private fun Thumbnails(state: AppState) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(state.photos) { i, p ->
            Box(
                Modifier
                    .size(64.dp)
                    .border(1.dp, if (i == state.selected) Ink.Bone else Ink.Ash)
                    .clickable { state.selected = i },
            ) {
                Image(p.bitmap, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Text(
                    "×",
                    color = Ink.Bone,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Ink.Black.copy(alpha = 0.6f))
                        .clickable(enabled = !state.busy) { state.remove(i) }
                        .padding(horizontal = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = Ink.Smoke, letterSpacing = 2.sp, fontSize = 13.sp)
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
        StripViewer(result.bitmap, result.rendered.wraps, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("BACK") }
            Spacer(Modifier.weight(1f))
            Text(
                "${result.rendered.image.width}×${result.rendered.image.height}" +
                    if (result.rendered.wraps) "  ·  seamless" else "",
                color = Ink.Smoke,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onSave, border = BorderStroke(1.dp, Ink.Bone)) { Text("SAVE JPEG", letterSpacing = 3.sp) }
        }
    }
}
