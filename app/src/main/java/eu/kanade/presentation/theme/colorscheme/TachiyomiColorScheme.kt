package eu.kanade.presentation.theme.colorscheme

import androidx.compose.material3.ColorScheme

internal object TachiyomiColorScheme : BaseColorScheme() {
    override val darkScheme: ColorScheme
        get() = TotoroColorScheme.darkScheme

    override val lightScheme: ColorScheme
        get() = TotoroColorScheme.lightScheme
}
