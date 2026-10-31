package local.duostandby
/** Original plugin property range0,1,2 and extracted numeral tables.
 * Digit substitution is the Android adapter; not proof of CoreText shaping parity. */
object OriginalDigitalEditor {
 val typefaceLabels=listOf("现代","经典","圆体")
 val tabs=intArrayOf(1,2,3) // color, typeface/numerals accessory, widgets; NO style property.
 private val digits=mapOf(
  0 to "0123456789",
  1 to "٠١٢٣٤٥٦٧٨٩",
  3 to "०१२३४५६७८९",
 )
 fun formatDigits(value:String,system:Int):String {
  val table=digits[system]?:digits.getValue(0)
  return value.map{if(it in '0'..'9')table[it-'0']else it}.joinToString("")
 }
}
