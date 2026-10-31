package local.duostandby
/** Xiaomi-only physical-pixel adapter. The aperture radius means the INNER edge,
 * not Apple ring radius. No guessed optical center or extra dp expansion. */
object OuterCameraGeometry {
 data class Point(val x:Double,val y:Double)
 data class Outline(val pathRadius:Double,val strokeWidth:Double,val innerRadius:Double,val outerRadius:Double)
 fun outline(apertureRadius:Double,strokeWidth:Double):Outline {
  require(apertureRadius>0 && strokeWidth>0)
  return Outline(apertureRadius+strokeWidth/2,strokeWidth,apertureRadius,apertureRadius+strokeWidth)
 }
 fun fromNatural(nx:Double,ny:Double,w:Double,h:Double,rotation:Int):Point = when(rotation){
  1->Point(ny*w,(1-nx)*h);2->Point((1-nx)*w,(1-ny)*h);3->Point((1-ny)*w,nx*h);else->Point(nx*w,ny*h)
 }
 fun toNatural(x:Double,y:Double,w:Double,h:Double,rotation:Int):Point = when(rotation){
  1->Point(1-y/h,x/w);2->Point(1-x/w,1-y/h);3->Point(y/h,1-x/w);else->Point(x/w,y/h)
 }
}
