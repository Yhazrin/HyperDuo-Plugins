package local.duostandby

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ClockKitUI _CLKUIInterpolateBetweenColors, 0x2497be408..0x2497be780.
 * Original lookup bytes, not an Android gamma approximation. Input contract [0,1] RGBA.
 */
class OriginalSolarColorInterpolation private constructor(
    private val srgbToLinear: FloatArray, private val linearToSrgb: FloatArray
) {
    fun mix(a: OriginalSolarParameters.Rgba, b: OriginalSolarParameters.Rgba, fraction: Float): OriginalSolarParameters.Rgba {
        require(fraction.isFinite() && fraction in 0f..1f)
        if (a == b) return a // Original UIColor equality short circuit.
        fun channel(x: Float, y: Float): Float {
            require(x in 0f..1f && y in 0f..1f)
            val first = srgbToLinear[(x * 255f).toInt()]
            val last = srgbToLinear[(y * 255f).toInt()]
            val value = Math.fma(fraction, last - first, first)
            return linearToSrgb[(value * 6590f).toInt().coerceIn(0, 6590)]
        }
        return OriginalSolarParameters.Rgba(channel(a.r,b.r), channel(a.g,b.g), channel(a.b,b.b),
            Math.fma(fraction, b.a - a.a, a.a))
    }
    companion object {
        const val SRGB_TABLE_ASSET = "solar/srgb-to-linear.f32le"
        const val LINEAR_TABLE_ASSET = "solar/linear-to-srgb.f32le"
        /** Both streams are consumed and closed; validates exact source table sizes. */
        fun load(srgb: InputStream, linear: InputStream): OriginalSolarColorInterpolation {
            fun table(input: InputStream, count: Int): FloatArray {
                val bytes = input.use { it.readBytes() }
                require(bytes.size == count * 4) { "Solar source LUT size mismatch" }
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                return FloatArray(count) { buffer.float }
            }
            return OriginalSolarColorInterpolation(table(srgb,256),table(linear,6591))
        }
    }
}
