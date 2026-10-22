package com.hereliesaz.photounroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch

@Composable
actual fun rememberJpegSaver(): (bytes: ByteArray, baseName: String) -> Unit {
    val scope = rememberCoroutineScope()
    // The dialog result arrives later; hold the bytes until the user picks a destination.
    val pending = remember { arrayOfNulls<ByteArray>(1) }
    val launcher = rememberFileSaverLauncher { file ->
        val bytes = pending[0]
        pending[0] = null
        if (file != null && bytes != null) scope.launch { file.write(bytes) }
    }
    return { bytes, name ->
        pending[0] = bytes
        launcher.launch(suggestedName = name, extension = "jpg")
    }
}
