package local.duostandby

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.min

/**
 * Now-playing MUSIC widget for the StandBy stack.
 * Original: MusicWidgets.appex lock-screen now-playing. Data: RootMusicProvider
 * (real media session via root dumpsys); no data = nothing drawn.
 */
class MusicWidgetContent(private val context: android.content.Context) {
    private val fonts = OriginalFonts(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, bounds: RectF, night: Boolean) {
        val now = RootMusicProvider.current ?: return
        val size = min(bounds.width(), bounds.height())
        val color = if (night) VisualTokens.NIGHT_RED else Color.WHITE
        val cx = bounds.centerX()
        // Album placeholder: rounded square with a note glyph (artwork URI is not read yet).
        val art = size * 0.30f
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.02f; paint.color = color
        val artRect = RectF(cx - art / 2, bounds.top + size * 0.08f, cx + art / 2, bounds.top + size * 0.08f + art)
        c.drawRoundRect(artRect, art * 0.22f, art * 0.22f, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(artRect.centerX(), artRect.centerY(), art * 0.10f, paint)
        // Title / artist
        paint.reset(); paint.isAntiAlias = true
        paint.typeface = fonts.ui; paint.textSize = size * 0.10f; paint.color = color; paint.textAlign = Paint.Align.CENTER
        c.drawText(now.title.take(24), cx, artRect.bottom + size * 0.16f, paint)
        if (now.artist != null) {
            paint.textSize = size * 0.085f
            c.drawText(now.artist.take(28), cx, artRect.bottom + size * 0.28f, paint)
        }
        // 播放/暂停：用原版 CAAR 还原的路径（PlayPauseStop.caar → OriginalMusicGlyphs），
        // 不再用 "❚❚"/"▶" 字符。盒子高度沿用本文件的适配值，宽度按原 layer 比例。
        val glyphH = size * 0.12f
        val wire = if (now.playing) OriginalMusicGlyphs.pauseWire else OriginalMusicGlyphs.playWire
        val aspect = if (now.playing) OriginalMusicGlyphs.PAUSE_ASPECT else OriginalMusicGlyphs.PLAY_ASPECT
        val glyphW = glyphH * aspect
        paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.FILL; paint.color = color
        val glyphTop = bounds.bottom - size * 0.10f - glyphH
        c.drawPath(OriginalMusicGlyphs.path(wire, cx - glyphW / 2f, glyphTop, glyphW, glyphH), paint)
    }
}
