package com.vitalink.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vitalink.core.model.VitalStatus

object Vita {
    val Teal = Color(0xFF0E9F8E)
    val TealDark = Color(0xFF07695E)
    val Mint = Color(0xFFD7F5EF)
    val Coral = Color(0xFFE5484D)
    val CoralDark = Color(0xFFB4232A)
    val Amber = Color(0xFFF59E0B)
    val Ok = Color(0xFF22B573)
    val Indigo = Color(0xFF4F6BFF)
    val Violet = Color(0xFF7C5CFF)
    val Ink = Color(0xFF14212B)
    val Muted = Color(0xFF6B7785)
    val Surface = Color(0xFFF4F6FB)

    fun status(status: String?): Color = when (status) {
        VitalStatus.EMERGENCY -> Coral
        VitalStatus.ATTENTION -> Amber
        else -> Ok
    }

    fun parse(hex: String?, fallback: Color = Indigo): Color = runCatching {
        Color(android.graphics.Color.parseColor(hex))
    }.getOrDefault(fallback)
}

private val Light = lightColorScheme(
    primary = Vita.Teal,
    onPrimary = Color.White,
    primaryContainer = Vita.Mint,
    onPrimaryContainer = Vita.TealDark,
    secondary = Vita.Indigo,
    secondaryContainer = Color(0xFFE3E8FF),
    onSecondaryContainer = Color(0xFF1D2C8F),
    tertiary = Vita.Violet,
    error = Vita.Coral,
    errorContainer = Color(0xFFFFE3E4),
    onErrorContainer = Vita.CoralDark,
    background = Vita.Surface,
    onBackground = Vita.Ink,
    surface = Color.White,
    onSurface = Vita.Ink,
    surfaceVariant = Color(0xFFEDF0F6),
    onSurfaceVariant = Vita.Muted,
    surfaceContainer = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FC),
    surfaceContainerHigh = Color(0xFFEFF2F8),
    outline = Color(0xFFD5DBE5),
    outlineVariant = Color(0xFFE6EAF1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF3DD6C2),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF0B4F46),
    onPrimaryContainer = Color(0xFFB4F2E7),
    secondary = Color(0xFF9AACFF),
    secondaryContainer = Color(0xFF26337A),
    tertiary = Color(0xFFB9A6FF),
    error = Color(0xFFFF7A7E),
    errorContainer = Color(0xFF5C1518),
    onErrorContainer = Color(0xFFFFDADB),
    background = Color(0xFF0D1318),
    onBackground = Color(0xFFE4E9EE),
    surface = Color(0xFF151D24),
    onSurface = Color(0xFFE4E9EE),
    surfaceVariant = Color(0xFF1F2A33),
    onSurfaceVariant = Color(0xFF9AA7B4),
    surfaceContainer = Color(0xFF151D24),
    surfaceContainerLow = Color(0xFF121920),
    surfaceContainerHigh = Color(0xFF1C262E),
    outline = Color(0xFF34424E),
    outlineVariant = Color(0xFF26323C),
)

private val base = Typography()
private val typography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val NumberStyle = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, letterSpacing = (-2).sp)

@Composable
fun VitaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = typography,
        content = content,
    )
}
