package local.duostandby

import kotlin.math.abs

/** CalendarPoster24A94425 c0d28; enum slots221abd718/748, light221aa9318.
 * c0c24 tests RGB, not alpha. Header is outside this modifier.
 * Color-space and SwiftUI blur/compositing equivalence are NOT established by this port.
 */
object OriginalCalendarCompositing {
    fun useMultiply(style:Int,lowLight:Boolean,lightScheme:Boolean,secondary:Int):Boolean {
        val nearWhite=listOf(16,8,0).all { shift ->
            abs(((secondary ushr shift) and 255)/255.0-1.0)<0.01
        }
        return style==1 && !lowLight && lightScheme && !nearWhite
    }
}
