package local.duostandby

import java.time.Instant
import java.time.ZoneId
import kotlin.math.*

/** Original ClockPoster 24A94425: 23f80 projection, 28cc8 tap, 2740c highlighted.
 * Caller owns actual configured cities; never seed a guessed Apple city list.
 * Ordered Android input is not a claim about Swift Dictionary iteration order.
 */
object OriginalWorldPins {
    data class City(val id:String,val name:String,val latitude:Double,val longitude:Double,
                    val timeZone:ZoneId,val currentLocation:Boolean=false) {
        init { require(id.isNotBlank() && latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0) }
    }
    data class Point(val x:Double,val y:Double)
    data class Diameters(val backdrop:Double,val middle:Double,val accent:Double)
    fun diameters(current:Boolean,selection:Double):Diameters = if(current)
        Diameters(14.0+4.0*selection,24.0+6.0*selection,16.0+4.0*selection)
        else Diameters(16.0+36.0*selection,16.0+6.0*selection,18.0+6.0*selection)
    private fun roundAway(x:Double)=if(x<0.0)ceil(x-0.5)else floor(x+0.5)
    // UIPointRoundToScale: whole + rounded fractional physical pixels.
    private fun pixel(x:Double,scale:Double):Double {
        if(scale==1.0)return roundAway(x)
        val whole=floor(x);return whole+roundAway((x-whole)*scale)/scale
    }
    fun project(city:City,frame:OriginalWorldMapGeometry.Frame,displayScale:Double):Point {
        require(displayScale.isFinite()&&displayScale>0.0)
        fun axis(raw:Double,origin:Double)=roundAway((raw-origin)/12.0)*12.0+origin
        val rawX=axis((city.longitude+180.0)*737.0/360.0,13.666)
        val rawY=axis((90.0-city.latitude)*369.0/180.0,3.333)
        val ratio=frame.width/737.0
        return Point(frame.x+pixel(rawX*ratio,displayScale),frame.y+pixel(rawY*ratio,displayScale))
    }
    class Model(val cities:List<City>,localZone:ZoneId=ZoneId.systemDefault()) {
        init {
            require(cities.map{it.id}.distinct().size==cities.size)
            val configured=cities.filterNot{it.currentLocation}
            require(configured.map{it.latitude to it.longitude}.distinct().size==configured.size)
            require(cities.count{it.currentLocation}<=1)
        }
        var selectedTimeZone:ZoneId=localZone;private set
        var selectedId:String?=null; private set
        private val selectedAt=mutableMapOf<String,Double>()
        fun select(id:String?) {require(id==null || cities.any{it.id==id});selectedId=id}
        fun isSelected(city:City):Boolean {
            val chosen=cities.firstOrNull{it.id==selectedId}?:return false
            return city.latitude==chosen.latitude && city.longitude==chosen.longitude
        }
        val orderedCities:List<City> get()=cities.sortedWith(Comparator { a,b ->
            val at=selectedAt[a.id];val bt=selectedAt[b.id]
            when {at==null&&bt!=null -> -1;at!=null&&bt==null -> 1
                at!=null&&bt!=null -> at.compareTo(bt)
                a.latitude!=b.latitude -> a.latitude.compareTo(b.latitude)
                else -> a.longitude.compareTo(b.longitude)}
        })
        fun highlighted(instant:Instant,localZone:ZoneId):City? {
            val pin=cities.firstOrNull{!it.currentLocation&&isSelected(it)}
                ?:cities.firstOrNull{it.currentLocation}
                ?:cities.firstOrNull{it.timeZone.rules.getOffset(instant)==localZone.rules.getOffset(instant)}
            // 27d7c nil only publishes nil; 27e74 nonnil updates clock VM.
            if(pin!=null)selectedTimeZone=pin.timeZone
            return pin
        }
        fun tap(x:Double,y:Double,frame:OriginalWorldMapGeometry.Frame,scale:Double,mediaTime:Double):City? {
            require(x.isFinite()&&y.isFinite()&&mediaTime.isFinite())
            var choice:City?=null
            for(city in orderedCities){
                val p=project(city,frame,scale)
                // CGRectContainsPoint includes min and excludes max; no circular hit approximation.
                if(x<p.x-22.0||x>=p.x+22.0||y<p.y-22.0||y>=p.y+22.0)continue
                val last=selectedAt[city.id];val prior=choice?.let{selectedAt[it.id]}
                if(choice==null || last==null || (prior!=null&&last<=prior))choice=city
            }
            selectedId=choice?.id;choice?.let{selectedAt[it.id]=mediaTime};return choice
        }
    }
    /** 4efb0: original mass2 stiffness300 damping29.9335 initialVelocity0.
     * Analytic oscillator with source parameters. Host interruption/settling
     * tolerance is explicit; not evidence of SwiftUI's transaction/RenderBox parity.
     */
    class SelectionSpring(initial:Double) {
        data class Sample(val value:Double,val velocity:Double,val running:Boolean)
        private var value=initial;private var velocity=0.0;private var target=initial
        private var time:Double?=null
        fun sample(next:Double,now:Double,animated:Boolean=true):Sample {
            require(next.isFinite()&&now.isFinite()&&(time==null||now>=time!!))
            if(!animated){value=next;velocity=0.0;target=next;time=now;return Sample(value,velocity,false)}
            time?.let { old ->
                val dt=now-old;val decay=29.9335/(2.0*2.0);val omega=sqrt(300.0/2.0-decay*decay)
                val y=value-target;val b=(velocity+decay*y)/omega
                val c=cos(omega*dt);val s=sin(omega*dt);val e=exp(-decay*dt)
                value=target+e*(y*c+b*s)
                velocity=e*((b*omega-decay*y)*c+(-y*omega-decay*b)*s)
            }
            time=now;target=next
            val running=abs(value-target)>=0.00001||abs(velocity)>=0.0001
            if(!running){value=target;velocity=0.0}
            return Sample(value,velocity,running)
        }
    }
}
