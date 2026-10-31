package local.duostandby

import kotlin.math.*

/** 24A94425 SimpleClockPosterExtension: deriveDial 0x10000b898,
 * TickShape.path 0x10001d25c and CFPUI radius 0x249786244.
 * Finite positive source-point domain. No fixed scene, viewport fit or new easing.
 * This produces native line endpoints; stroke/raster adapter is a separate layer.
 */
object OriginalAnalogTicks {
    data class Rect(val x:Double, val y:Double, val width:Double, val height:Double)
    data class Point(val x:Double,val y:Double)
    data class Segment(val outer:Point,val inner:Point,val thickness:Double)
    data class Metrics(val length:Double,val thickness:Double,val height:Double,val width:Double)
    data class Dial(
        val widthClass:Int,val padding:Double,val horizontalPadding:Double,val cornerRadius:Double,
        val verticalScale:Double,val horizontalScale:Double,val major:Metrics,val minor:Metrics,
        val nonuniformHeightSmall:Double,val nonuniformHeightBig:Double,val nonuniformWidth:Double,
        val inset39:Double,val width39:Double,val height15711:Double,val inset24810:Double,val width24810:Double,
    )
    data class Tick(val index:Int,val angle:Double,val uniformLength:Double,val uniformProgress:Double,
        val collapsedProgress:Double,val thickness:Double,val uniformWidth:Double,val uniformHeight:Double)
    private fun ra(v:Double)=sign(v)*floor(abs(v)+0.5)
    // ClockKit CGFloat.interpolate @0x1faaaa250: clamp then FMA, not unconstrained lerp.
    private fun interpolate(start:Double,end:Double,fraction:Double):Double =
        Math.fma(end-start,fraction.coerceIn(0.0,1.0),start)

    fun dial(w:Double,h:Double,leading:Double=0.0,trailing:Double=0.0):Dial {
        require(w.isFinite() && h.isFinite() && w>0.0 && h>0.0)
        val cls=if(abs(w/h)<1.5)0 else if(abs(w/h)<2.0)1 else 2
        if(cls==0)return Dial(0,16.0,2.0,0.0,1.0,1.0,
            Metrics(105.0,5.0,73.0,105.0),Metrics(42.0,3.0,28.0,42.0),
            55.0,78.0,74.0,102.0,39.0,71.0,148.0,35.0)
        val p=29.0;val hp=max(max(leading,trailing)-p,0.0)
        val sy=(h-2*p)/(402.0-2*p);val sx=(w-2*p-2*hp)/(874.0-2*p-2*hp)
        return Dial(cls,p,hp,0.0,sy,sx,
            Metrics(147.0,5.0,ra(56*sy),ra(147*sx)),Metrics(28.0,3.0,ra(28*sy),ra(28*sx)),
            ra(42*sy),ra(78*sy),ra(67*sx),ra(92*sx),ra(71*sx),ra(61*sy),ra(191*sx),ra(36*sx))
    }

    /** Original layout enum: ticks=0, ticksAndMajorNumerals=1, numeralsDial=2.
     * The caller must retain the original visibility/color rules; geometry alone
     * does not imply every returned major tick is visible in every layout.
     */
    fun ticks(d:Dial,layoutRaw:Int):List<Tick> {
        require(layoutRaw in 0..2)
        val majorProgress=doubleArrayOf(1.0,0.0,-1.0)[layoutRaw]
        return (0..59).map { i ->
            val major=i%5==0;val m=if(major)d.major else d.minor
            Tick(i,(2.0*PI/60.0)*i,m.length,if(major)majorProgress else 1.0,
                if(!major && layoutRaw==2)1.0 else 0.0,
                if(major && d.widthClass==0 && layoutRaw==2)5.5 else m.thickness,m.width,m.height)
        }
    }

