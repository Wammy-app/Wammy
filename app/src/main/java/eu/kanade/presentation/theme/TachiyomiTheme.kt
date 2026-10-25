package eu.kanade.presentation.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.presentation.theme.colorscheme.AmberColorScheme
import eu.kanade.presentation.theme.colorscheme.AsukaColorScheme
import eu.kanade.presentation.theme.colorscheme.BaseColorScheme
import eu.kanade.presentation.theme.colorscheme.ItsukaColorScheme
import eu.kanade.presentation.theme.colorscheme.KanadeColorScheme
import eu.kanade.presentation.theme.colorscheme.MamimiColorScheme
import eu.kanade.presentation.theme.colorscheme.MikuColorScheme
import eu.kanade.presentation.theme.colorscheme.MionColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetColorScheme
import eu.kanade.presentation.theme.colorscheme.MonetCompatColorScheme
import eu.kanade.presentation.theme.colorscheme.RemonColorScheme
import eu.kanade.presentation.theme.colorscheme.RikkaColorScheme
import eu.kanade.presentation.theme.colorscheme.SakuraColorScheme
import eu.kanade.presentation.theme.colorscheme.ShanaColorScheme
import eu.kanade.presentation.theme.colorscheme.SkyColorScheme
import eu.kanade.presentation.theme.colorscheme.TachiyomiColorScheme
import eu.kanade.presentation.theme.colorscheme.TotoroColorScheme
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@androidx.annotation.Keep
@Composable
fun TachiyomiTheme(
    appTheme: AppTheme? = null,
    amoled: Boolean? = null,
    seedColor: Int? = null,
    content: @Composable () -> Unit,
) {
    val uiPreferences = Injekt.get<UiPreferences>()
    val prefAppTheme by uiPreferences.appTheme.collectAsState()
    val prefIsAmoled by uiPreferences.themeDarkAmoled.collectAsState()
    val themeMode by uiPreferences.themeMode.collectAsState()
    BaseTachiyomiTheme(
        appTheme = appTheme ?: prefAppTheme,
        isAmoled = amoled ?: prefIsAmoled,
        themeMode = themeMode,
        seedColor = seedColor,
        content = content,
    )
}

@Composable
fun TachiyomiPreviewTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) = BaseTachiyomiTheme(appTheme, isAmoled, eu.kanade.domain.ui.model.ThemeMode.SYSTEM, content = content)

@Composable
private fun BaseTachiyomiTheme(
    appTheme: AppTheme,
    isAmoled: Boolean,
    themeMode: eu.kanade.domain.ui.model.ThemeMode,
    seedColor: Int? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isSystemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        eu.kanade.domain.ui.model.ThemeMode.LIGHT -> false
        eu.kanade.domain.ui.model.ThemeMode.DARK -> true
        eu.kanade.domain.ui.model.ThemeMode.SYSTEM -> isSystemDark
    }
    MaterialExpressiveTheme(
        colorScheme = remember(appTheme, isDark, isAmoled, seedColor) {
            if (seedColor != null) {
                val scheme = MonetCompatColorScheme(Color(seedColor))
                scheme.getColorScheme(isDark, isAmoled, overrideDarkSurfaceContainers = false)
            } else {
                getThemeColorScheme(
                    context = context,
                    appTheme = appTheme,
                    isDark = isDark,
                    isAmoled = isAmoled,
                )
            }
        },
        content = content,
    )
}

private fun getThemeColorScheme(
    context: Context,
    appTheme: AppTheme,
    isDark: Boolean,
    isAmoled: Boolean,
): ColorScheme {
    val isExpressive = appTheme == AppTheme.EXPRESSIVE || appTheme == AppTheme.MONET
    val colorScheme = if (isExpressive) {
        MonetColorScheme(context)
    } else {
        colorSchemes.getOrDefault(appTheme, TotoroColorScheme)
    }
    return colorScheme.getColorScheme(
        isDark = isDark,
        isAmoled = isAmoled,
        overrideDarkSurfaceContainers = !isExpressive,
    )
}

private val colorSchemes: Map<AppTheme, BaseColorScheme> = mapOf(
    AppTheme.DEFAULT to TotoroColorScheme,
    AppTheme.MIKU to MikuColorScheme,
    AppTheme.ASUKA to AsukaColorScheme,
    AppTheme.MION to MionColorScheme,
    AppTheme.RIKKA to RikkaColorScheme,
    AppTheme.SAKURA to SakuraColorScheme,
    AppTheme.MAMIMI to MamimiColorScheme,
    AppTheme.KANADE to KanadeColorScheme,
    AppTheme.ITSUKA to ItsukaColorScheme,
    AppTheme.SHANA to ShanaColorScheme,
    AppTheme.LIME to RemonColorScheme,
    AppTheme.AMBER to AmberColorScheme,
    AppTheme.SKY to SkyColorScheme,
)
