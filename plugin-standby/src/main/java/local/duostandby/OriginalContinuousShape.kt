package local.duostandby
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min
/** SwiftUI .continuous path sampled on macOS; PosterKit standard item selects
 * .continuous with radius8. Cross-platform raster equivalence is not asserted. */
object OriginalContinuousShape {
 fun path(b:RectF, radius:Float):Path {
  val r=radius.coerceIn(0f,min(b.width(),b.height())/3.05733f)
  if(r==0f)return Path().apply{addRect(b,Path.Direction.CW)}
  fun x(n:Float)=when {n==100f->b.centerX();n<100f->b.left+n/8f*r;else->b.right-(200f-n)/8f*r}
  fun y(n:Float)=when {n==50f->b.centerY();n<50f->b.top+n/8f*r;else->b.bottom-(100f-n)/8f*r}
  return Path().apply {
   moveTo(x(200.000000000f),y(50.000000000f))
   lineTo(x(200.000000000f),y(87.770680428f))
   cubicTo(x(200.000000000f),y(91.292079926f),x(200.000000000f),y(93.052743912f),x(199.400708795f),y(94.948048115f))
   cubicTo(x(198.647519946f),y(97.017407894f),x(197.017407894f),y(98.647519946f),x(194.948048115f),y(99.400708795f))
   cubicTo(x(193.052743912f),y(100.000000000f),x(191.292079926f),y(100.000000000f),x(187.770680428f),y(100.000000000f))
   lineTo(x(12.229319572f),y(100.000000000f))
   cubicTo(x(8.707920074f),y(100.000000000f),x(6.947256088f),y(100.000000000f),x(5.051951885f),y(99.400708795f))
   cubicTo(x(2.982592106f),y(98.647519946f),x(1.352480054f),y(97.017407894f),x(0.599291205f),y(94.948048115f))
   cubicTo(x(0.000000000f),y(93.052743912f),x(0.000000000f),y(91.292079926f),x(0.000000000f),y(87.770680428f))
   lineTo(x(0.000000000f),y(12.229319572f))
   cubicTo(x(0.000000000f),y(8.707920074f),x(0.000000000f),y(6.947256088f),x(0.599291205f),y(5.051951885f))
   cubicTo(x(1.352480054f),y(2.982592106f),x(2.982592106f),y(1.352480054f),x(5.051951885f),y(0.599291205f))
   cubicTo(x(6.947256088f),y(0.000000000f),x(8.707920074f),y(0.000000000f),x(12.229319572f),y(0.000000000f))
   lineTo(x(187.770680428f),y(0.000000000f))
   cubicTo(x(191.292079926f),y(0.000000000f),x(193.052743912f),y(0.000000000f),x(194.948048115f),y(0.599291205f))
   cubicTo(x(197.017407894f),y(1.352480054f),x(198.647519946f),y(2.982592106f),x(199.400708795f),y(5.051951885f))
   cubicTo(x(200.000000000f),y(6.947256088f),x(200.000000000f),y(8.707920074f),x(200.000000000f),y(12.229319572f))
   close()
  }
 }
}
