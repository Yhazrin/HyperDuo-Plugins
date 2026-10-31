package local.duostandby

import kotlin.math.*

/** ClockPoster 24A94425 source contract; not an inferred gradient or normalized wall-clock model.
 * Source addresses are ClockPoster VM addresses unless prefixed by a framework name.
 */
object OriginalSolarParameters {
    const val CIRCLE_DIAMETER_POINTS = 750f // 0x6abf8..0x6ac04, double 0x4087700000000000
    const val BLUR_RADIUS_POINTS = 200f // 0x6ac58, double 0x4069000000000000
    const val MINIMUM_FRAME_INTERVAL_SECONDS = 1.0 / 30.0 // 0x6a134
    const val NOISE_TIME_RATE = 0.012 // 0x5b568..0x5b5b0, not used by Sky palette
    const val RED_MODE_TRANSITION_SECONDS = 1.2 // 0x5b454..0x5b4f8
    /** 0x6a72c flags0x80 -> 0x1f0f0. Construction only; downstream event phase remains open. */
    object SoftTimeTransition {
        const val MASS = 2.0
        const val STIFFNESS = 20.0
        const val DAMPING = 32.0
        const val INITIAL_VELOCITY = 0.0
        val INSERTION_OPACITY_BOUNDS = floatArrayOf(Float.fromBits(0x3df5c28f), Float.fromBits(0x3f19999a))
        val REMOVAL_OPACITY_BOUNDS = floatArrayOf(Float.fromBits(0x3f333333), Float.fromBits(0x3f666666))
        fun translationMagnitude(capHeightPoints: Double) = capHeightPoints / 10.0
    }
    data class Rgba(val r: Float, val g: Float, val b: Float, val a: Float = 1f)
    data class Point(val x: Float, val y: Float)
    data class Frame(val background: Rgba, val circle: Rgba, val center: Point)
    // Double table at 0xc17d8; input is SolarPosition.percentComplete, NOT hour / 24.
    val phaseKnots = doubleArrayOf(0.027777777777777776,0.09722222222222222,0.125,0.1527777777777778,0.5,0.8472222222222222,0.875,0.9027777777777778,0.9722222222222222)
    // Source ColorPalette +0x28, factory 0x6ff64. UIColor inputs, alpha = 1.
    private val foreground = arrayOf(
        Rgba(0.0f,0.0f,0.0f),
        Rgba(0.3843137254901961f,0.48627450980392156f,0.6431372549019608f),
        Rgba(0.3843137254901961f,0.48627450980392156f,0.6431372549019608f),
        Rgba(0.47058823529411764f,0.7686274509803922f,1.0f),
        Rgba(0.47058823529411764f,0.7686274509803922f,1.0f),
        Rgba(0.47058823529411764f,0.7686274509803922f,1.0f),
        Rgba(0.47058823529411764f,0.5098039215686274f,0.788235294117647f),
        Rgba(0.47058823529411764f,0.5098039215686274f,0.788235294117647f),
        Rgba(0.0f,0.0f,0.0f)
    )
    // Source ColorPalette +0x00, factory 0x702bc.
    private val background = arrayOf(
        Rgba(0.803921568627451f,0.5490196078431373f,0.8156862745098039f),
        Rgba(0.803921568627451f,0.5490196078431373f,0.8156862745098039f),
        Rgba(0.803921568627451f,0.6745098039215687f,0.4f),
        Rgba(0.5529411764705883f,0.6588235294117647f,0.22745098039215686f),
        Rgba(0.5882352941176471f,0.5294117647058824f,0.35294117647058826f),
        Rgba(0.6196078431372549f,0.4f,0.47843137254901963f),
        Rgba(0.8980392156862745f,0.5882352941176471f,0.14901960784313725f),
        Rgba(0.803921568627451f,0.3607843137254902f,0.1843137254901961f),
        Rgba(0.803921568627451f,0.5490196078431373f,0.8156862745098039f)
    )
    /** Input null preserves constructor phase 0 and the distinct nil-position orbit.
     * Source geolocation -> SolarPosition conversion remains outside this recovered module.
     */
    fun frame(widthPoints: Float, heightPoints: Float, screenScale: Float,
              solarPositionPhase: Float?, redProgress: Float,
              interpolator: OriginalSolarColorInterpolation): Frame {
        require(widthPoints > 0 && heightPoints > 0 && screenScale > 0)
        require(solarPositionPhase == null || solarPositionPhase.isFinite())
        require(redProgress.isFinite() && redProgress in 0f..1f)
        val phase = solarPositionPhase ?: 0f
        val fg = sample(foreground, phase, interpolator)
        val bg = sample(background, phase, interpolator)
        // 0x6ab80..0x6abb0 red foreground; 0x6ae98..0x6aeb4 black background.
        return Frame(interpolator.mix(bg, Rgba(0f, 0f, 0f), redProgress),
            interpolator.mix(fg, Rgba(.4f, 0f, 0f), redProgress),
            orbit(widthPoints, heightPoints, screenScale, solarPositionPhase))
    }
    /** 0x13da4 constructor + 0x5ad98 position. UIScreen.scale is supplied by the host.
     * Pixel rounding uses nearest; exact UIKit tie behavior is not yet recovered.
     */
    fun orbit(width: Float, height: Float, scale: Float, phase: Float?): Point {
        fun rounded(x: Double) = round(x * scale) / scale
        val rx = rounded(width.toDouble() * 1.4 * .5)
        val ry = rounded((width.toDouble() * 1.4 * .5) / (width.toDouble() / height))
        val pi = Float.fromBits(0x40490fda)
        val angle = if (phase == null) pi else ((phase + .5f) * pi) * 2f
        // sincosf has Float results; JVM transcendental conversion is an explicit backend adapter.
        val x = rx + cos(angle.toDouble()).toFloat() * rx.toFloat() - (2 * rx - width) * .5
        val y = ry + sin(angle.toDouble()).toFloat() * ry.toFloat() - (2 * ry - height) * .5
        return Point(rounded(x).toFloat(), rounded(y).toFloat())
    }
    private fun sample(colors: Array<Rgba>, phase: Float,
                       interpolation: OriginalSolarColorInterpolation): Rgba {
        val p = ((phase.toDouble() % 1.0) + 1.0) % 1.0
        // The wrap endpoints are equal in both original Sky palettes, so no invented wrap ramp.
        if (p <= phaseKnots.first() || p >= phaseKnots.last()) return colors.first()
        val hi = phaseKnots.indexOfFirst { it >= p }
        val lo = hi - 1
        val fraction = (p - phaseKnots[lo]) / (phaseKnots[hi] - phaseKnots[lo])
        return interpolation.mix(colors[lo], colors[hi], easeInOut(fraction))
    }
    /** Exact source algorithm: CAMediaTimingFunction._solveForInput 0x1c38e8a2c
     * -> CA::Render::TimingFunction::evaluate 0x1c3b1fef4.
     * Preset index 4 at 0x1c3b9c794, epsilon at 0x1c3bb1150,
     * derivative threshold at 0x1c3bb0fa8. Input/output Float with Double evaluation.
     */
    fun easeInOut(input: Double): Float {
        val x = input.toFloat().toDouble() // ClockPoster 0x6bf04 narrows before objc call.
        val p1 = .42f.toDouble(); val p2 = .58f.toDouble()
        val c = p1 * 3.0
        val delta3 = (p2 - p1) * 3.0
        val b = delta3 - c
        val a = 1.0 - delta3
        fun error(t: Double): Double = Math.fma(Math.fma(Math.fma(t, a, b), t, c), t, -x)
        fun result(t: Double): Float = (Math.fma(Math.fma(t, -2.0, 3.0), t, 0.0) * t).toFloat()
        var t = x
        for (iteration in 0 until 8) {
            val e = error(t)
            if (abs(e) < 1e-5) return result(t)
            val derivative = Math.fma(Math.fma(3.0 * a, t, b + b), t, c)
            if (abs(derivative) < 1e-6) break
            t -= e / derivative
        }
        if (x < 0.0) return result(0.0)
        if (x > 1.0) return result(1.0)
        var low = 0.0; var high = 1.0; var counter = -1023
        t = x
        while (true) {
            val e = error(t)
            if (abs(e) < 1e-5) return result(t)
            if (e < 0.0) low = t else high = t
            t = Math.fma(high - low, .5, low)
            val again = low < high && counter != 0
            counter++
            if (!again) return result(t)
        }
    }
}
