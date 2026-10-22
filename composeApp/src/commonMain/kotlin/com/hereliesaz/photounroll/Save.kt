package com.hereliesaz.photounroll

import androidx.compose.runtime.Composable

/** Returns a function that hands finished JPEG bytes to the platform's save/download flow. */
@Composable
expect fun rememberJpegSaver(): (bytes: ByteArray, baseName: String) -> Unit
