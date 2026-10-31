package local.duostandby

/** Original b9008 asymmetric AnyTransition. The constituent parameters match
 * StyleTransition except active blur radius6 (not month grid radius10).
 * Each branch owns its motion state so outgoing content survives removal.
 * SwiftUI interruption/transaction parity is not claimed by this adapter.
 */
class OriginalCalendarHeaderTransition(initialStyle:Int) {
    data class Sample(val date:OriginalCalendarStyleTransition.Sample,val month:OriginalCalendarStyleTransition.Sample) {
        val running get()=date.running||month.running
    }
    private val date=OriginalCalendarStyleTransition(initialStyle==0,6.0)
    private val month=OriginalCalendarStyleTransition(initialStyle!=0,6.0)
    fun sample(style:Int,nowNanos:Long)=Sample(date.sample(style==0,nowNanos),month.sample(style!=0,nowNanos))
}
