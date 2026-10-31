package local.duostandby

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import android.widget.FrameLayout
import android.net.Uri
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.roundToInt

class MainActivity:Activity() {
    private lateinit var scene:StandByView
    private lateinit var sceneContainer:FrameLayout
    private lateinit var notificationBanner:NotificationBannerView
    private val screenOffReceiver=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:android.content.Context,intent:android.content.Intent){
            if(intent.action==android.content.Intent.ACTION_SCREEN_OFF)runCatching{photoSource.shuffleOnLock()}
        }
    }
    private var shuffleSheet:PhotoShuffleSheet? = null
    private var categoryEditor:PhotoCategoryEditor? = null
    // photosContent belongs to one PagedEditor configuration throughout its draft.
    private var photoContentPageId:String?=null
    private var photoPickerOpen=false
    private val freshPhotoGrants=linkedSetOf<String>()
    private val photoPickerRequest=4101
    private fun beginPhotoContentDraft():Boolean {
        photoContentPageId?.let { owner -> return photoSource.draftPages?.any{it.id==owner}==true }
        val owner=photoSource.selectedPageId
        if(photoSource.beginDraft().none{it.id==owner})return false
        photoContentPageId=owner
        return true
    }
    /** select = 类别切换器当前停留的那一页；完成时它成为整面实际使用的配置。 */
    private fun finishPhotoContentDraft(save:Boolean,select:String?=null):Boolean {
        if(save){
            val owner=photoContentPageId?:return false
            if(photoPickerOpen)return false
            if(!photoSource.commitDraft(select ?: owner)){
                photoContentError("未能保存照片配置，请检查读取授权后重试。")
                return false
            }
        }else photoSource.cancelDraft()
        photoContentPageId=null;photoPickerOpen=false
        releaseUnusedPhotoGrants()
        return true
    }
    private fun photoContentSummary():String {
        if(photoPickerOpen)return "等待系统照片选择结果…"
        val owner=photoContentPageId?:photoSource.selectedPageId
        val page=(photoSource.draftPages?:photoSource.pages).firstOrNull{it.id==owner}?:return "选择照片"
        return when(page.sourceKind){
            PhotoPageRepository.SourceKind.XIAOMI_CURATED -> "小米精选"
            PhotoPageRepository.SourceKind.XIAOMI_CARD -> page.title
            else -> "已选择 ${page.assetUris.size} 张照片"
        }
    }
    private fun photoContentError(message:String){
        Toast.makeText(this,message,Toast.LENGTH_LONG).show()
        scene.refreshPhotoEditor()
    }
    private fun releaseUnusedPhotoGrants(){
        val committed=photoSource.pages.flatMap{it.assetUris}.toSet()
        val pending=photoSource.draftPages.orEmpty().flatMap{it.assetUris}.toSet()
        freshPhotoGrants.toList().forEach { value ->
            if(value in committed)freshPhotoGrants.remove(value)
            else if(value !in pending && runCatching{
                    contentResolver.releasePersistableUriPermission(Uri.parse(value),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }.isSuccess)freshPhotoGrants.remove(value)
        }
    }
    /** 长按 portal 收拢完成后进入的类别切换器；卡片几何直接取 portal 卡片，避免跳变。 */
    private fun showPhotoCategoryEditor(){
        if(categoryEditor!=null)return
        if(!beginPhotoContentDraft()){ scene.exitPhotoConfiguration(); return }
        val pages=photoSource.draftPages?:return
        if(pages.isEmpty()){ scene.exitPhotoConfiguration(); return }
        val index=pages.indexOfFirst{it.id==photoSource.selectedPageId}.coerceAtLeast(0)
        // 面此刻已经在显示的位图当种子（复制一份，编辑器自己回收），进入编辑不再先黑一帧。
        // 种子必须**保持原始比例**：v177 里把它硬缩成卡片宽高，非 1.47:1 的照片被压扁，
        // 进编辑就像"矮了一截"。这里按 cover 比例缩放（只裁不压），卡片再由 drawCard 做 cover 裁剪。
        val card=scene.photoCardBounds()
        val seed=scene.currentPhotoPreview?.let{ bmp ->
            runCatching{
                if(bmp.isRecycled||bmp.width<=0||bmp.height<=0)null else {
                    val scale=max(card.width()/bmp.width,card.height()/bmp.height)
                    val w=(bmp.width*scale).roundToInt().coerceAtLeast(1)
                    val h=(bmp.height*scale).roundToInt().coerceAtLeast(1)
                    if(w==bmp.width&&h==bmp.height)bmp.copy(android.graphics.Bitmap.Config.ARGB_8888,false)
                    else android.graphics.Bitmap.createScaledBitmap(bmp,w,h,true)
                }
            }.getOrNull()
        }
        val editor=PhotoCategoryEditor(this,pages,index,scene.photoCardBounds(),scene.photoCardCorner(),seed,
            scene.currentPhotoAssetUri())
        editor.onAdd={ photoSource.showAlbumPicker() }
        editor.onPageSelected={ item -> photoSource.previewDraftPage(item.id) }
        // 进入编辑前抓一次面上的可读性强度，卡片整段沿用（否则编辑态把元数据关掉会让照片整体变亮）。
        val legibility=scene.photoLegibilityOpacity
        editor.photoLayer={ canvas,bounds,bitmap,night -> scene.drawPhotoCard(canvas,bounds,bitmap,night,legibility) }
        editor.night=scene.controller.state.night
        editor.onDone={ closePhotoCategoryEditor(true) }
        editor.onDismiss={ closePhotoCategoryEditor(false) }
        categoryEditor=editor
        android.util.Log.i("DuoPhotoCategory","editor open index=$index pages=${pages.size} id=${pages.getOrNull(index)?.id}")
        sceneContainer.addView(editor,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        editor.requestFocus()
        scene.onPhotoEditorOpened()
    }

    /** 「完成」提交草稿并走同一个 portal 退场；取消则丢弃草稿。 */
    private fun closePhotoCategoryEditor(save:Boolean){
        android.util.Log.i("DuoPhotoCategory","editor close save=$save")
        val editor=categoryEditor?:return
        if(!finishPhotoContentDraft(save,editor.selectedPageId()))return
        categoryEditor=null
        sceneContainer.removeView(editor)
        scene.exitPhotoConfiguration()
    }

    /** 「+」选完影集后，编辑器页数与当前页跟随草稿变化。 */
    private fun refreshPhotoCategoryEditor(){
        val editor=categoryEditor?:return
        editor.setPages(photoSource.draftPages?:return,photoSource.selectedPageId)
    }
    private fun onAlbumPicked(pageId:String){
        photoContentPageId=pageId
        scene.refreshPhotoEditor()
        categoryEditor?.setPages(photoSource.draftPages.orEmpty(),pageId)
    }

    /** Photo Shuffle sheet: sources + frequency, per PhotosPosterUI 24A94425. */
    private fun showPhotoShuffle(){
        if(shuffleSheet!=null)return
        val sheet=PhotoShuffleSheet(this)
        val current=getSharedPreferences("standby_trigger",MODE_PRIVATE).getInt("shuffle_frequency",1)
        sheet.onFeatured={ GalleryCardsRepository.cards.firstOrNull()?.let{ photoSource.addAlbumToDraft(it.id) } }
        sheet.onAlbum={ photoSource.showAlbumPicker() }
        sheet.onCategories={ photoSource.showAlbumPicker() }
        sheet.onManual={ openPhotoPicker() }
        sheet.onFrequency={ index ->
            getSharedPreferences("standby_trigger",MODE_PRIVATE).edit().putInt("shuffle_frequency",index).apply()
        }
        sheet.onDismiss={ sceneContainer.removeView(sheet); shuffleSheet=null }
        shuffleSheet=sheet
        sceneContainer.addView(sheet,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        sheet.show(current)
    }

    private fun openPhotoPicker(){
        if(photoPickerOpen||!beginPhotoContentDraft())return
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            photoPickerOpen=true
            @Suppress("DEPRECATION")
            startActivityForResult(intent,photoPickerRequest)
        }catch(_:Exception){photoPickerOpen=false;photoContentError("无法打开系统照片选择器，请重试。")}
        scene.refreshPhotoEditor()
    }
    @Deprecated("Activity result bridge retained without introducing AndroidX dependency")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=photoPickerRequest)return
        if(!photoPickerOpen)return
        photoPickerOpen=false
        val owner=photoContentPageId?:return
        if(resultCode!=RESULT_OK||data==null){scene.refreshPhotoEditor();return}
        val uris=buildList {
            data.clipData?.let{clip->for(i in 0 until clip.itemCount)add(clip.getItemAt(i).uri)}
            data.data?.let{add(it)}
        }.distinct()
        if(uris.isEmpty()){scene.refreshPhotoEditor();return}
        if(uris.size>50){photoContentError("每个照片配置最多选择 50 张，请减少选择。");return}
        val granted=mutableListOf<Uri>()
        try {
            val existing=contentResolver.persistedUriPermissions.filter{it.isReadPermission}.map{it.uri.toString()}.toSet()
            uris.forEach{uri->
                require(uri.scheme=="content")
                if(uri.toString() !in existing){
                    contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    freshPhotoGrants.add(uri.toString())
                }
                granted.add(uri)
            }
            check(photoSource.replaceDraftChosenUris(owner,granted))
            photoSource.previewDraftPage(owner)
        }catch(_:Exception){photoContentError("未能替换照片选择或保留读取授权；原草稿未变，请重新选择。")}
        releaseUnusedPhotoGrants()
        scene.refreshPhotoEditor()
    }
    private val batteryData by lazy { BatteryDataSource(applicationContext) }
    private val batteryControls by lazy { BluetoothBatteryControls(this,batteryData) { restartBatteryData() } }
    private fun restartBatteryData() { batteryData.close();batteryData.start { scene.updateBatteries(it) } }
    private val photoSource by lazy { XiaomiCuratedPhotoControls(this) { scene.updatePhotos(it) }.also { controls ->
        controls.onCardPicked = { pageId -> onAlbumPicked(pageId) }
    } }
    private var batteryReceiverRegistered=false
    private var lastPlugged:Boolean?=null
    private val batteryReceiver=object:BroadcastReceiver(){
        override fun onReceive(context:Context,intent:Intent){
            val plugged=intent.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
            val previous=lastPlugged
            lastPlugged=plugged
            val level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,-1)
            val scale=intent.getIntExtra(BatteryManager.EXTRA_SCALE,-1)
            // Foreground-only Android integration; not claimed as the Duo host trigger policy.
            if(!isInitialStickyBroadcast&&previous==false&&plugged&&level>=0&&scale>0){
                scene.showChargingPreview((100.0*level/scale).toInt().coerceIn(0,100))
            }
            if(!plugged)scene.stopChargingPreview()
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Decor-fits must be disabled BEFORE setContentView so the view gets full-screen bounds;
        // the insets controller only exists after the decor is created (hiding later, post-content).
        if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false)
        // Draw into the display cutout as well, otherwise the punch-hole/cutout band still
        // reserves ~20px and leaves a dark strip.
        if(Build.VERSION.SDK_INT>=28){window.attributes=window.attributes.apply{layoutInDisplayCutoutMode=android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS}}
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val fixed=intent.getStringExtra("reference_instant")?.let{runCatching{LocalDateTime.parse(it)}.getOrNull()}
        val requestedFace=intent.getStringExtra("face")?.let{runCatching{Face.valueOf(it.uppercase())}.getOrNull()}
        val night=if(intent.hasExtra("night"))intent.getBooleanExtra("night",false)else null
        val controller=StandByController(this,fixed,requestedFace,night)
        controller.restore(savedInstanceState?.getBundle("standbyState"))
        scene=StandByView(this,controller,{finish()},{applyBrightness();if(::scene.isInitialized)photoSource.setActive(scene.controller.state.face==Face.PHOTOS)}).apply{fitsSystemWindows=false}
        if(intent.getBooleanExtra("probe_aap_public_api",false))
            android.util.Log.i("DuoStandBy","publicAAPSettings="+BluetoothAapPublicCapability.inspect())
        intent.getStringExtra("navigation_profile")?.let{scene.setNavigationProfile(it)}
        scene.onBatterySettings = { batteryControls.show() }
        scene.onPhotoSourceSettings = { photoSource.show() }
        scene.onPhotoStep = { reverse -> photoSource.stepPhoto(reverse) }
        scene.onPhotoReadyStep = { reverse,id -> photoSource.commitReadyAssetStep(reverse,id) }
        scene.onPhotoCardSwitch = { reverse -> photoSource.switchCard(reverse) }
        scene.onPhotoAlbums = { showPhotoShuffle() }
        scene.onPhotoCategoryEditor = { showPhotoCategoryEditor() }
        photoSource.onShuffle = { showPhotoShuffle() }
        scene.onPhotoContentBegin = { beginPhotoContentDraft() }
        scene.onPhotoEdit = { openPhotoPicker() }
        scene.onPhotoContentFinish = { save -> finishPhotoContentDraft(save) }
        scene.onPhotoContentSummary = { photoContentSummary() }
        scene.onPhotoTimeLayout = { photoSource.timeLayoutRaw(photoContentPageId?:photoSource.selectedPageId) }
        scene.onPhotoTimeLayoutChange = { raw ->
            val owner=photoContentPageId
            (owner!=null && photoSource.editDraftTimeLayout(raw,owner)).also{if(it)scene.refreshPhotoEditor()}
        }
        scene.onPhotoNumberSystem = { photoSource.numberSystemRaw(photoContentPageId?:photoSource.selectedPageId) }
        scene.onPhotoNumberSystemChange = { raw ->
            val owner=photoContentPageId
            (owner!=null && photoSource.editDraftNumberSystem(raw,owner)).also{if(it)scene.refreshPhotoEditor()}
        }
        scene.onPhotoInteraction = { photoSource.setInteractionActive(it) }
        android.util.Log.i("DuoStandBy","scene face=${controller.state.face.name} reference=${controller.referenceMode}")
        // Ephemeral reference input never overwrites configured city data.
        scene.configureWorld(if(fixed!=null)intent.getStringExtra("reference_world_cities")else null,
            if(fixed!=null)intent.getStringExtra("reference_selected_city")else null)
        // Keep the verified window-controller fix: attach content BEFORE querying insetsController.
        sceneContainer=FrameLayout(this).apply{fitsSystemWindows=false;setPadding(0,0,0,0);addView(scene,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))}
        setContentView(sceneContainer);applyBrightness()
        notificationBanner=NotificationBannerView(this)
        sceneContainer.addView(notificationBanner,FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT))
        NotificationRelay.onItem={item->runOnUiThread{if(!isFinishing)notificationBanner.show(item)}}
        // Integrated host owns explicit notification/photo permission controls.
        // Opening the clock alone must not prompt for unrelated access.
        // Opt-in foldable StandBy trigger (iOS 27 Ambient policy adaptation). Off by default;
        // enabled from settings so the foreground service only runs when requested.
        if(getSharedPreferences("standby_trigger",MODE_PRIVATE).getBoolean("enabled",false)){
            if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),triggerPermissionRequest)
            }
            runCatching{StandByTriggerService.start(this)}
        }
        savedInstanceState?.getStringArrayList("freshPhotoGrants")?.let{freshPhotoGrants.addAll(it)}
        savedInstanceState?.getString("photoEditorDraft")?.let { draft ->
            val owner=savedInstanceState.getString("photoContentPageId")
            if(owner!=null && controller.editing && controller.displayed.face==Face.PHOTOS && photoSource.restoreDraftState(draft)){
                if(photoSource.draftPages?.any{it.id==owner}==true){
                    photoContentPageId=owner;photoPickerOpen=savedInstanceState.getBoolean("photoPickerOpen",false)
                    photoSource.previewDraftPage(owner)
                }else photoSource.cancelDraft()
            }
        }
        if(controller.editing && controller.displayed.face==Face.PHOTOS && photoContentPageId==null)beginPhotoContentDraft()
        releaseUnusedPhotoGrants();scene.refreshPhotoEditor()
        val dismissFilter=android.content.IntentFilter(StandByTriggerService.ACTION_DISMISS)
        runCatching{
            val screenFilter=android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
            registerReceiver(screenOffReceiver,screenFilter)
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(triggerDismissReceiver,dismissFilter,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(triggerDismissReceiver,dismissFilter)
        if(Build.VERSION.SDK_INT>=30){window.setDecorFitsSystemWindows(false);window.insetsController?.apply{hide(WindowInsets.Type.systemBars());systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE}}
        // Gesture help lives in accessibility descriptions; never cover the replica with a launch toast.
    }
    private val triggerPermissionRequest=4202
    private val triggerDismissReceiver=object:android.content.BroadcastReceiver(){
        override fun onReceive(context:android.content.Context,intent:android.content.Intent){finish()}
    }
    override fun onStart(){
        super.onStart()
        lastPlugged=null
        batteryControls.start()
        photoSource.start(scene.controller.state.face==Face.PHOTOS)
        batteryData.start { scene.updateBatteries(it) }
        val filter=IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky=if(Build.VERSION.SDK_INT>=33)registerReceiver(batteryReceiver,filter,Context.RECEIVER_NOT_EXPORTED)else registerReceiver(batteryReceiver,filter)
        batteryReceiverRegistered=true
        if(sticky!=null)lastPlugged=sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0
    }
    override fun onStop(){
        photoSource.stop()
        batteryControls.stop();batteryData.close()
        if(batteryReceiverRegistered){unregisterReceiver(batteryReceiver);batteryReceiverRegistered=false}
        lastPlugged=null
        if(::scene.isInitialized)scene.stopChargingPreview()
        super.onStop()
    }
    /** Our own MediaStore grant replaces the per-photo root read for the curated gallery. */
    private fun ensureMediaAccess(){
        val permission=if(android.os.Build.VERSION.SDK_INT>=33)android.Manifest.permission.READ_MEDIA_IMAGES
            else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if(checkSelfPermission(permission)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            runCatching{requestPermissions(arrayOf(permission),4203)}
        }
    }

    /** Notification access is the only grant the banner needs; no root and no overlay window. */
    private fun offerNotificationAccessIfNeeded(){
        val enabled=android.provider.Settings.Secure.getString(contentResolver,"enabled_notification_listeners")?.contains(packageName)==true
        if(enabled)return
        val prefs=getSharedPreferences("standby_trigger",MODE_PRIVATE)
        if(prefs.getBoolean("notification_prompt_shown",false))return
        prefs.edit().putBoolean("notification_prompt_shown",true).apply()
        runCatching{
            android.app.AlertDialog.Builder(this)
                .setTitle("StandBy 通知")
                .setMessage("开启通知使用权后，StandBy 里会像原版一样显示通知卡片（居中、几秒自动消失、可上滑关闭）。不需要 root。")
                .setPositiveButton("去开启"){_,_->startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))}
                .setNegativeButton("稍后",null)
                .show()
        }
    }

    override fun onDestroy(){
        NotificationRelay.onItem=null
        if(::notificationBanner.isInitialized)notificationBanner.dismissImmediately()
        runCatching{unregisterReceiver(triggerDismissReceiver)}
        runCatching{unregisterReceiver(screenOffReceiver)}
        // A stopped Activity can be destroyed while the external picker is open;
        // its saved draft still owns these grants until it is restored or cancelled.
        if(isFinishing){photoSource.cancelDraft();releaseUnusedPhotoGrants()}
        photoSource.close();super.onDestroy()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==BluetoothBatteryControls.REQUEST_CONNECT || requestCode==BluetoothBatteryControls.REQUEST_SCAN)
            batteryControls.permissionResult(requestCode)
    }
    private fun applyBrightness(){if(!::scene.isInitialized)return;val a=window.attributes;a.screenBrightness=if(scene.controller.state.night)0.04f else 0.30f;window.attributes=a}
    override fun onSaveInstanceState(outState:Bundle){
        outState.putBundle("standbyState",Bundle().also{scene.saveState(it)})
        photoSource.saveDraftState()?.let{outState.putString("photoEditorDraft",it)}
        outState.putString("photoContentPageId",photoContentPageId)
        outState.putBoolean("photoPickerOpen",photoPickerOpen)
        outState.putStringArrayList("freshPhotoGrants",ArrayList(freshPhotoGrants))
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Platform back bridge retained for API 28+ without an AndroidX dependency")
    override fun onBackPressed(){
        if(categoryEditor!=null){closePhotoCategoryEditor(false);return}
        if(!scene.handleBack())super.onBackPressed()
    }
    override fun onResume(){super.onResume();if(::scene.isInitialized)scene.refresh()}
}
