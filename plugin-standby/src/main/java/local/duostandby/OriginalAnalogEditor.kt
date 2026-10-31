package local.duostandby
/** Original24A94425 allOptions and literal numeral strings; do not expose allCases.
 * Existing style IDs retained:0=numeralsDial,1=ticksAndMajorNumerals,2=ticks. */
object OriginalAnalogEditor {
 val styleOrder=intArrayOf(2,1,0)
 val styleLabels=listOf("刻度","刻度和主要数字","数字刻度盘")
 val numeralOrder=intArrayOf(0,1,3)
 val numeralLabels=listOf("阿拉伯数字","阿拉伯印度文","天城文")
 private val texts=mapOf(
  0 to listOf("1","2","3","4","5","6","7","8","9","10","11","12"),
  1 to listOf("١","٢","٣","٤","٥","٦","٧","٨","٩","١٠","١١","١٢"),
  3 to listOf("१","२","३","४","५","६","७","८","९","१०","११","१२"),
 )
 fun numeral(system:Int,value:Int):String {
  if(value !in 1..12)return ""
  return " "+(texts[system]?:texts.getValue(0))[value-1]+" "
 }
}
