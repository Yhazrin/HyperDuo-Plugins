package local.duostandby

/** 24A94425 SimpleDigitalPosterExtension, standby font size and narrow selection.
 * Source: digital-font-mapping-followup and digital-layout-followup.
 * CoreText cap-height ratio is from the exact font; not UIKit runtime acceptance. */
object OriginalDigitalTypography {
    const val CAP_HEIGHT_RATIO=1443.0/2048.0
    fun widthAxis(hour:Int,is24Hour:Boolean,nativeNumerals:Int=0):Int {
        require(hour in 0..23)
        val r=hour%12
        return if(is24Hour||nativeNumerals==1||nativeNumerals==3||r==0||r>9)38 else 47
    }
    fun sizeClass(width:Double,height:Double)=if(height==0.0)2 else if(kotlin.math.abs(width/height)<1.5)0 else if(kotlin.math.abs(width/height)<2.0)1 else 2
    fun fontSize(width:Double,height:Double):Double {
        if(height==0.0)return 400.0
        return height*when(sizeClass(width,height)){0->400.0/466.0;1->360.0/375.0;else->400.0/402.0}
    }
    fun widgetFontSize(width:Double,height:Double)=if(sizeClass(width,height)==1)26.0 else 30.0
}
