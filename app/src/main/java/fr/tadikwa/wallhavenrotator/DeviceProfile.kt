package fr.tadikwa.wallhavenrotator

import android.content.Context
import android.content.res.Configuration

object DeviceProfile {
    fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600

    fun resolveOrientation(context: Context, mode: OrientationMode): ResolvedOrientation {
        return when (mode) {
            OrientationMode.PORTRAIT -> ResolvedOrientation.PORTRAIT
            OrientationMode.LANDSCAPE -> ResolvedOrientation.LANDSCAPE
            OrientationMode.AUTO -> {
                if (!isTablet(context)) {
                    ResolvedOrientation.PORTRAIT
                } else if (context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                    ResolvedOrientation.PORTRAIT
                } else {
                    ResolvedOrientation.LANDSCAPE
                }
            }
        }
    }

    fun displayLabel(context: Context, mode: OrientationMode): String {
        val type = if (isTablet(context)) "Tablette" else "Téléphone"
        val resolved = resolveOrientation(context, mode)
        val orientation = if (resolved == ResolvedOrientation.PORTRAIT) "portrait" else "paysage"
        return "$type • $orientation"
    }
}
