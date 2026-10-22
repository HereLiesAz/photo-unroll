package com.hereliesaz.photounroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.download
import kotlinx.coroutines.launch

@Composable
actual fun rememberJpegSaver(): (bytes: ByteArray, baseName: String) -> Unit {
    val scope = rememberCoroutineScope()
    return { bytes, name -> scope.launch { FileKit.download(bytes, "$name.jpg") } }
}
