package local.duostandby

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Data-layer transcription of ClockPoster 24A94425, not a guessed astronomy model.
 * Evidence: references/duo-exact-source/world-clock-v15. Native original arithmetic
 * kernel runs separately; final map masks, colors and Duo host layout are not wired.
 */
object OriginalWorldTerminator {
    data class Coordinate(val latitude: Double, val longitude: Double)
    data class Terminator(
        val fractionalDay: Double,
        val declinationDegrees: Double,
        val hourAngleDegrees: Double,
        val northernHemisphereSummer: Boolean,
        val coordinates: List<Coordinate>,
        // nil = original .discrete; otherwise .catmullRomSpline(resolution: Int).
        val splineResolution: Int?
    )
    private val seasonCoefficient = Double.fromBits(0x3fef8fc7e3f1f8fcL)
    private val radiansPerDegree = Double.fromBits(0x3f91df46a2529d39L)
    private val degreesPerRadian = Double.fromBits(0x404ca5dc1a63c1f8L)
    private val amplitude = Double.fromBits(0x4037733333333333L)

    /** b24e8..b28d0: Gregorian year start and whole-second difference in GMT. */
    fun calculate(instant: Instant): Terminator {
        val utc = instant.atOffset(ZoneOffset.UTC)
        val start = utc.withDayOfYear(1).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)
        val seconds = Duration.between(start, instant).seconds
        return calculateDay(seconds.toDouble() / 86400.0 + 1.0)
    }

    /** b21f4..b2430. No replacement declination formula or artificial smoothing. */
    fun calculateDay(day: Double): Terminator {
        require(day.isFinite() && day >= 1.0) { "Expected a finite positive UTC day of year" }
        val declination = sin(((day + 284.0) * seasonCoefficient) * radiansPerDegree) * amplitude
        val fraction = day - day.toLong().toDouble()
        val hourAngle = (fraction * 24.0 + -12.0) * 15.0
        val tangent = tan(declination * radiansPerDegree)
        val discrete = abs(declination) <= 10.0
        val stride = if (discrete) 1 else 3
        val points = ArrayList<Coordinate>(if (discrete) 361 else 121)
        for (index in 0..360 step stride) {
            val longitude = index.toDouble() + -180.0
            val latitude = if (tangent == 0.0) 0.0 else
                atan(-cos((hourAngle + longitude) * radiansPerDegree) / tangent) * degreesPerRadian
            points.add(Coordinate(latitude, longitude))
        }
        return Terminator(day, declination, hourAngle, declination > 0.0, points,
            if (discrete) null else if (abs(declination) <= 15.0) 2 else 3)
    }
}
