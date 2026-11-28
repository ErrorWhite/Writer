package xyz.qingjian.writer

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import com.itos.coui.menu.ProvideCouiMenuBackdrop
import com.itos.coui.theme.CouiColorScheme
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.CouiTypography

private val serif = FontFamily(Font(R.font.source_han_serif_sc, FontWeight.SemiBold))

internal object WriterTheme {
    val colorScheme: ManuscriptColors
        @Composable get() = ManuscriptColors(CouiTheme.isDark)
    val typography = ManuscriptTypography
    val shapes = ManuscriptShapes
}

internal class ManuscriptColors(dark: Boolean) {
    val primary = if (dark) Color(0xFFB8C6FF) else Color(0xFF3D62DF)
    val onPrimary = if (dark) Color(0xFF122A75) else Color.White
    val primaryContainer = if (dark) Color(0xFF273B77) else Color(0xFFE4EAFF)
    val onPrimaryContainer = if (dark) Color(0xFFE1E8FF) else Color(0xFF172F79)
    val background = if (dark) Color(0xFF151921) else Color(0xFFF0F1F4)
    val surface = if (dark) Color(0xFF1D222C) else Color(0xFFFEFEFF)
    val surfaceContainer = if (dark) Color(0xFF222B3D) else Color(0xFFE9EDF7)
    val surfaceContainerHigh = if (dark) Color(0xFF293246) else Color(0xFFE3E8F5)
    val onSurface = if (dark) Color(0xFFE9EDF9) else Color(0xFF242D42)
    val onBackground = onSurface
    val onSurfaceVariant = if (dark) Color(0xFFBCC6DD) else Color(0xFF49546D)
    val outlineVariant = if (dark) Color(0xFF3B475E) else Color(0xFFD7DEEE)
    val error = if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
    val errorContainer = if (dark) Color(0xFF55201D) else Color(0xFFFFDAD6)
}

internal object ManuscriptTypography {
    val chapterTitle = TextStyle(fontSize = 29.sp, lineHeight = 39.sp, fontFamily = serif, fontWeight = FontWeight.SemiBold)
    val headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontFamily = serif, fontWeight = FontWeight.SemiBold)
    val titleLarge = headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp)
    val titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
    val bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
    val bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp)
    val bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)
    val labelLarge = bodyMedium.copy(fontWeight = FontWeight.Medium)
    val labelMedium = bodySmall.copy(fontWeight = FontWeight.Medium)
    val labelSmall = labelMedium.copy(fontSize = 11.sp)
}

internal object ManuscriptShapes {
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(18.dp)
}

@Composable
internal fun QingjianTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = ManuscriptColors(dark)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    CouiTheme(
        darkTheme = dark,
        colorScheme = CouiColorScheme(
            accent = palette.primary, onAccent = palette.onPrimary, surface = palette.surface,
            container = palette.surfaceContainer, onSurface = palette.onSurface,
            onSurfaceVariant = palette.onSurfaceVariant, outlineVariant = palette.outlineVariant,
            background = palette.background, card = palette.surface
        ),
        typography = CouiTypography(
            body = ManuscriptTypography.bodyLarge, label = ManuscriptTypography.labelMedium,
            dialogTitleFontFamily = serif
        )
    ) {
        MaterialTheme(
            colorScheme = base.copy(
                primary = palette.primary, onPrimary = palette.onPrimary,
                primaryContainer = palette.primaryContainer, onPrimaryContainer = palette.onPrimaryContainer,
                background = palette.background, onBackground = palette.onBackground,
                surface = palette.surface, onSurface = palette.onSurface, onSurfaceVariant = palette.onSurfaceVariant,
                surfaceContainer = palette.surfaceContainer, surfaceContainerHigh = palette.surfaceContainerHigh,
                outlineVariant = palette.outlineVariant, error = palette.error, errorContainer = palette.errorContainer
            ),
            typography = Typography(
                titleLarge = ManuscriptTypography.titleLarge, bodyLarge = ManuscriptTypography.bodyLarge,
                bodyMedium = ManuscriptTypography.bodyMedium, bodySmall = ManuscriptTypography.bodySmall,
                labelLarge = ManuscriptTypography.labelLarge
            )
        ) {
            ProvideCouiMenuBackdrop(isolateContent = false) { backdrop ->
                Box(backdrop.fillMaxSize()) {
                    CompositionLocalProvider(LocalContentColor provides palette.onBackground, content = content)
                }
            }
        }
    }
}
