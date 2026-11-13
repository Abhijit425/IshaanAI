package com.ishaan.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

val MedBlue = Color(0xFF1565C0)
val MedBlueDark = Color(0xFF0D47A1)
val MedSky = Color(0xFFE3F2FD)
val UrgentRed = Color(0xFFC62828)
val ReviewAmber = Color(0xFFEF6C00)
val OkGreen = Color(0xFF2E7D32)

private val Light = lightColorScheme(
    primary = MedBlue, onPrimary = Color.White, primaryContainer = MedSky, onPrimaryContainer = MedBlueDark,
    secondary = Color(0xFF00838F), secondaryContainer = Color(0xFFE0F7FA),
    background = Color(0xFFF5F7FA), surface = Color.White, surfaceVariant = Color(0xFFECEFF1),
    error = UrgentRed,
)
private val Dark = darkColorScheme(
    primary = Color(0xFF90CAF9), onPrimary = Color(0xFF0D2A4D), primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color(0xFFD6E7FB),
    secondary = Color(0xFF80DEEA), background = Color(0xFF0F1418), surface = Color(0xFF161C22), surfaceVariant = Color(0xFF232B33),
    error = Color(0xFFEF9A9A),
)

@Composable
fun IshaanTheme(darkMode: String = "SYSTEM", largeText: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (darkMode) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }
    val base = Typography()
    val f = if (largeText) 1.18f else 1f
    fun TextUnit.s() = (value * f).sp
    val typo = Typography(
        displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize.s()),
        headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize.s()),
        titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize.s()),
        titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize.s()),
        titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize.s()),
        bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize.s()),
        bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize.s()),
        bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize.s()),
        labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize.s()),
        labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize.s()),
        labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize.s()),
    )
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = typo, content = content)
}
