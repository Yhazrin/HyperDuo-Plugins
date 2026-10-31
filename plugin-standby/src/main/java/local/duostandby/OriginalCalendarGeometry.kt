package local.duostandby

/** 24A94425 Simulator CalendarPoster source-derived local dimensions.
 * Android's measured window in dp is an EXPLICIT host adapter, not proof of Duo's
 * scene.frame. Never normalize or swap that frame to force a desired aspect class.
 * Native bf038 itself returns scene.height then scene.width.
 */
object OriginalCalendarGeometry {
    data class SceneFrame(val width:Float,val height:Float) {
        val aspectClass:Int get()=OriginalCalendarComposition.aspectClass(height.toDouble(),width.toDouble())
    }
    fun androidWindow(widthPx:Int,heightPx:Int,density:Float):SceneFrame {
        require(density>0f && widthPx>0 && heightPx>0)
        return SceneFrame(widthPx/density,heightPx/density)
    }
    const val COMPACT_CELL=58f
    const val COMPACT_GRID_WIDTH=450f
    const val COMPACT_GRID_HEIGHT=406f
    const val DATE_FRAME_WIDTH=526f
    const val DATE_FRAME_HEIGHT=316f
    const val WEEK_ROWS=6
    const val WEEK_COLUMNS=7
    const val MONTH_Y_OFFSET=-3f
    const val TITLE_TRACKING=-.25f
    fun columnCenter(column:Int):Float {
        require(column in 0..6)
        return floatArrayOf(29f,94f,159f,225f,291f,356f,421f)[column]
    }
    fun weekdayCenterY()=COMPACT_CELL/2f
    fun dayCenterY(row:Int):Float {
        require(row in 0 until WEEK_ROWS)
        return COMPACT_CELL*(row+1.5f)
    }
    fun headerFontSize(cls:Int)=floatArrayOf(36f,32f,50f,36f)[cls]
    fun headerLeading(cls:Int)=floatArrayOf(22f,61f,0f,22f)[cls]
    fun headerTrailing(cls:Int)=floatArrayOf(25f,61f,0f,25f)[cls]
    fun topSpacer(cls:Int)=floatArrayOf(19f,33f,0f,19f)[cls]
    fun middleSpacer(cls:Int)=if(cls==2)0f else 18f
    fun dateYOffset(cls:Int)=if(cls==1)27f else 37f
    // c0f70: devanagari + NewYork + day outside1..9 ->390; otherwise425/429.
    fun dateFontSize(frame:SceneFrame,day:Int,numerals:Int,typeface:Int):Float {
        val base=if(numerals==3 && typeface==3 && day !in 1..9)390f
                 else if(frame.aspectClass in 1..2)429f else 425f
        return if(frame.aspectClass==2)base*frame.height/874f else base
    }
    fun timelineCell(frame:SceneFrame)=frame.height/874f*40f
    fun timelineFont(frame:SceneFrame)=if(frame.aspectClass==2)frame.height/874f*30f else headerFontSize(frame.aspectClass)
    fun timelineTodayDiameter(frame:SceneFrame)=if(frame.aspectClass==2)frame.height/874f*52f else 54f
    fun timelineGap(available:Float,cell:Float)=((available-7f*cell)/6f).coerceAtLeast(0f)
    // d4d74 Grid spacing is zero. Cell edge padding creates these intervals.
    data class TimelineLayout(val cell:Float,val gapX:Float,val gapY:Float,val weekdayBottom:Float){
        val intrinsicWidth get()=7f*cell+6f*gapX
        val intrinsicHeight get()=7f*cell+weekdayBottom+5.5f*gapY
        fun columnCenter(column:Int)=cell/2f+column*(cell+gapX)
        fun weekdayCenterY()=cell/2f
        fun dayCenterY(row:Int)=(row+1.5f)*cell+weekdayBottom+(row+.5f)*gapY
    }
    fun timelineLayout(frame:SceneFrame,width:Float,height:Float):TimelineLayout {
        val cell=timelineCell(frame);val gy=timelineGap(height,cell)
        return TimelineLayout(cell,timelineGap(width,cell),gy,if(frame.aspectClass==1)gy/2f else 0f)
    }
    const val CLASS1_GRID_FRAME_HEIGHT=21f
    const val CLASS1_GRID_INSET=74.5f
    fun compactDateXOffset(day:Int,numerals:Int,typeface:Int):Float {
        if(day !in 1..31)return 0f
        val column=when(typeface){0->0;2->1;3->2;else->return 0f}
        val rows=when(numerals){0->arabic;1->arabicIndic;3->devanagari;else->return 0f}
        return rows[day-1][column]
    }
    private val arabic=arrayOf(
        floatArrayOf(-1.0f,-1.0f,7.0f), // 1
        floatArrayOf(9.0f,9.0f,7.0f), // 2
        floatArrayOf(9.0f,9.0f,7.0f), // 3
        floatArrayOf(9.0f,9.0f,7.0f), // 4
        floatArrayOf(9.0f,9.0f,7.0f), // 5
        floatArrayOf(9.0f,9.0f,7.0f), // 6
        floatArrayOf(9.0f,9.0f,7.0f), // 7
        floatArrayOf(9.0f,9.0f,7.0f), // 8
        floatArrayOf(9.0f,9.0f,7.0f), // 9
        floatArrayOf(5.0f,5.0f,10.0f), // 10
        floatArrayOf(-1.0f,-1.0f,10.0f), // 11
        floatArrayOf(7.0f,7.0f,7.0f), // 12
        floatArrayOf(6.0f,6.0f,8.0f), // 13
        floatArrayOf(4.0f,4.0f,7.0f), // 14
        floatArrayOf(7.0f,7.0f,8.0f), // 15
        floatArrayOf(8.0f,8.0f,7.0f), // 16
        floatArrayOf(3.0f,3.0f,7.0f), // 17
        floatArrayOf(7.0f,7.0f,7.0f), // 18
        floatArrayOf(7.0f,7.0f,7.0f), // 19
        floatArrayOf(5.0f,5.0f,8.0f), // 20
        floatArrayOf(6.0f,6.0f,9.0f), // 21
        floatArrayOf(5.0f,5.0f,9.0f), // 22
        floatArrayOf(7.0f,7.0f,9.0f), // 23
        floatArrayOf(0.0f,0.0f,9.0f), // 24
        floatArrayOf(5.0f,5.0f,9.0f), // 25
        floatArrayOf(7.0f,7.0f,9.0f), // 26
        floatArrayOf(2.0f,2.0f,7.0f), // 27
        floatArrayOf(7.0f,7.0f,8.0f), // 28
        floatArrayOf(6.0f,6.0f,8.0f), // 29
        floatArrayOf(3.0f,3.0f,5.0f), // 30
        floatArrayOf(9.0f,9.0f,7.0f), // 31
    )
    private val arabicIndic=arrayOf(
        floatArrayOf(9.0f,9.0f,9.0f), // 1
        floatArrayOf(19.0f,19.0f,9.0f), // 2
        floatArrayOf(19.0f,19.0f,9.0f), // 3
        floatArrayOf(9.0f,9.0f,9.0f), // 4
        floatArrayOf(9.0f,9.0f,9.0f), // 5
        floatArrayOf(9.0f,9.0f,9.0f), // 6
        floatArrayOf(12.0f,12.0f,7.0f), // 7
        floatArrayOf(9.0f,9.0f,9.0f), // 8
        floatArrayOf(9.0f,9.0f,8.0f), // 9
        floatArrayOf(17.0f,17.0f,11.0f), // 10
        floatArrayOf(10.0f,10.0f,9.0f), // 11
        floatArrayOf(8.0f,8.0f,6.0f), // 12
        floatArrayOf(17.0f,17.0f,8.0f), // 13
        floatArrayOf(10.0f,10.0f,5.0f), // 14
        floatArrayOf(10.0f,10.0f,7.0f), // 15
        floatArrayOf(10.0f,10.0f,7.0f), // 16
        floatArrayOf(9.0f,9.0f,4.0f), // 17
        floatArrayOf(9.0f,9.0f,7.0f), // 18
        floatArrayOf(7.0f,7.0f,6.0f), // 19
        floatArrayOf(7.0f,7.0f,9.0f), // 20
        floatArrayOf(4.0f,4.0f,9.0f), // 21
        floatArrayOf(4.0f,4.0f,6.0f), // 22
        floatArrayOf(5.0f,5.0f,2.0f), // 23
        floatArrayOf(2.0f,2.0f,9.0f), // 24
        floatArrayOf(7.0f,7.0f,8.0f), // 25
        floatArrayOf(6.0f,6.0f,7.0f), // 26
        floatArrayOf(3.0f,3.0f,2.0f), // 27
        floatArrayOf(6.0f,6.0f,4.0f), // 28
        floatArrayOf(5.0f,5.0f,8.0f), // 29
        floatArrayOf(5.0f,5.0f,10.0f), // 30
        floatArrayOf(9.0f,9.0f,10.0f), // 31
    )
    private val devanagari=arrayOf(
        floatArrayOf(9.0f,9.0f,9.0f), // 1
        floatArrayOf(9.0f,9.0f,9.0f), // 2
        floatArrayOf(9.0f,9.0f,9.0f), // 3
        floatArrayOf(9.0f,9.0f,9.0f), // 4
        floatArrayOf(9.0f,9.0f,-17.0f), // 5
        floatArrayOf(9.0f,9.0f,9.0f), // 6
        floatArrayOf(10.0f,10.0f,9.0f), // 7
        floatArrayOf(9.0f,9.0f,9.0f), // 8
        floatArrayOf(9.0f,9.0f,9.0f), // 9
        floatArrayOf(10.0f,10.0f,6.0f), // 10
        floatArrayOf(10.0f,10.0f,9.0f), // 11
        floatArrayOf(9.0f,9.0f,7.0f), // 12
        floatArrayOf(9.0f,9.0f,7.0f), // 13
        floatArrayOf(7.0f,7.0f,4.0f), // 14
        floatArrayOf(3.0f,3.0f,-17.0f), // 15
        floatArrayOf(-1.0f,-1.0f,-1.0f), // 16
        floatArrayOf(6.0f,6.0f,0.0f), // 17
        floatArrayOf(8.0f,8.0f,3.0f), // 18
        floatArrayOf(5.0f,5.0f,6.0f), // 19
        floatArrayOf(8.0f,8.0f,6.0f), // 20
        floatArrayOf(5.0f,5.0f,7.0f), // 21
        floatArrayOf(5.0f,5.0f,9.0f), // 22
        floatArrayOf(3.0f,3.0f,5.0f), // 23
        floatArrayOf(5.0f,5.0f,8.0f), // 24
        floatArrayOf(-3.0f,-3.0f,-17.0f), // 25
        floatArrayOf(-3.0f,-3.0f,-4.0f), // 26
        floatArrayOf(3.0f,3.0f,3.0f), // 27
        floatArrayOf(4.0f,4.0f,1.0f), // 28
        floatArrayOf(4.0f,4.0f,5.0f), // 29
        floatArrayOf(6.0f,6.0f,8.0f), // 30
        floatArrayOf(5.0f,5.0f,7.0f), // 31
    )
}
