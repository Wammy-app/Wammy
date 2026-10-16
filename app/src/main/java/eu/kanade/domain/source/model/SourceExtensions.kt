package eu.kanade.domain.source.model

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import tachiyomi.domain.source.model.Source
import eu.kanade.tachiyomi.source.Source as ApiSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val adultRegex = Regex(
    "(hentai|porn[a-z]*|smut|erotic[ao]?|eromanga|doujin(shi)?|xxx+|fakku|hitomi|pururin|tsumino|asmhentai|hentaifox|ahottie|ahoty|akuma|4kthd|fourkhd|allporncomics|pornhwa|nhentai|rule34|luscious|hiperdex|uncensored|coomer|buondua|cosplaytele|foamgirl|kiutaku|fanservice|(?<![a-zA-Z])adult(?![a-zA-Z]))",
    RegexOption.IGNORE_CASE
)

val Source.installedExtension: Extension.Installed?
    get() {
        return Injekt.get<ExtensionManager>()
            .installedExtensionsFlow
            .value
            .find { ext -> ext.sources.any { it.id == id } }
    }

val ApiSource.installedExtension: Extension.Installed?
    get() {
        return Injekt.get<ExtensionManager>()
            .installedExtensionsFlow
            .value
            .find { ext -> ext.sources.any { it.id == id } }
    }

fun Source.isAdultSource(): Boolean {
    val ext = this.installedExtension
    if (ext != null) {
        if (ext.isAdult) return true
        val available = runCatching {
            Injekt.get<ExtensionManager>()
                .availableExtensionsFlow
                .value
                .find { it.pkgName == ext.pkgName }
        }.getOrNull()
        if (available?.isAdult == true) return true

        val textToMatch = "${ext.name} ${ext.pkgName} ${this.name}"
        if (adultRegex.containsMatchIn(textToMatch)) return true
        return false
    }
    return adultRegex.containsMatchIn(this.name)
}

fun ApiSource.isAdultSource(): Boolean {
    val ext = this.installedExtension
    if (ext != null) {
        if (ext.isAdult) return true
        val available = runCatching {
            Injekt.get<ExtensionManager>()
                .availableExtensionsFlow
                .value
                .find { it.pkgName == ext.pkgName }
        }.getOrNull()
        if (available?.isAdult == true) return true

        val textToMatch = "${ext.name} ${ext.pkgName} ${this.name}"
        if (adultRegex.containsMatchIn(textToMatch)) return true
        return false
    }
    return adultRegex.containsMatchIn(this.name)
}
