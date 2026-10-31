package local.duostandby

import android.service.dreams.DreamService
import android.view.WindowManager

/** Optional selectable Dream; never writes the user's screensaver selection. */
class StandByDreamService:DreamService() {
    private var scene:StandByView?=null
    private val batteryData by lazy { BatteryDataSource(applicationContext) }
    override fun onAttachedToWindow(){
        super.onAttachedToWindow();isInteractive=true;isFullscreen=true;isScreenBright=false
        val controller=StandByController(this)
        val view=StandByView(this,controller,{finish()},{applyBrightness()})
        scene=view;setContentView(view);applyBrightness();batteryData.start { view.updateBatteries(it) }
    }
    private fun applyBrightness(){
        val w=window?:return;val p=w.attributes;p.screenBrightness=if(scene?.controller?.state?.night==true)0.04f else 0.30f;w.attributes=p
    }
    override fun onDetachedFromWindow(){batteryData.close();scene=null;super.onDetachedFromWindow()}
}
