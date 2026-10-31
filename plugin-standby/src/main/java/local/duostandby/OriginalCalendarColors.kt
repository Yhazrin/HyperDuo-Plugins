package local.duostandby
import kotlin.math.roundToInt
/** Original24A94425 CalendarPosterExtension assets, sRGB, customOptions order.
 * Component0=primary=>numerals,1=accent,2=background,3=secondary.
 * These eight IDs have no -1 suffix; special rainbow branches are NOT generalized.
 * calendarLook default5 migrates the former red/blue experiment; not an Apple default claim.
 * Night-red host treatment and scene geometry remain separate work. */
object OriginalCalendarColors {
 data class Colors(val background:Int,val numerals:Int,val secondary:Int,val accent:Int)
 val ids=listOf("Sage","Slate","Mauve","Mocha","Neon-Blue","Red-Blue","Blue-Orange","Orange-Purple")
 private val light=arrayOf(
  Colors(0xffa4b89d.toInt(),0xff000000.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Sage
  Colors(0xff92abb1.toInt(),0xff000000.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Slate
  Colors(0xffac9b9f.toInt(),0xff000000.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Mauve
  Colors(0xffaba398.toInt(),0xff000000.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Mocha
  Colors(0xffebebeb.toInt(),0xff8cd443.toInt(),0xff00c4ff.toInt(),0xffffffff.toInt()), // Neon-Blue
  Colors(0xffebebeb.toInt(),0xffff5252.toInt(),0xff006fff.toInt(),0xffffffff.toInt()), // Red-Blue
  Colors(0xffebebeb.toInt(),0xff94cfff.toInt(),0xffff6600.toInt(),0xffffffff.toInt()), // Blue-Orange
  Colors(0xffebebeb.toInt(),0xffffba3a.toInt(),0xffff3df7.toInt(),0xffffffff.toInt()), // Orange-Purple
 )
 private val dark=arrayOf(
  Colors(0xff000000.toInt(),0xffa4b89d.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Sage
  Colors(0xff000000.toInt(),0xff92abb1.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Slate
  Colors(0xff000000.toInt(),0xffac9b9f.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Mauve
  Colors(0xff000000.toInt(),0xffaba398.toInt(),0xfffeffff.toInt(),0xffffffff.toInt()), // Mocha
  Colors(0xff012730.toInt(),0xff8cd443.toInt(),0xff00c4ff.toInt(),0xffffffff.toInt()), // Neon-Blue
  Colors(0xff071b37.toInt(),0xffff5252.toInt(),0xff006fff.toInt(),0xffffffff.toInt()), // Red-Blue
  Colors(0xff2e1300.toInt(),0xff94cfff.toInt(),0xffff6600.toInt(),0xffffffff.toInt()), // Blue-Orange
  Colors(0xff2c092a.toInt(),0xffffba3a.toInt(),0xffff3df7.toInt(),0xffffffff.toInt()), // Orange-Purple
 )
 fun colors(index:Int,dark:Boolean):Colors=(if(dark)this.dark else light)[index.coerceIn(0,7)]
 // CalendarFace0x2162c049c: ordinary custom look, month style + dark => primary.opacity(0.5).
 fun dateInk(index:Int,dark:Boolean,style:Int):Int=colors(index,dark).numerals.let { if(style==1&&dark)opacity(it,0.5)else it }
 // Day.isCurrentMonth false: 0x2162d10c8 fmov d0,#0.25 -> Color.opacity.
 fun outsideMonth(index:Int,dark:Boolean):Int=opacity(colors(index,dark).secondary,0.25)
 private fun opacity(color:Int,value:Double):Int=(color and 0x00ffffff) or (((color ushr 24)*value).roundToInt() shl 24)
}
