package local.duostandby

import android.graphics.Path

/**
 * 原版 MusicWidgets 播放/暂停图形，直接由 CAAR 归档还原。
 * 来源：references/duo-exact-source/files/System/Library/AppPlugIns/MusicWidgets.appex/
 *      MusicFoundation_MusicCoreUI.bundle/PlayPauseStop.caar
 *      （NSKeyedArchiver，CACGPathCodingProxy.LKCGPathSegments；见
 *       docs/research/evidence/music-widget-caar-20261025.json）
 * 坐标已按各自 layer bounds 归一化到 0..1，绘制时再乘目标盒子的宽高；比例保持原值。
 * 归档里没有 colorSpace（颜色只有 r/g/b/a），填充是纯白。
 */
object OriginalMusicGlyphs {
    /** op: 0=moveTo 1=lineTo 3=cubicTo 4=close；每个点已归一化。 */
    data class Seg(val op: Int, val pts: FloatArray)

    /** path-play-tint-shape：layer bounds 79.8600 × 89.1900（aspect 0.895392） */
    const val PLAY_ASPECT = 0.895392f
    val playWire: List<Seg> = listOf(
        Seg(0, floatArrayOf(0.916479f, 0.597825f)),
        Seg(1, floatArrayOf(0.190083f, 0.975109f)),
        Seg(3, floatArrayOf(0.156148f, 0.992376f, 0.130729f, 1.000000f, 0.102930f, 1.000000f)),
        Seg(3, floatArrayOf(0.045955f, 1.000000f, 0.000000f, 0.961543f, 0.000000f, 0.896513f)),
        Seg(1, floatArrayOf(0.000000f, 0.104048f)),
        Seg(3, floatArrayOf(0.000000f, 0.039018f, 0.045955f, 0.000000f, 0.102930f, 0.000000f)),
        Seg(3, floatArrayOf(0.130729f, 0.000000f, 0.156148f, 0.008185f, 0.190083f, 0.025451f)),
        Seg(1, floatArrayOf(0.916479f, 0.402624f)),
        Seg(3, floatArrayOf(0.972201f, 0.431326f, 1.000000f, 0.457899f, 1.000000f, 0.500168f)),
        Seg(3, floatArrayOf(1.000000f, 0.542437f, 0.972201f, 0.568449f, 0.916479f, 0.597713f)),
        Seg(4, floatArrayOf()),
    )
    /** path-pause-tint-shape：layer bounds 67.9200 × 90.6800（aspect 0.749007） */
    const val PAUSE_ASPECT = 0.749007f
    val pauseWire: List<Seg> = listOf(
        Seg(0, floatArrayOf(0.000000f, 0.082267f)),
        Seg(1, floatArrayOf(0.000000f, 0.918394f)),
        Seg(3, floatArrayOf(0.000000f, 0.971769f, 0.037691f, 1.000000f, 0.108952f, 1.000000f)),
        Seg(1, floatArrayOf(0.295789f, 1.000000f)),
        Seg(3, floatArrayOf(0.367049f, 1.000000f, 0.404741f, 0.974195f, 0.404741f, 0.918394f)),
        Seg(1, floatArrayOf(0.404741f, 0.082378f)),
        Seg(3, floatArrayOf(0.404741f, 0.028341f, 0.367049f, 0.000110f, 0.295789f, 0.000110f)),
        Seg(1, floatArrayOf(0.109099f, 0.000110f)),
        Seg(3, floatArrayOf(0.037839f, 0.000000f, 0.000000f, 0.028231f, 0.000000f, 0.082267f)),
        Seg(4, floatArrayOf()),
        Seg(0, floatArrayOf(0.595112f, 0.082267f)),
        Seg(1, floatArrayOf(0.595112f, 0.918394f)),
        Seg(3, floatArrayOf(0.595112f, 0.971769f, 0.632803f, 1.000000f, 0.704947f, 1.000000f)),
        Seg(1, floatArrayOf(0.891048f, 1.000000f)),
        Seg(3, floatArrayOf(0.963192f, 1.000000f, 1.000000f, 0.974195f, 1.000000f, 0.918394f)),
        Seg(1, floatArrayOf(1.000000f, 0.082378f)),
        Seg(3, floatArrayOf(1.000000f, 0.028341f, 0.963192f, 0.000110f, 0.891048f, 0.000110f)),
        Seg(1, floatArrayOf(0.704947f, 0.000110f)),
        Seg(3, floatArrayOf(0.632803f, 0.000000f, 0.595112f, 0.028231f, 0.595112f, 0.082267f)),
        Seg(4, floatArrayOf()),
    )

    /** 按目标盒子把归一化路径还原成 Path（保持原比例由调用方负责）。 */
    fun path(wire: List<Seg>, left: Float, top: Float, width: Float, height: Float): Path {
        val p = Path()
        for (s in wire) {
            when (s.op) {
                0 -> p.moveTo(left + s.pts[0] * width, top + s.pts[1] * height)
                1 -> p.lineTo(left + s.pts[0] * width, top + s.pts[1] * height)
                3 -> p.cubicTo(left + s.pts[0] * width, top + s.pts[1] * height,
                               left + s.pts[2] * width, top + s.pts[3] * height,
                               left + s.pts[4] * width, top + s.pts[5] * height)
                4 -> p.close()
            }
        }
        return p
    }
}
