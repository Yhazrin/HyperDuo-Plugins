package local.duostandby
import android.content.Context
import android.graphics.Typeface
/** Local research resources from original 24A94425; not a redistribution license.
 * Numeric instance is evidence-backed; SFUI chrome weight is an Android mapping,
 * not a claim of UIKit shaping/metrics equivalence. Chinese uses platform fallback. */
class OriginalFonts(private val context:Context) {
 val ui=Typeface.Builder(context.assets,"fonts/SFUI.ttf").build()
 val medium=Typeface.create(ui,500,false)
 val semibold=Typeface.create(ui,600,false)
 private fun digitalInstance(width:Int)=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(11)
  .setFontVariationSettings("'wdth' $width, 'wght' 650, 'SOFT' 70, 'GRAD' 400").build()
 val digitalCompressed=digitalInstance(47)
 val digitalExtraCompressed=digitalInstance(38)
 private fun classic(width:Int)=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(3)
  .setFontVariationSettings("'opsz' 200, 'wght' 700, 'wdth' $width, 'SOFT' 70").build()
 private fun rounded(width:Int)=Typeface.Builder(context.assets,"fonts/Tokyo.ttf")
  .setFontVariationSettings("'wght' 640, 'wdth' $width").build()
 private val digitalClassic=arrayOf(classic(48),classic(32))
 private val digitalRounded=arrayOf(rounded(50),rounded(36))
 fun digital(typeface:Int,narrow:Boolean):Typeface=when(typeface){
  1->digitalClassic[if(narrow)1 else 0]
  2->digitalRounded[if(narrow)1 else 0]
  else->if(narrow)digitalExtraCompressed else digitalCompressed
 }

 // PhotosClockPoster time label: FontSpec.nativeName ".SFSoftNumeric-Regular"
 // (static spec table) with per-layout variable axes. width/weight are the same
 // wdth/wght axis values used by the digital clock instance above; this is the
 // original variable font, not an Android weight approximation.
 // WorldClockWidget 24A94425: time font name .SFSoftNumeric-CompressedSemibold
 // (CTFontCreateWithNameAndOptions @0x100029d54), sizes 96/80 from the two
 // lazy font wrappers 0x100029c8c/0x100029d18.
 val softCompressedSemibold=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(11)
  .setFontVariationSettings("'wdth' 47, 'wght' 590, 'SOFT' 70, 'GRAD' 400").build()

 private val photoTimeCache=HashMap<String,Typeface>()
 fun photoTime(width:Double?,weight:Double?):Typeface{
  val w=width ?: 42.0; val wt=weight ?: 640.0
  val key="$w/$wt"
  return photoTimeCache.getOrPut(key){
   Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(11)
    .setFontVariationSettings("'wdth' $w, 'wght' $wt, 'SOFT' 70, 'GRAD' 400").build()
  }
 }

 // CalendarPoster newYork branch: named .NewYorkSoftNumeric-Bold, opsz override200.
 // Preserve the existing serif selection; this is not proof of Apple's default preset.
 val calendarNewYork=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(3)
  .setFontVariationSettings("'opsz' 200, 'wght' 674, 'wdth' 100, 'SOFT' 70").build()
 private val calendarSoft=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(11)
  .setFontVariationSettings("'wdth' 100, 'wght' 510, 'SOFT' 70, 'GRAD' 400").build()
 private val calendarStencil=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(12)
  .setFontVariationSettings("'wght' 508, 'GRAD' 400").build()
 // Original CalendarPoster enum order0,3,2. Do not reuse DigitalTypeface (Tokyo).
 fun calendar(typeface:Int):Typeface=when(typeface){0->calendarSoft;2->calendarStencil;else->calendarNewYork}
 // Original named .SFSoftNumeric-Semibold is weight590, NOT the expanded650 instance.
 val worldTime=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(11)
  .setFontVariationSettings("'wdth' 100, 'wght' 590, 'SOFT' 70, 'GRAD' 400").build()
 val analog=Typeface.Builder(context.assets,"fonts/ADTNumeric.ttc").setTtcIndex(11)
  .setFontVariationSettings("'wdth' 150, 'wght' 650, 'SOFT' 70, 'GRAD' 400").build()
}
