package com.hh.recall.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF246B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDECE5), onPrimaryContainer = Color(0xFF133F35),
    secondary = Color(0xFF7D6743), secondaryContainer = Color(0xFFE9E4D5), onSecondaryContainer = Color(0xFF4D4433),
    background = Color(0xFFF5F3EC), surface = Color(0xFFFFFDF8),
    surfaceContainer = Color(0xFFEDEBE3), surfaceContainerLow = Color(0xFFF7F5EE),
    onSurface = Color(0xFF283731), onSurfaceVariant = Color(0xFF6D7770), outlineVariant = Color(0xFFDDDCD1),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB5C4BA), onPrimary = Color(0xFF232C26),
    primaryContainer = Color(0xFF39443D), onPrimaryContainer = Color(0xFFE0E7E1),
    secondary = Color(0xFFC6C3B6), secondaryContainer = Color(0xFF3A3D37), onSecondaryContainer = Color(0xFFE3E4DA),
    background = Color(0xFF1D211F), surface = Color(0xFF282E2A), surfaceContainer = Color(0xFF343A36),
    surfaceContainerLow = Color(0xFF242925), onSurface = Color(0xFFE6E8E6), onSurfaceVariant = Color(0xFFB7BEB8),
    outline = Color(0xFF909A92), outlineVariant = Color(0xFF444C46),
)

@Composable
fun RecallTheme(dark: Boolean, content: @Composable () -> Unit) {
    val target = if (dark) Dark else Light
    @Composable fun blend(color: Color): Color = animateColorAsState(color, tween(200), label = "theme").value
    val colors = target.copy(
        primary = blend(target.primary), onPrimary = blend(target.onPrimary),
        primaryContainer = blend(target.primaryContainer), onPrimaryContainer = blend(target.onPrimaryContainer),
        secondaryContainer = blend(target.secondaryContainer), onSecondaryContainer = blend(target.onSecondaryContainer),
        background = blend(target.background), surface = blend(target.surface),
        surfaceContainer = blend(target.surfaceContainer), surfaceContainerLow = blend(target.surfaceContainerLow),
        onSurface = blend(target.onSurface), onSurfaceVariant = blend(target.onSurfaceVariant),
        outlineVariant = blend(target.outlineVariant),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

fun answerColor(dark: Boolean) = if (dark) Color(0xFFA8DDB8) else Color(0xFF246B5B)
fun markingColor(dark: Boolean) = if (dark) Color(0xFFF0A39C) else Color(0xFFBC4B48)