    /** CFPUIRadiusForRectAtAngleWithInset: original Float angle modulo,
     * normal positive CGRect dimensions and source FMA operation order retained.
     * C is the fourth floating argument; callers must not double-subtract padding.
     */
    fun radius(rect:Rect,c:Double,angle:Double,insetX:Double,insetY:Double):Double {
        var a=(angle.toFloat()%Float.fromBits(0x40c90fdb)).toDouble()
        while(a<0.0)a+=2.0*PI
        a=when {a<PI/2.0->a;a<PI->PI-a;a<3.0*PI/2.0->a-PI;else->2.0*PI-a}
        val x=Math.fma(-2.0,insetX,rect.width)/2.0
        val y=Math.fma(-2.0,insetY,rect.height)/2.0
        val r=max(0.0,1.52866*(c-insetY))
        val low=atan2(y-r,x);val high=atan2(y,x-r)
        val px:Double;val py:Double
        if(a<=low){px=x;py=x*tan(a)}
        else if(a>high){py=y;px=y/tan(a)}
        else {
            var lo=0.0;var hi=PI/2.0;var count=0;var xx=0.0;var yy=0.0
            do {
                val t=(lo+hi)/2.0
                xx=Math.fma(r,cos(t).pow(0.6224092216150273),x-r)
                yy=Math.fma(r,sin(t).pow(0.6224092216150273),y-r)
                val e=a-atan2(yy,xx)
                if(e<0.0)hi=t else if(e>0.0)lo=t
                if(abs(e)<=0.01)break
                count++
            } while(count<100)
            px=xx;py=yy
        }
        return sqrt(Math.fma(px,px,py*py))
    }

    fun path(rect:Rect,d:Dial,t:Tick,squareProgress:Double):Segment {
        val p=d.padding+t.thickness*0.5
        val hp=d.horizontalPadding
        val c=d.cornerRadius-p
        val sine=sin(t.angle);val cosine=cos(t.angle)
        val ac=abs(cosine);val ass=abs(sine)
        var height=t.uniformHeight;var width=t.uniformWidth
        if(ac==1.0 || ass==1.0 || (ass>0.8 && d.widthClass!=0)) {
            height=interpolate(d.nonuniformHeightSmall,height,t.uniformProgress)
            width=interpolate(d.nonuniformWidth,width,t.uniformProgress)
        } else if(ass<=0.8 && d.widthClass!=0) {
            height=interpolate(d.nonuniformHeightBig,height,t.uniformProgress)
        }
        val circle=min(rect.width,rect.height)*0.5*(1.0-squareProgress)
        var outer=radius(rect,c,t.angle,hp+p,p)*squareProgress+circle
        val base=p+circle
        val w=rect.width-p-hp;val h=rect.height-p
        val threshold=w/sqrt(h*h+w*w)
        val collapse=1.0-t.collapsedProgress
        var inner=if(threshold<ac)
            radius(rect,0.0,t.angle,hp+base+(width-t.thickness)*collapse,0.0)
        else radius(rect,0.0,t.angle,0.0,base+(height-t.thickness)*collapse)
        if(t.uniformProgress<0.0 && t.index%5==0){
            var extra=0.0;var forceHorizontal=false
            when {
                ass==1.0->{height=0.0;width=0.0}
                ac==1.0->{extra=d.inset39;width=d.width39}
                ass>0.8->{height=d.height15711}
                else->{extra=d.inset24810;width=d.width24810;forceHorizontal=true}
            }
            val targetOuter=radius(rect,c,t.angle,hp+p+extra,base)
            val targetInner=if(forceHorizontal || threshold<ac)
                radius(rect,0.0,t.angle,p+extra+hp+(width-t.thickness)*collapse,0.0)
            else radius(rect,0.0,t.angle,0.0,base+(height-t.thickness)*collapse)
            outer=interpolate(outer,targetOuter,-t.uniformProgress)
            inner=interpolate(inner,targetInner,-t.uniformProgress)
        }
        val cx=rect.x+rect.width/2.0;val cy=rect.y+rect.height/2.0
        return Segment(Point(cx+cosine*outer,cy-sine*outer),Point(cx+cosine*inner,cy-sine*inner),t.thickness)
    }
}
