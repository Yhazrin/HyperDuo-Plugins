package local.duostandby

/** CalendarPoster 24A94425, SHA298eb1a5..., traced instructions, not visual inference.
 * A/B are the native pair returned by0x2162bf038; retain that order.
 * Host canvas orientation and full SwiftUI geometry are separate unfinished work.
 */
object OriginalCalendarComposition {
    // 0x2162befd8: strict >1.2, >=1.5, NaN ->3; no absolute value.
    fun aspectClass(a:Double,b:Double):Int {
        val ratio=a/b
        return when { ratio.isNaN()->3;ratio>=1.5->2;ratio>1.2->1;else->0 }
    }
    // c8fa8..c9060: large date is retained for the current period, including month style.
    // The month-navigation animation uses Animation.default; its numeric curve is not claimed here.
    fun dateOpacity(style:Int,samePeriod:Boolean,lowLight:Boolean):Float =
        if(style!=1 || samePeriod || lowLight)1f else 0f

    // Visual grid is constructed separately from d7988 (transparent hit regions).
    // c6250..c62a4 class0/1 and c8750..c88dc class2 apply StyleTransition(style==1,10).
    // This is terminal opacity only; class2 also has a construction/low-light predicate.
    fun hasMonthGrid(aspectClass:Int,style:Int):Boolean = aspectClass in 0..2 && style==1
}
