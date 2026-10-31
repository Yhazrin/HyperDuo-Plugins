package local.duostandby

import kotlin.math.abs

/** 24A94425 PosterBoardUIServices + PosterKit + BaseBoardUI, explicit host inputs.
 * Port of recovered rules, NOT measurements of a Duo device. No Android metrics,
 * inferred orientation, guessed role, fit scale or default screen dimensions.
 * Native CGFloat calculations stay Double until the renderer boundary.
 */
object OriginalPosterHostContract {
    // BSSizeEqualToSize 0x1d18382a8 loads the exact double bits3cb0000000000000.
    val SIZE_EQUAL_EPSILON:Double=Double.fromBits(0x3cb0000000000000L)
    enum class Orientation(val nativeValue:Int,val degrees:Int) {
        PORTRAIT(1,0), UPSIDE_DOWN(2,180), LANDSCAPE_LEFT(3,90), LANDSCAPE_RIGHT(4,-90);
        val landscape get()=this==LANDSCAPE_LEFT || this==LANDSCAPE_RIGHT
    }
    data class Frame(val x:Double,val y:Double,val width:Double,val height:Double) {
        init {
            // Adapter guard: unknown/invalid dimensions must not become fake native inputs.
            require(listOf(x,y,width,height).all{it.isFinite()} && width>0.0 && height>0.0)
        }
        fun swappedSize()=copy(width=height,height=width)
    }
    data class Inputs(
        val displayConfigurationBounds:Frame?, // null means observed missing configuration, not unknown
        val hostViewBounds:Frame,               // PRUIS host view, NOT poster root view
        val resolvedSceneOrientation:Orientation, // resolver output supplied; no invented fallback
        val supportedInterfaceOrientations:Long,
        val posterRole:String,
        val posterRootBounds:Frame,             // independent PRPosterRootViewController bounds
        val windowOrientation:Orientation?,    // null means verified no window (native fallback1)
        val wrapperBounds:Frame?              // observed after wrapper.frame=root.bounds; required with countertransform
    )
    data class Result(
        val sceneFrame:Frame,                   // model.canvas derives from this, never content bounds
        val appliesCountertransform:Boolean,
        val dynamicRotation:Boolean,
        val containerOrientation:Orientation?,  // null when ordinary rendering view has no wrapper
        val wrapperFrame:Frame?,                // frame assignment is separate from wrapper local bounds
        val contentBounds:Frame,
        val contentRotationDegrees:Int
    )
    fun sizeEqual(a:Frame,b:Frame):Boolean =
        abs(a.width-b.width)<SIZE_EQUAL_EPSILON && abs(a.height-b.height)<SIZE_EQUAL_EPSILON

    /** PFPosterRoleSupportsDynamicRotation 0x1f8a232c0; Ambient is NOT in this array. */
    fun supportsDynamicRotation(role:String):Boolean = role in setOf(
        "PRPosterRoleLockScreen", "PRPosterRoleIncomingCall", "PRPosterRoleBackdrop")

    /** Live PRUIS closure writes flag0x50bd from mask!=2, independently of role. */
    fun appliesCountertransform(mask:Long):Boolean = mask!=2L

    /** 0x1fefed454 + PUIContentFrameForDisplayConfigurationAndOrientation.
     * Full display size (in either orientation) selects D including its origin.
     * Otherwise preserve V origin, swapping only for a present display+landscape.
     * Incoming presentation size is deliberately not an argument to this rule.
     */
    fun sceneFrame(display:Frame?,view:Frame,orientation:Orientation):Frame {
        if(display==null)return view
        if(sizeEqual(view,display) || sizeEqual(view,display.swappedSize()))return display
        return if(orientation.landscape)view.swappedSize() else view
    }
    fun resolve(input:Inputs):Result {
        val scene=sceneFrame(input.displayConfigurationBounds,input.hostViewBounds,input.resolvedSceneOrientation)
        val counter=appliesCountertransform(input.supportedInterfaceOrientations)
        val dynamic=supportsDynamicRotation(input.posterRole)
        if(!counter)return Result(scene,false,dynamic,null,null,input.posterRootBounds,0)
        // PRPosterRootViewController fixes contentOrientation=1 and copies root.bounds.
        val container=if(dynamic)input.windowOrientation?:Orientation.PORTRAIT else Orientation.PORTRAIT
        // Setting frame does not establish bounds.origin. Never substitute root origin.
        val wrapper=requireNotNull(input.wrapperBounds){"Observed wrapper bounds required for countertransform"}
        val content=if(container.landscape)wrapper.swappedSize() else wrapper
        return Result(scene,true,dynamic,container,input.posterRootBounds,content,-container.degrees)
    }
}
