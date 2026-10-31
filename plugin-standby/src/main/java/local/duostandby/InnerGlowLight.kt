package local.duostandby

import android.graphics.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 内屏柔光的取色与合成，参数全部来自 24A94425 ClockAngel，而不是"看着像"的琥珀。
 *
 * 证据 1 —— 闹钟暖色字面量
 *   `_colorLiteralRed:green:blue:alpha:` 调用点 ClockAngel+0x1000096ac
 *   （slice VA，见 references/duo-exact-source/alarm-host-followup/clockangel-annotated.asm）
 *   传入 s0=1.0（0x10000969c fmov s0,#1.0）、s1=0x3f1594af=0.58429998（0x10000968c–0x100009694）、
 *   s2=0（0x1000096a0 movi.2d v2,#0）、s3=1.0（0x1000096a4）
 *   → RGBA (1.0, 0.5843, 0.0, 1.0) = #FF9500。
 *
 * 证据 2 —— 原版把画面"变暖"用的是材质颜色矩阵，不是发光体
 *   ClockAngel.app/timerYellow.materialrecipe → baseMaterial.materialFiltering.colorMatrix：
 *     RGB gain  0.5 / 0.5 / 0.5
 *     RGB bias  0.5 / 0.3 / 0.02
 *   （timerDark.materialrecipe：gain 0.825 / 0.5084 / -0.26，bias 0.174 / 0.171 / 0.195）
 *   即 out = backdrop*0.5 + (0.5, 0.3, 0.02)，作用在**模糊背景**上。
 *
 * 本文件的合成方式：把这两条证据按线性光正确组合，而不是再挑三个琥珀色：
 *   1) 亮度场 i(r) ∈ [0,1]（形状是适配，见下）；
 *   2) 暖色基底 = i × #FF9500（转到线性空间）；
 *   3) 套 timerYellow 矩阵：out = 0.5*base + i*(0.5, 0.3, 0.02)；
 *   4) 回到 sRGB 显示。
 *   注意 3) 对 #FF9500 是"保色相、缩亮度"的（i=1 时 out≈#FF9500+0.02 蓝），
 *   所以最终视觉就是 #FF9500 的线性衰减，而不是手挑的 #FFC482。
 *
 * 未闭合（如实标注）：
 *   - 衰减形状：原包没有任何柔光渐变层可读，这里用线性光下高斯扩散
 *     i = exp(-(r/σ)²)，σ 取到最远角落约 2%（物理漫射光的形状，不是原值）；
 *   - 呼吸/渐亮：`ramp` 2.2s、`breathe` 8s 仍是适配值，原版时长未解出。
 */
object InnerGlowLight {
    /** 闹钟暖色 #FF9500（证据 1）。 */
    const val ALARM_R = 1.0
    const val ALARM_G = 0.5842999815940857
    const val ALARM_B = 0.0

    /** timerYellow.materialrecipe colorMatrix（证据 2）。 */
    const val GAIN_R = 0.5
    const val GAIN_G = 0.5
    const val GAIN_B = 0.5
    const val BIAS_R = 0.5
    const val BIAS_G = 0.3
    const val BIAS_B = 0.02

    /** 高斯扩散在最远角落保留的强度比例。
     *  官方观感是"整面被柔光照亮"（用户实测反馈：原实现偏暗），所以角落不再压到 2%，
     *  改保留 ~35%，让大面积保持高亮度、只在最边缘略暗。仍是形状适配值，不是原版实测。 */
    private const val EDGE_FRACTION = 0.35
    private val SIGMA_K = 1.0 / sqrt(-kotlin.math.ln(EDGE_FRACTION))

    private fun srgbToLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun linearToSrgb(c: Double): Double {
        val v = c.coerceIn(0.0, 1.0)
        return if (v <= 0.0031308) v * 12.92 else 1.055 * v.pow(1.0 / 2.4) - 0.055
    }

    /** 半径比例 t∈[0,1] 处的线性亮度（高斯扩散，线性光空间）。 */
    fun intensity(t: Double): Double {
        val tt = t.coerceIn(0.0, 1.0)
        val x = tt / SIGMA_K
        return exp(-(x * x))
    }

