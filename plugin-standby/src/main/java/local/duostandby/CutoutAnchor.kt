package local.duostandby
/** Hardware adapter, NOT Apple source. A vendor safe rectangle is not an optical
 * camera measurement. Center is provisional until the user drag-calibrates it. */
object CutoutAnchor {
 data class Anchor(val x:Double,val y:Double,val radius:Double,val calibrated:Boolean=false)
 fun fromBounds(left:Double,top:Double,right:Double,bottom:Double):Anchor {
  require(right>left && bottom>top)
  return Anchor((left+right)/2,(top+bottom)/2,kotlin.math.min(right-left,bottom-top)/2)
 }
}
