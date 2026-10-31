package local.duostandby

import android.content.Context
import android.content.res.Configuration

/** 24A94425 CoreUI DesignLibrary-iOS/iOSRepositories COLORS BOM tree,
 * systemOrangeColor index1. Decoded sRGB ARGB; NOT guessed web/iOS legacy orange.
 * Android UI_MODE_NIGHT maps only standard, nonvibrant catalog traits here.
 * Actual Duo idiom/contrast/vibrancy must still be matched before 1:1 claims.
 */
object OriginalWorldPinColors {
    fun orange(context:Context):Int = if(context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        0xffff9230.toInt() else 0xffff8d28.toInt()
}