    /**
     * 发光体本身是否再套 timerYellow 矩阵。
     * 结论：**不套**。那个矩阵是 MaterialKit 对"模糊背景"做的暖化（out=0.5*backdrop+(0.5,0.3,0.02)），
     * 用它去乘一个自发光的纯色，会把 #FF9500 拉成偏黄的 #FFB326——那是另一个元素（玻璃材质）的效果，
     * 不是闹钟暖色本身。这里只保留它的参数作为对照记录（GAIN_ 与 BIAS_ 常量），发光路径直接用证据色。
     */
    private const val APPLY_MATERIAL_MATRIX = false

    /** 把某个半径比例处的颜色算出来（线性光衰减 → sRGB 8bit）。 */
    fun colorAt(t: Double, alphaScale: Double): Int {
        val i = intensity(t)
        // 1) 暖色基底（线性光）
        val baseR = srgbToLinear(ALARM_R) * i
        val baseG = srgbToLinear(ALARM_G) * i
        val baseB = srgbToLinear(ALARM_B) * i
        // 2) 可选：timerYellow 矩阵（默认关闭，见 APPLY_MATERIAL_MATRIX 的说明）
        val mR = if (APPLY_MATERIAL_MATRIX) GAIN_R else 1.0
        val mG = if (APPLY_MATERIAL_MATRIX) GAIN_G else 1.0
        val mB = if (APPLY_MATERIAL_MATRIX) GAIN_B else 1.0
        val bR = if (APPLY_MATERIAL_MATRIX) BIAS_R else 0.0
        val bG = if (APPLY_MATERIAL_MATRIX) BIAS_G else 0.0
        val bB = if (APPLY_MATERIAL_MATRIX) BIAS_B else 0.0
        val outR = mR * baseR + bR * i
        val outG = mG * baseG + bG * i
        val outB = mB * baseB + bB * i
        // 3) 回到 sRGB
        val r = (linearToSrgb(outR) * 255).toInt().coerceIn(0, 255)
        val g = (linearToSrgb(outG) * 255).toInt().coerceIn(0, 255)
        val b = (linearToSrgb(outB) * 255).toInt().coerceIn(0, 255)
        val a = (255 * alphaScale * (0.55 + 0.45 * i)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    /** 生成 RadialGradient 用的多档色标（半径比例 → 颜色），抵消 shader 的 sRGB 插值误差。 */
    fun stops(alphaScale: Double, count: Int = 33): Pair<IntArray, FloatArray> {
        val n = max(2, count)
        val colors = IntArray(n + 1)
        val positions = FloatArray(n + 1)
        for (k in 0..n) {
            val t = k.toDouble() / n
            colors[k] = colorAt(t, alphaScale)
            positions[k] = t.toFloat()
        }
        return colors to positions
    }

    /** 光源中心与半径：面板上的"漫射光"从靠上的一点向外扩，最大半径到最远角落。 */
    fun centerAndRadius(width: Float, height: Float): Triple<Float, Float, Float> {
        val cx = width * 0.5f
        val cy = height * 0.42f
        val dx = max(cx, width - cx)
        val dy = max(cy, height - cy)
        return Triple(cx, cy, sqrt(dx * dx + dy * dy))
    }

    /** 面板亮度建议值（0..1），用于 window screenBrightness。 */
    fun panelBrightness(dual: Boolean): Float = if (dual) 0.30f else 0.30f

    /** 预留：把同一套参数导出给其他渲染路径（例如未来换成 RuntimeShader）。 */
    fun describe(): String = buildString {
        append("alarm=").append(String.format("#%02X%02X%02X",
            (ALARM_R * 255).toInt(), (ALARM_G * 255).toInt(), (ALARM_B * 255).toInt()))
        append(" matrix=gain(").append(GAIN_R).append(',').append(GAIN_G).append(',').append(GAIN_B)
        append(") bias(").append(BIAS_R).append(',').append(BIAS_G).append(',').append(BIAS_B).append(')')
        append(" sigmaK=").append(String.format("%.4f", SIGMA_K))
        append(" edgeFraction=").append(EDGE_FRACTION)
    }
}
