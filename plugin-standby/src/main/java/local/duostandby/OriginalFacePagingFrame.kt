package local.duostandby

/** Per-whole-poster frame for the independent Android face catalog.
 * Native mechanism: AmbientUI 24A94425 horizontal progress0x36a0..36ac,
 * update0xbabc / closure0xbd74: transform0xbdb4 and alpha0xbdd0 target the
 * SAME passed item view. Defaults response.35, damping1, targetScale.61,
 * targetOpacity0. The Duo catalog and UIKit/Android timing adapter remain explicit.
 * Do not apply the vertical posterView-only opacity rule to this horizontal host.
 */
class OriginalFacePagingFrame(count:Int,initialPage:Double) {
    data class Item(val index:Int,val offsetPages:Double,val targetProgress:Double,
                    val progress:Double,val scale:Double,val running:Boolean) {
        val visible get()=targetProgress>0.0||progress>1e-5||running
    }
    private val springs=List(count){OriginalStandByNavigation.Presentation(OriginalStandByNavigation.progress(it,initialPage))}
    private var lastFrame:Long?=null
    var items=springs.mapIndexed {index,spring->
        Item(index,index-initialPage,OriginalStandByNavigation.progress(index,initialPage),spring.value,
            OriginalStandByNavigation.scale(OriginalStandByNavigation.Axis.HORIZONTAL,spring.value),false)
    };private set
    val running get()=items.any{it.running}
    fun advance(nanos:Long,page:Double,animated:Boolean){
        if(lastFrame?.let{nanos<=it}==true)return
        items=springs.mapIndexed{index,spring->
            val target=OriginalStandByNavigation.progress(index,page)
            val progress=spring.sample(target,nanos,animated)
            Item(index,index-page,target,progress,
                OriginalStandByNavigation.scale(OriginalStandByNavigation.Axis.HORIZONTAL,progress),spring.running)
        }
        lastFrame=nanos
    }
}
