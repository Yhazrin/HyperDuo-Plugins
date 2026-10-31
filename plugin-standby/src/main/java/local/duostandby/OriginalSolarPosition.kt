package local.duostandby

import kotlin.math.abs

/** ClockPoster 0x37440..0x3771c -> 0x4473c / 0x44860.
 * This accepts original almanac outputs, not an invented wall-clock approximation.
 * Geographic events/elevation still require the original GEO implementation or reference input.
 */
object OriginalSolarPosition {
    data class AlmanacInput(
        val nowSeconds: Double,
        val solarNoonSeconds: Double,
        val middleSunsetSeconds: Double,
        val solarMidnightSeconds: Double,
        val currentElevationDegrees: Float,
        val solarNoonElevationDegrees: Float,
        val solarMidnightElevationDegrees: Float
    )
    fun percentComplete(input: AlmanacInput): Float {
        val phase = when {
            input.nowSeconds > input.solarMidnightSeconds -> 0
            input.nowSeconds > input.middleSunsetSeconds -> 3
            input.nowSeconds > input.solarNoonSeconds -> 2
            else -> 1
        } // Strict > comparisons from 0x447b4, 0x447e0, 0x4480c.
        val denominator = if (input.nowSeconds < input.middleSunsetSeconds)
            input.solarNoonElevationDegrees else input.solarMidnightElevationDegrees
        require(denominator != 0f && denominator.isFinite()) { "Source almanac denominator unavailable" }
        val ratio = abs(input.currentElevationDegrees / denominator)
        val within = if ((phase and 1) == 0) 1f - ratio else ratio
        val base = when (phase) { 0 -> .75f; 1 -> 0f; 2 -> .25f; else -> .5f }
        return within * .25f + base // Separate Float multiply + add, 0x4499c..0x449a4.
    }
}
