package local.duostandby

/** Extracted numeric sRGB facts only; no Apple implementation or font assets.
 * Assets.car SHA256 f619a6be1f8e4d7eb4696249b71c3bab39da0a716b721c809f6e7940aa8ef9ff.
 * SimpleClockPosterExtension AnalogColors @0x100013fe0, look type .color fallback roles.
 * Does NOT describe gradient/splitTone, typography, outline, widget or hub roles.
 */
object OriginalAnalogParameters {
    data class Colors(val background:Int,val primary:Int,val secondary:Int,val accent:Int)
    data class Palette(val id:String,val any:Colors,val dark:Colors,val light:Colors)
    const val MINOR_TICK_ALPHA=0.4f
    const val DIGITAL_TIME_FORMAT="h:mm:ss"
    const val DATE_FORMAT="EEE dd"
    // Preserve old indices: neutral, blue, pink, green, gold, purple; append two new schemes.
    // Color values now follow the source rather than the previous approximate swatches.
    val paletteIds=listOf("color-3","color-1","color-2","color-0","color-7","color-5","color-4","color-6")
    val labels=listOf("砂岩黄","迷雾灰","石英粉","青瓷绿","琥珀色配深卡其色","紫丁香色配深松绿色","橄榄色配深橄榄色","蔚蓝色搭配深红木色")
    private val palettes=listOf(
        Palette("color-3",Colors(0xFFABA398.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF605649.toInt()),Colors(0xFF000000.toInt(),0xFFABA398.toInt(),0xFFFFFFFF.toInt(),0xFF605649.toInt()),Colors(0xFFABA398.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF605649.toInt())),
        Palette("color-1",Colors(0xFF92ABB1.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF426068.toInt()),Colors(0xFF000000.toInt(),0xFF92ABB1.toInt(),0xFFFFFFFF.toInt(),0xFF426068.toInt()),Colors(0xFF92ABB1.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF426068.toInt())),
        Palette("color-2",Colors(0xFFAC9B9F.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF614C50.toInt()),Colors(0xFF000000.toInt(),0xFFAC9B9F.toInt(),0xFFFFFFFF.toInt(),0xFF614C50.toInt()),Colors(0xFFAC9B9F.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF614C50.toInt())),
        Palette("color-0",Colors(0xFFA4B89D.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF57734E.toInt()),Colors(0xFF000000.toInt(),0xFFA4B89D.toInt(),0xFFFFFFFF.toInt(),0xFF57734E.toInt()),Colors(0xFFA4B89D.toInt(),0xFF000000.toInt(),0xFFFFFFFF.toInt(),0xFF57734E.toInt())),
        Palette("color-7",Colors(0xFFBA862C.toInt(),0xFFFFCA00.toInt(),0xFFFFFFFF.toInt(),0xFF2893C1.toInt()),Colors(0xFF433602.toInt(),0xFFBA862C.toInt(),0xFFFFFFFF.toInt(),0xFF2893C1.toInt()),Colors(0xFFBA862C.toInt(),0xFFFFCA00.toInt(),0xFFFFFFFF.toInt(),0xFF2893C1.toInt())),
        Palette("color-5",Colors(0xFFD1BDD9.toInt(),0xFF063F40.toInt(),0xFFFFFFFF.toInt(),0xFFFF521E.toInt()),Colors(0xFF063F40.toInt(),0xFFD1BDD9.toInt(),0xFFFFFFFF.toInt(),0xFFFF521E.toInt()),Colors(0xFFD1BDD9.toInt(),0xFF063F40.toInt(),0xFFFFFFFF.toInt(),0xFFFF521E.toInt())),
        Palette("color-4",Colors(0xFFACAC2D.toInt(),0xFFFFF193.toInt(),0xFFFFFFFF.toInt(),0xFFC4268D.toInt()),Colors(0xFF46411C.toInt(),0xFFACAC2D.toInt(),0xFFFFFFFF.toInt(),0xFFC4268D.toInt()),Colors(0xFFACAC2D.toInt(),0xFFFFF193.toInt(),0xFFFFFFFF.toInt(),0xFFC4268D.toInt())),
        Palette("color-6",Colors(0xFF2F90BA.toInt(),0xFF632222.toInt(),0xFFFFFFFF.toInt(),0xFFFFE432.toInt()),Colors(0xFF632222.toInt(),0xFF2F90BA.toInt(),0xFFFFFFFF.toInt(),0xFFFFE432.toInt()),Colors(0xFF2F90BA.toInt(),0xFF632222.toInt(),0xFFFFFFFF.toInt(),0xFFFFE432.toInt()))
    )
    fun colors(index:Int,dark:Boolean):Colors {
        val palette=palettes[index.coerceIn(0,palettes.lastIndex)]
        return if(dark)palette.dark else palette.light
    }
}
