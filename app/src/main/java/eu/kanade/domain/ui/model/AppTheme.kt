package eu.kanade.domain.ui.model

import dev.icerock.moko.resources.StringResource
import tachiyomi.i18n.MR

enum class AppTheme(val titleRes: StringResource?) {
    DEFAULT(MR.strings.theme_totoro),
    EXPRESSIVE(MR.strings.theme_expressive),
    MIKU(MR.strings.theme_miku),
    ASUKA(MR.strings.theme_asuka),
    MION(MR.strings.theme_mion),
    RIKKA(MR.strings.theme_rikka),
    SAKURA(MR.strings.theme_sakura),
    MAMIMI(MR.strings.theme_mamimi),
    KANADE(MR.strings.theme_kanade),
    ITSUKA(MR.strings.theme_itsuka),
    SHANA(MR.strings.theme_shana),
    LIME(MR.strings.theme_lime),
    AMBER(MR.strings.theme_amber),
    SKY(MR.strings.theme_sky),

    // Deprecated
    MONET(null),
    CATPPUCCIN(null),
    GREEN_APPLE(null),
    LAVENDER(null),
    MIDNIGHT_DUSK(null),
    NORD(null),
    STRAWBERRY_DAIQUIRI(null),
    TAKO(null),
    TEALTURQUOISE(null),
    TIDAL_WAVE(null),
    YINYANG(null),
    YOTSUBA(null),
    TOKYONIGHT(null),
    MONOCHROME(null),
    DARK_BLUE(null),
    HOT_PINK(null),
    BLUE(null),
}
