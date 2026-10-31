package local.duostandby

import android.graphics.Canvas
import android.graphics.RectF
import kotlin.math.min

/** Two independent widget stacks. AmbientUI 24A94425 proves one row/two columns
 * (0x18b88/0x18b90). This is a host/controller, not a claim that Android gestures
 * reproduce SpringBoardHome's unrecovered stack-editor animation. */
class OriginalWidgetStackHost {
    enum class Side { LEFT, RIGHT }
    enum class CalendarStyle { DATE, MONTH }
    data class Entry(val id: String, val title: String, val calendarStyle: CalendarStyle? = null,
                     val zoneId: String? = null, val clock24Hour: Boolean? = null) {
        // Legacy IDs remain valid; newly repeated clock instances have stable IDs.
        val kind: String get() = id.substringBefore('#')
    }
    data class Stack(val entries: MutableList<Entry> = mutableListOf(), var selected: Int = 0,
                     var smartRotate: Boolean = false, var suggestions: Boolean = false)
    val left = Stack()
    val right = Stack()
    // Content state belongs to a column + widget identity, never to the screen.
    // This retains the Android multi-page battery adapter without coupling columns.
    private val contentPages = Side.values().associateWith { mutableMapOf<String,Int>() }
    fun contentPage(side: Side, id: String, pageCount: Int): Int {
        if(stack(side).entries.none { it.id==id })return 0
        val pages=contentPages.getValue(side)
        return (pages[id] ?: 0).coerceIn(0,pageCount.coerceAtLeast(1)-1).also { pages[id]=it }
    }
    fun advanceContentPage(side: Side, id: String, pageCount: Int): Int {
        val page=contentPage(side,id,pageCount)
        if(stack(side).entries.none { it.id==id })return 0
        return ((page+1)%pageCount.coerceAtLeast(1)).also { contentPages.getValue(side)[id]=it }
    }
    // AMUIInfographViewController cannotRemoveLastWidgetInWidgetSheet @1f148=YES.
    fun canRemove(side: Side, id: String): Boolean =
        stack(side).entries.size>1 && stack(side).entries.any { it.id==id }
    var editing: Side? = null; private set
    fun stack(side: Side) = if (side == Side.LEFT) left else right
    fun beginEditing(side: Side) { editing = side }
    fun finishEditing() { editing = null }
    fun select(side: Side, index: Int) {
        val s=stack(side); if(s.entries.isNotEmpty()) s.selected=index.coerceIn(s.entries.indices)
    }
    fun step(side: Side, delta: Int) = select(side, stack(side).selected + delta)
    fun add(side: Side, entry: Entry) {
        val s=stack(side)
        if(s.entries.size>=64)return
        val instance=if(s.entries.any{it.id==entry.id} && entry.kind in setOf("ANALOG","DIGITAL","WORLD_CLOCK"))
            entry.copy(id=entry.kind+"#"+java.util.UUID.randomUUID().toString()) else entry
        if(s.entries.none{it.id==instance.id}) { s.entries.add(instance); s.selected=s.entries.lastIndex }
    }
    fun configureClock(side: Side, id: String, zoneId: String?, clock24Hour: Boolean?) {
        if(zoneId!=null && runCatching{java.time.ZoneId.of(zoneId)}.isFailure)return
        val entries=stack(side).entries
        val index=entries.indexOfFirst{it.id==id}
        if(index<0 || entries[index].kind !in setOf("ANALOG","DIGITAL","WORLD_CLOCK"))return
        entries[index]=entries[index].copy(zoneId=zoneId,clock24Hour=clock24Hour)
    }
    fun remove(side: Side, id: String) {
        if(!canRemove(side,id))return
        contentPages.getValue(side).remove(id)
        val s=stack(side); val current=s.entries.getOrNull(s.selected)?.id
        s.entries.removeAll{it.id==id}; s.selected=s.entries.indexOfFirst{it.id==current}.takeIf{it>=0}
            ?: s.selected.coerceIn(0,(s.entries.size-1).coerceAtLeast(0))
    }
    fun move(side: Side, from: Int, to: Int) {
        val s=stack(side); require(from in s.entries.indices && to in s.entries.indices)
        val current=s.entries.getOrNull(s.selected)?.id
        val item=s.entries.removeAt(from);s.entries.add(to,item)
        s.selected=s.entries.indexOfFirst{it.id==current}.coerceAtLeast(0)
    }
    fun replace(side: Side, entries: List<Entry>, selectedId: String? = null) {
        require(entries.map{it.id}.distinct().size == entries.size)
        contentPages.getValue(side).keys.retainAll(entries.map { it.id }.toSet())
        val s=stack(side);s.entries.clear();s.entries.addAll(entries)
        s.selected=entries.indexOfFirst{it.id==selectedId}.coerceAtLeast(0)
    }
    fun interface Painter { fun draw(canvas: Canvas, bounds: RectF, entry: Entry) }
    /** Caller binds actual observed slot rectangles. No invented fixed dp cards,
     * borders, shadows or editor curves are added by this host. */
    fun draw(canvas: Canvas, leftBounds: RectF, rightBounds: RectF, painter: Painter) {
        for ((s,b) in listOf(left to leftBounds,right to rightBounds)) {
            val e=s.entries.getOrNull(s.selected) ?: continue
            val checkpoint=canvas.save();canvas.clipRect(b);painter.draw(canvas,b,e);canvas.restoreToCount(checkpoint)
        }
    }
    fun hitSide(x: Float,y: Float,leftBounds: RectF,rightBounds: RectF): Side? = when {
        leftBounds.contains(x,y)->Side.LEFT;rightBounds.contains(x,y)->Side.RIGHT;else->null
    }
    companion object {
        /** Explicit recording profile. Source 2622x1206; both visual envelopes
         * ~965px square, left centre(686,603), right centre(1848,603).
         * This is physical iPhone 27.2 measurement, not Duo host parameters. */
        fun measuredIPhoneSlots(bounds: RectF): Pair<RectF,RectF> {
            val scale=min(bounds.width()/2622f,bounds.height()/1206f)
            val x=bounds.centerX()-1311f*scale;val y=bounds.centerY()-603f*scale
            fun box(cx:Float)=RectF(x+(cx-482.5f)*scale,y+120.5f*scale,x+(cx+482.5f)*scale,y+1085.5f*scale)
            return box(686f) to box(1848f)
        }
    }
}
