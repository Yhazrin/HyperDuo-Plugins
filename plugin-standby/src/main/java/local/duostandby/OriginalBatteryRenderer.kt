package local.duostandby

import android.content.Context
import android.graphics.*
import kotlin.math.min

/**
 * Original assets: 24A94425 CoreGlyphs / BatteryCenterUI, not hand-drawn silhouettes.
 * Four-ring geometry: calibrated physical iPhone 27.2 session01 at 60 s, NOT
 * falsely identified with the separate UIKit BCUI2x2Avocado source branch.
 * Rendering is an Android reconstruction. Palette/layer interpolation and exact
 * symbol configuration remain outside the closed source proof.
 */
class OriginalBatteryRenderer(context: Context) {
    data class Slot(val device: BatteryDeviceModel, val component: BatteryComponent) {
        val id: String get() = "${device.id}:${component.role.name}"
    }
    /** Asset selection must be supplied from a positively identified model.
     * The provider's HEADPHONES kind alone never means AirPods Pro. */
    fun interface GlyphResolver { fun assetName(slot: Slot): String? }
    private val assets = context.assets
    private val bitmaps = mutableMapOf<String, Bitmap?>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val oval = RectF()
    var lastDescriptions: List<String> = emptyList(); private set

    fun slots(devices: List<BatteryDeviceModel>): List<Slot> = devices
        .sortedBy { if (it.kind == BatteryDeviceKind.PHONE) 0 else 1 }
        .flatMap { d -> d.components.sortedBy { it.role.ordinal }.map { Slot(d, it) } }

    /** Every component remains independent. Overflow goes on another page, never
     * silently replaces LEFT/RIGHT/CASE with a fabricated combined percentage. */
    fun pages(devices: List<BatteryDeviceModel>): List<List<Slot>> = slots(devices).chunked(4)

    fun draw(canvas: Canvas, bounds: RectF, devices: List<BatteryDeviceModel>, page: Int = 0,
             glyphResolver: GlyphResolver? = null, lowChargeSlotIds: Set<String> = emptySet()) {
        val entries = pages(devices).getOrNull(page).orEmpty()
        drawSlots(canvas, bounds, entries, glyphResolver, lowChargeSlotIds)
    }

    fun drawSlots(canvas: Canvas, bounds: RectF, entries: List<Slot>, glyphResolver: GlyphResolver? = null,
                  lowChargeSlotIds: Set<String> = emptySet()) {
        require(entries.size <= 4)
        // The captured grid envelope is 965 x 965 px, outer diameter 430 px,
        // centre pitch 535 px, observed stroke 40 px (about +/- 1 px capture AA).
        val size = min(bounds.width(), bounds.height())
        val unit = size / 965f
        val x = bounds.centerX() - size / 2f
        val y = bounds.centerY() - size / 2f
        val diameter = 430f * unit
        val stroke = 40f * unit
        val radius = (diameter - stroke) / 2f
        lastDescriptions = entries.map { slot -> description(slot) }
        repeat(4) { index ->
            val cx = x + (215f + (index % 2) * 535f) * unit
            val cy = y + (215f + (index / 2) * 535f) * unit
            val slot = entries.getOrNull(index)
            paint.reset(); paint.isAntiAlias = true; paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke; paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.rgb(33, 33, 33) // measured capture mode, not UIColor claim
            canvas.drawCircle(cx, cy, radius, paint)
            if (slot != null) {
                val component = slot.component
                val fresh = !component.stale && slot.device.connected != false
                // Missing/stale values have no live charge arc; never interpreted as 0%.
                val percent = component.percent.takeIf { fresh }
                if (percent != null && percent > 0) {
                    // Native lowCharge is a provider property; threshold is not closed.
                    // Keep valid levels green rather than inventing a 20% threshold.
                    paint.color = if (slot.id in lowChargeSlotIds) Color.rgb(255, 59, 48) else Color.rgb(48, 205, 86)
                    oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
                    canvas.drawArc(oval, -90f, percent * 3.6f, false, paint)
                }
                val explicit = glyphResolver?.assetName(slot)
                val glyph = explicit ?: when {
                    slot.device.kind == BatteryDeviceKind.PHONE -> "iphone.gen3"
                    slot.device.kind == BatteryDeviceKind.HEADPHONES && component.role == BatteryComponentRole.MAIN -> "headphones"
                    else -> null
                }
                if (glyph != null) {
                    val h = when {
                        component.role == BatteryComponentRole.CASE -> 134f
                        slot.device.kind == BatteryDeviceKind.PHONE -> 160f
                        else -> 134f
                    } * unit
                    drawGlyph(canvas, glyph, cx, cy, h, Color.WHITE)
                }
                if (component.charging == true && fresh) {
                    // Original bolt + knockout asset; observed glyph centred at ring top.
                    drawGlyph(canvas, "bolt-outline-knockout-v2", cx, cy - radius, 80f * unit, Color.BLACK)
                    drawGlyph(canvas, "bolt-v2", cx, cy - radius, 80f * unit, Color.WHITE)
                }
            }
        }
    }

    private fun drawGlyph(c: Canvas, name: String, cx: Float, cy: Float, height: Float, tint: Int) {
        if (!name.matches(Regex("[a-zA-Z0-9._-]+"))) return
        val image = bitmaps.getOrPut(name) { runCatching {
            assets.open("original_battery/$name.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() } ?: return
        val width = height * image.width / image.height.toFloat()
        paint.reset(); paint.isAntiAlias = true; paint.isFilterBitmap = true
        paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        c.drawBitmap(image, null, RectF(cx-width/2, cy-height/2, cx+width/2, cy+height/2), paint)
    }

    private fun description(s: Slot): String {
        val fresh = !s.component.stale && s.device.connected != false
        val value = when {
            s.device.connected == false -> "未连接，电量未知"
            s.component.stale -> "电量已过期"
            else -> s.component.percent?.let { "$it%" } ?: "电量未知"
        }
        val charging = when(s.component.charging.takeIf { fresh }) { true -> "，充电中"; false -> ""; null -> "，充电状态未知" }
        return "${s.device.displayName} ${s.component.role.name}：$value$charging"
    }
}
