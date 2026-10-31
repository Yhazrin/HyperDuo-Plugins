package local.duostandby

/** PosterKit 24A94425 StylePickerContent.itemSize, 0x1e3884308.
 * proxyWidth and optional screenSize are DIFFERENT host inputs. No invented height cap.
 * Raw type1 is the square branch; the standard thumbnail adapter passes0.
 */
object OriginalStylePickerSizing {
    data class Size(val width:Double,val height:Double)
    const val INTER_ITEM_SPACING=12.0 // init0x1e3882f54
    fun itemSize(type:Int,proxyWidth:Double,screenSize:Size?):Size {
        if(type==1)return Size(40.0,40.0)
        if(screenSize==null || !(screenSize.width>0.0) || !(screenSize.height>0.0))return Size(60.0,60.0)
        val width=proxyWidth-40.0
        return Size(width,screenSize.height*width/screenSize.width)
    }
}
