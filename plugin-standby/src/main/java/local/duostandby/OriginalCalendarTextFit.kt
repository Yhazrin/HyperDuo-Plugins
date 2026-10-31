package local.duostandby

import android.graphics.Paint
import android.text.TextPaint
import android.text.TextUtils

/** CalendarPoster c80a8 class2 environment: minimumScaleFactor=.5, lineLimit=1.
 * These constraints belong to each Text, NOT a whole-calendar Canvas scale.
 * Android shaping/search/ellipsis are adapters, not recovered SwiftUI internals.
 */
object OriginalCalendarTextFit {
    const val MINIMUM_SCALE_FACTOR=.5f
    const val LINE_LIMIT=1
    /** Effective proposals AFTER native layout allocation. Do not guess the
     * trailing nil-minLength Spacer or divide the date HStack equally. */
    data class Proposal(val firstDateRun:Float,val secondDateRun:Float,
                        val monthYear:Float,val monthTime:Float) {
        init {require(listOf(firstDateRun,secondDateRun,monthYear,monthTime).all{it.isFinite() && it>0f})}
    }
    data class Result(val text:String,val fontSize:Float,val width:Float,
                      val scale:Float,val truncated:Boolean)
    fun fit(label:String,base:Paint,availableWidth:Float):Result {
        require(availableWidth.isFinite() && availableWidth>0f)
        require(base.textSize.isFinite() && base.textSize>0f)
        val p=TextPaint(base);val original=base.textSize
        val full=p.measureText(label)
        if(full<=availableWidth)return Result(label,original,full,1f,false)
        p.textSize=original*MINIMUM_SCALE_FACTOR
        if(p.measureText(label)>availableWidth){
            // Standard SwiftUI EnvironmentValues.truncationMode default is tail.
            // Android TextUtils preserves shaping boundaries; not native glyph parity.
            val shown=TextUtils.ellipsize(label,p,availableWidth,TextUtils.TruncateAt.END).toString()
            return Result(shown,p.textSize,p.measureText(shown),MINIMUM_SCALE_FACTOR,shown!=label)
        }
        var lo=original*MINIMUM_SCALE_FACTOR;var hi=original
        // Numerical search tolerance is Android adapter implementation, not an Apple parameter.
        repeat(20){val mid=(lo+hi)/2f;p.textSize=mid;if(p.measureText(label)<=availableWidth)lo=mid else hi=mid}
        p.textSize=lo
        return Result(label,lo,p.measureText(label),lo/original,false)
    }
}
