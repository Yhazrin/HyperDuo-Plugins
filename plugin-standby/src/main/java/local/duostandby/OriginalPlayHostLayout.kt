package local.duostandby

import kotlin.math.max
import kotlin.math.min

/** Confirmed UIKit Play/RollingTimeView host formulas, kept separate from the
 * SwiftUI face. Do not call this a recovered SwiftUI scaling mechanism.
 * Native UILabel tight metrics must be supplied explicitly. CoreText outline
 * bounds or advance substitution is an Android adapter, not equivalent proof. */
object OriginalPlayHostLayout {
    const val HORIZONTAL_SCREEN_INSET = 64.0
    const val VERTICAL_SCREEN_INSET = 24.0
    const val ORIGINAL_SWIFTUI_BLUR_SEPARATOR = true

    data class Rect(val x:Double,val y:Double,val width:Double,val height:Double) {
        val maxX get()=x+width
        val maxY get()=y+height
    }
    data class Fit(val scale:Double,val tightTotalWidth:Double,val tightMaxHeight:Double,
                   val content:Rect,val leadingZeroWidthSpace:Boolean)

    /** 82d24..82ee0, followed by b909c..b90a8 center in bounds.
     * For a full-screen frame this is exactly inset(64,24). */
    fun uiKitContentBounds(frameInScreen:Rect,screen:Rect):Rect {
        require(frameInScreen.width>0 && frameInScreen.height>0)
        val left=max(0.0,screen.x+64-frameInScreen.x)
        val right=max(0.0,frameInScreen.maxX-screen.maxX+64)
        val top=max(0.0,screen.y+24-frameInScreen.y)
        val bottom=max(0.0,frameInScreen.maxY-screen.maxY+24)
        val w=frameInScreen.width-left-right
        val h=frameInScreen.height-top-bottom
        require(w>0 && h>0)
        // setCenter(bounds.center) overrides asymmetric preliminary origin.
        return Rect((frameInScreen.width-w)/2,(frameInScreen.height-h)/2,w,h)
    }

    /** 54a6c/54ba0/54c8c + 54724..54760. Pure source formula.
     * screenScale/nativeScale is an iOS input, NOT Android densityDpi/160. */
    fun uiKitFit(characters:List<String>,tightWidths:List<Double>,tightHeights:List<Double>,
                 content:Rect,screenScale:Double,nativeScale:Double):Fit {
        require(characters.isNotEmpty() && characters.size==tightWidths.size && characters.size==tightHeights.size)
        require(tightWidths.all{it.isFinite()&&it>=0} && tightHeights.all{it.isFinite()&&it>=0})
        require(content.width>0 && content.height>0 && screenScale>0 && nativeScale>0)
        val leading=characters.first()=="\u200B"
        val width=tightWidths.sum()-30.0*(characters.size-1-if(leading)1 else 0)
        val height=tightHeights.max()
        require(width>0 && height>0)
        return Fit(min(min(content.width/width,content.height/height),screenScale/nativeScale),width,height,content,leading)
    }

    /** Same UIKit fit chain, with an explicit Android full-window point mapping.
     * Android 1 dp -> 1 point; its backing pixels map 1:1, so scale/nativeScale
     * is normalized to 1/1 instead of pretending density is an iOS UIScreen value.
     * Callers identify whether supplied tight metrics are native UILabel or an adapter.
     */
    fun androidWindowFit(widthPoints:Double,heightPoints:Double,characters:List<String>,
                         tightWidths:List<Double>,tightHeights:List<Double>):Fit {
        val window=Rect(0.0,0.0,widthPoints,heightPoints)
        val content=uiKitContentBounds(window,window)
        return uiKitFit(characters,tightWidths,tightHeights,content,1.0,1.0)
    }

    /** UILabel layout @0x547a4..0x549f4: cumulative tight widths and 30pt overlap,
     * discounting the invisible leading U+200B. This is not the SwiftUI advance layout.
     */
    fun uiKitCenters(tightWidths:List<Double>,fit:Fit):List<Double> {
        val leading=if(fit.leadingZeroWidthSpace)1 else 0
        var cursor=fit.content.x+(fit.content.width-fit.tightTotalWidth*fit.scale)/2.0
        return tightWidths.mapIndexed { index,width ->
            val center=cursor+(width/2.0-30.0*(index-leading))*fit.scale
            cursor+=width*fit.scale
            center
        }
    }

}
