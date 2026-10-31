package local.duostandby
import kotlin.math.*
/** Transcribed from original-analog-layout.json; local host W/H mapping is Android.
 * Original bounds anchor is center; UIFont/SwiftUI vertical metrics remain separate. */
object OriginalAnalogLayout {
 data class Numeral(val value:Int,val x:Double,val y:Double,val size:Double)
 private fun ra(x:Double)=sign(x)*floor(abs(x)+0.5)
 private val regular_numeralsDial=mapOf(12 to Pair(-8.0,31.0),10 to Pair(80.0,88.0),2 to Pair(-90.0,88.0),9 to Pair(43.0,-1.0),3 to Pair(-39.0,0.0),8 to Pair(90.0,-85.0),4 to Pair(-103.0,-85.0),6 to Pair(0.0,-32.0))
 private val regular_ticksAndMajorNumerals=mapOf(12 to Pair(0.0,40.0),9 to Pair(47.0,0.0),3 to Pair(-47.0,0.0),6 to Pair(0.0,-43.0))
 private val wide_numeralsDial=mapOf(12 to Pair(-3.0,31.0),10 to Pair(124.0,49.0),2 to Pair(-149.0,49.0),9 to Pair(40.0,0.0),3 to Pair(-40.0,0.0),8 to Pair(143.0,-53.0),4 to Pair(-158.0,-53.0),6 to Pair(0.0,-31.0))
 private val wide_ticksAndMajorNumerals=mapOf(12 to Pair(0.0,41.0),9 to Pair(56.0,0.0),3 to Pair(-56.0,0.0),6 to Pair(0.0,-41.0))
 private val ultrawide_numeralsDial=mapOf(12 to Pair(-3.0,31.0),10 to Pair(124.0,49.0),2 to Pair(-149.0,49.0),9 to Pair(40.0,0.0),3 to Pair(-40.0,0.0),8 to Pair(143.0,-53.0),4 to Pair(-158.0,-53.0),6 to Pair(0.0,-31.0))
 private val ultrawide_ticksAndMajorNumerals=mapOf(12 to Pair(0.0,41.0),9 to Pair(56.0,0.0),3 to Pair(-56.0,0.0),6 to Pair(0.0,-41.0))
 fun layout(w:Double,h:Double,outline:Boolean):List<Numeral> {
  require(w>0 && h>0)
  val cls=if(abs(w/h)<1.5)0 else if(abs(w/h)<2)1 else 2
  val p=if(cls==0)16.0 else 29.0
  val hp=if(cls==0)2.0 else 0.0 // Current Android content host has no extra L/R insets.
  val sx=if(cls==0)1.0 else (w-2*p-2*hp)/(874-2*p-2*hp)
  val sy=if(cls==0)1.0 else (h-2*p)/(402-2*p)
  val size=84*(if(cls==1)(sx+sy)/2 else sy)
  val offsets=when(cls){0->if(outline)regular_numeralsDial else regular_ticksAndMajorNumerals
   1->if(outline)wide_numeralsDial else wide_ticksAndMajorNumerals
   else->if(outline)ultrawide_numeralsDial else ultrawide_ticksAndMajorNumerals}
  val vb=p+if(cls==0)55.0 else ra(42*sy)
  val hb=p+hp+if(cls==0)74.0 else ra(67*sx)
  return offsets.map{(n,o)->
   val x=if(outline)when(n){2,3,4->w-p-hp;8,9,10->p+hp;else->w/2}
    else when(n){3->w-hb;9->hb;else->w/2}
   val y=if(outline)when(n){2,10,12->p;4,6,8->h-p;else->h/2}
    else when(n){12->vb;6->h-vb;else->h/2}
   Numeral(n,x+ra(o.first*sx),y+ra(o.second*sy),size)
  }
 }
}
