package com.hereliesaz.photounroll.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object Ink {
    val Black = Color(0xFF000000)
    val Coal = Color(0xFF111111)
    val Ash = Color(0xFF2A2A2A)
    val Smoke = Color(0xFF7A7A7A)
    val Bone = Color(0xFFEDEDED)
}

/** Monochrome: one light, one dark, and the greys between. */
@Composable
fun PhotoUnrollTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.Bone,
            onPrimary = Ink.Black,
            secondary = Ink.Smoke,
            onSecondary = Ink.Black,
            background = Ink.Black,
            onBackground = Ink.Bone,
            surface = Ink.Coal,
            onSurface = Ink.Bone,
            surfaceVariant = Ink.Ash,
            onSurfaceVariant = Ink.Smoke,
            outline = Ink.Smoke,
            error = Ink.Bone,
        ),
        content = content,
    )
}
