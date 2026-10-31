package local.duostandby

/** CalendarPoster c7638/c7cac/c8aa4, 24A94425 Simulator.
 * Scene frame and effective parent proposal are DIFFERENT coordinate spaces.
 * The caller must supply measured/allocated spacer widths;12 is only a minimum.
 * This does not infer an original scene from an Android screen or claim that
 * the local SwiftUI proposal solver has been reproduced.
 */
object OriginalCalendarWideLayout {
    data class Proposal(val width:Float,val height:Float)
    data class Rect(val left:Float,val top:Float,val width:Float,val height:Float)
    data class Layout(val scene:OriginalCalendarGeometry.SceneFrame,val parent:Proposal,
                      val header:Rect,val right:Rect,val leftSpacer:Float,val rightSpacer:Float) {
        fun dateCenterX(style:Int,measuredFixedTextWidth:Float,day:Int,numerals:Int):Float {
            require(measuredFixedTextWidth.isFinite() && measuredFixedTextWidth>=0f)
            return if(style==0)measuredFixedTextWidth/2f-opticalOffset(day,numerals) else right.width/2f
        }
        val dateCenterY get()=right.height/2f
        val timeline get()=OriginalCalendarGeometry.timelineLayout(scene,right.width,right.height)
    }
    fun resolve(scene:OriginalCalendarGeometry.SceneFrame,parent:Proposal,
                allocatedLeftSpacer:Float,allocatedRightSpacer:Float):Layout {
        require(scene.aspectClass==2)
        require(listOf(scene.height,parent.width,parent.height,allocatedLeftSpacer,allocatedRightSpacer).all{it.isFinite()})
        require(allocatedLeftSpacer>=12f && allocatedRightSpacer>=12f)
        val padX=49f*scene.height/874f;val padY=42f*scene.height/874f
        val headerWidth=scene.height/3f
        val rightWidth=parent.width-2f*padX-headerWidth-allocatedLeftSpacer-allocatedRightSpacer
        val height=parent.height-2f*padY
        require(rightWidth>0f && height>0f){"Unresolved/overflowing class2 proposal; do not replace scene with guessed dimensions"}
        return Layout(scene,parent,Rect(padX,padY,headerWidth,height),
            Rect(padX+headerWidth+allocatedLeftSpacer,padY,rightWidth,height),allocatedLeftSpacer,allocatedRightSpacer)
    }
    // bf278: original unscaled optical offsets, not the compact279-entry table.
    fun opticalOffset(day:Int,numerals:Int):Float=when(numerals){
        0->if(day in 2..9)floatArrayOf(28f,28f,22f,29f,26f,22f,24f,25f)[day-2]else 20f
        1->if(day in 1..9)floatArrayOf(28f,32f,32f,31f,30f,16f,20f,20f,19f)[day-1]else 20f
        3->if(day in 1..9)floatArrayOf(22f,24f,22f,21f,22f,26f,26f,21f,24f)[day-1]else 20f
        else->20f
    }
}
