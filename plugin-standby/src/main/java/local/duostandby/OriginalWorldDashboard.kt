package local.duostandby

/** ClockPoster 24A94425: parent4a418/4c0c0, Dashboard4f064/4f52c.
 * Font metrics/default VStack spacing are supplied by the host, not recovered
 * Duo scene measurements. All literal source offsets stay in point units.
 */
object OriginalWorldDashboard {
    const val TIME_SIZE=92.0
    const val NAME_SIZE=26.0
    const val MARGIN=49.0
    const val TOP_MINIMUM=36.0
    const val TIME_OFFSET=-10.0
    const val NAME_OFFSET=-21.0
    const val SELECTION_DURATION=.33
    data class Layout(val leading:Double,val top:Double,val timeTop:Double,val nameTop:Double,val availableWidth:Double,val rightAligned:Boolean)
    fun layout(width:Double,height:Double,timeHeight:Double,nameHeight:Double,
               defaultSpacing:Double,hasName:Boolean,rtl:Boolean):Layout {
        val contentHeight=timeHeight+defaultSpacing+nameHeight
        val parentTop=maxOf(TOP_MINIMUM,height-MARGIN-contentHeight)
        val shift=if(hasName)28.0 else 46.0
        return Layout(if(rtl)width-MARGIN else MARGIN,parentTop+shift,
            parentTop+shift+TIME_OFFSET,parentTop+shift+timeHeight+defaultSpacing+NAME_OFFSET,
            (width-2*MARGIN).coerceAtLeast(0.0),rtl)
    }
}
