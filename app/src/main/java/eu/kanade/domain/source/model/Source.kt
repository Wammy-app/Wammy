package eu.kanade.domain.source.model

import android.app.Application
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.builtin.BuiltinSourceManager
import tachiyomi.domain.source.model.Source
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

val Source.icon: ImageBitmap?
    get() {
        val builtinIconRes = BuiltinSourceManager.getIconRes(id, name)
        if (builtinIconRes != null) {
            val context = Injekt.get<Application>()
            return ContextCompat.getDrawable(context, builtinIconRes)
                ?.toBitmap()
                ?.asImageBitmap()
        }
        return Injekt.get<ExtensionManager>().getAppIconForSource(id)
            ?.toBitmap()
            ?.asImageBitmap()
    }

val Source.isBuiltin: Boolean
    get() = BuiltinSourceManager.isBuiltin(id)

