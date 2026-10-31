package local.duostandby

import android.content.Context
import android.os.Bundle
import java.time.LocalDateTime

enum class Face { CALENDAR, DIGITAL, ANALOG, WORLD, WIDGETS, FLOAT, SOLAR, PHOTOS, WEATHER }
data class FaceOptions(val style:Int=0,val palette:Int=0,val numerals:Int=0,val dark:Boolean=true,val showTime:Boolean=true,val showDate:Boolean=true,val nativeNumerals:Int=0,val digitalTypeface:Int=0,val digitalLook:Int=0,val calendarTypeface:Int=3,val calendarLook:Int=5,val calendarHeader:CalendarHeaderSelection=CalendarHeaderSelection.LegacyDefault,val weatherPhase:Int=0) {
    fun checked(face:Face)=copy(calendarLook=calendarLook.coerceIn(0,7),calendarTypeface=if(calendarTypeface in listOf(0,3,2))calendarTypeface else 3,digitalLook=digitalLook.coerceIn(0,26),weatherPhase=weatherPhase.coerceIn(0,3),digitalTypeface=digitalTypeface.coerceIn(0,2),style=style.coerceIn(0,if(face==Face.ANALOG)2 else 1),nativeNumerals=if(nativeNumerals in listOf(0,1,3))nativeNumerals else 0,palette=palette.coerceIn(0,if(face==Face.ANALOG)7 else 5),numerals=numerals.coerceIn(0,3))
    companion object { fun defaults(face:Face)=FaceOptions(dark=face==Face.ANALOG,style=if(face==Face.CALENDAR)1 else 0) }
}
data class StandByState(val face:Face=Face.ANALOG,val night:Boolean=false,val options:FaceOptions=FaceOptions.defaults(face))
sealed interface StandByEvent {
    data class Select(val face:Face):StandByEvent
    data class Swipe(val step:Int):StandByEvent
    data class SwipeStyle(val step:Int):StandByEvent
    data class WeatherPhase(val step:Int):StandByEvent
    data object ToggleNight:StandByEvent
}
class StandByController(context:Context,fixedTime:LocalDateTime?=null,initialFace:Face?=null,night:Boolean?=null) {
    private val prefs=context.getSharedPreferences("standby",Context.MODE_PRIVATE)
    private val values=prefs.all
    private val optionCache=mutableMapOf<Face,FaceOptions>()
    val referenceMode=fixedTime!=null
    private val referenceTime=fixedTime
    var state=StandByState(initialFace?:runCatching{Face.valueOf(values["face"] as? String?:"ANALOG")}.getOrDefault(Face.ANALOG),night?:values["night"] as? Boolean?:false)
        private set
    var clockFace:Face = if(state.face in OriginalStandByNavigation.clockFaces)state.face else
        runCatching { Face.valueOf(values["clockFace"] as? String ?: "ANALOG") }.getOrDefault(Face.ANALOG)
            .takeIf { it in OriginalStandByNavigation.clockFaces } ?: Face.ANALOG
        private set
    var draft:StandByState?=null;private set
    var editorTab=0
    val editing get()=draft!=null
    val displayed get()=draft?:state
    init { state=state.copy(options=loadOptions(state.face)) }
    fun now()=referenceTime?:LocalDateTime.now()
    private fun loadOptions(face:Face):FaceOptions = optionCache.getOrPut(face) {
        val v=values;val k=face.name+".";val d=FaceOptions.defaults(face)
        FaceOptions(v[k+"style"] as? Int?:d.style,v[k+"palette"] as? Int?:d.palette,v[k+"numerals"] as? Int?:d.numerals,v[k+"dark"] as? Boolean?:d.dark,v[k+"time"] as? Boolean?:true,v[k+"date"] as? Boolean?:true,v[k+"nativeNumerals"] as? Int?:0,v[k+"digitalTypeface"] as? Int?:0,v[k+"digitalLook"] as? Int?:0,v[k+"calendarTypeface"] as? Int?:3,v[k+"calendarLook"] as? Int?:5,OriginalCalendarWidgetOption.decode(v[k+"calendarHeader"] as? String),v[k+"weatherPhase"] as? Int?:0).checked(face)
    }
    fun forFace(face:Face)=if(face==displayed.face)displayed else StandByState(face,state.night,loadOptions(face))
    fun dispatch(event:StandByEvent) {
        if(editing)return
        // Weather complication page: state-only event, must not go through loadOptions(selected)
        // which would overwrite the new phase with the cached options.
        if(event is StandByEvent.WeatherPhase){
            state=state.copy(options=state.options.copy(weatherPhase=(state.options.weatherPhase+event.step).coerceIn(0,3)))
            persist(); return
        }
        val selected=when(event){
            is StandByEvent.Select -> event.face
            is StandByEvent.Swipe -> {
                val mode=OriginalStandByNavigation.mode(state.face)
                val next=(mode.ordinal+event.step).coerceIn(0,OriginalStandByNavigation.Mode.entries.lastIndex)
                OriginalStandByNavigation.face(OriginalStandByNavigation.Mode.entries[next],clockFace)
            }
            is StandByEvent.SwipeStyle -> if(OriginalStandByNavigation.mode(state.face)==OriginalStandByNavigation.Mode.CLOCKS)
                OriginalStandByNavigation.clockFaces[(OriginalStandByNavigation.clockIndex(state.face)+event.step).coerceIn(0,OriginalStandByNavigation.clockFaces.lastIndex)] else state.face
            is StandByEvent.WeatherPhase -> state.face
            StandByEvent.ToggleNight -> state.face
        }
        state=state.copy(face=selected,options=loadOptions(selected),night=if(event==StandByEvent.ToggleNight)!state.night else state.night)
        if(selected in OriginalStandByNavigation.clockFaces)clockFace=selected
        persist()
    }
    fun rememberClockFace(face:Face){
        if(face !in OriginalStandByNavigation.clockFaces)return
        clockFace=face
        if(!referenceMode)prefs.edit().putString("clockFace",face.name).apply()
    }
    fun beginEdit(){if(draft==null){draft=state.copy();editorTab=if(state.face==Face.DIGITAL)1 else 0}}
    fun edit(change:(FaceOptions)->FaceOptions){draft=draft?.let{it.copy(options=change(it.options).checked(it.face))}}
    fun confirmEdit(){draft?.let{state=it};draft=null;persist()}
    fun cancelEdit(){draft=null}
    fun editNight(){draft=draft?.let{it.copy(night=!it.night)}}
    private fun persist(){
        optionCache[state.face]=state.options
        if(referenceMode)return
        val o=state.options;val k=state.face.name+"."
        prefs.edit().putString("face",state.face.name).putString("clockFace",clockFace.name).putBoolean("night",state.night).putInt(k+"style",o.style).putInt(k+"palette",o.palette).putInt(k+"numerals",o.numerals).putBoolean(k+"dark",o.dark).putBoolean(k+"time",o.showTime).putBoolean(k+"date",o.showDate).putInt(k+"nativeNumerals",o.nativeNumerals).putInt(k+"digitalTypeface",o.digitalTypeface).putInt(k+"digitalLook",o.digitalLook).putInt(k+"calendarTypeface",o.calendarTypeface).putInt(k+"calendarLook",o.calendarLook).putString(k+"calendarHeader",OriginalCalendarWidgetOption.encode(o.calendarHeader)).putInt(k+"weatherPhase",o.weatherPhase).commit()
    }
    fun save(out:Bundle){out.putString("clockFace",clockFace.name);out.putBundle("committed",pack(state));draft?.let{out.putBundle("draft",pack(it))};out.putInt("editorTab",editorTab)}
    fun restore(saved:Bundle?){if(saved==null)return;clockFace=runCatching{Face.valueOf(saved.getString("clockFace")?:clockFace.name)}.getOrDefault(clockFace).takeIf{it in OriginalStandByNavigation.clockFaces}?:Face.ANALOG;saved.getBundle("committed")?.let{state=unpack(it,state)};draft=saved.getBundle("draft")?.let{unpack(it,state)};editorTab=saved.getInt("editorTab",0).coerceIn(0,3)}
    private fun pack(s:StandByState)=Bundle().apply{putString("face",s.face.name);putBoolean("night",s.night);putInt("style",s.options.style);putInt("palette",s.options.palette);putInt("numerals",s.options.numerals);putBoolean("dark",s.options.dark);putBoolean("time",s.options.showTime);putBoolean("date",s.options.showDate);putInt("nativeNumerals",s.options.nativeNumerals);putInt("digitalTypeface",s.options.digitalTypeface);putInt("digitalLook",s.options.digitalLook);putInt("calendarTypeface",s.options.calendarTypeface);putInt("calendarLook",s.options.calendarLook);putString("calendarHeader",OriginalCalendarWidgetOption.encode(s.options.calendarHeader))}
    private fun unpack(b:Bundle,fallback:StandByState)=runCatching{val f=Face.valueOf(b.getString("face")?:fallback.face.name);StandByState(f,b.getBoolean("night"),FaceOptions(b.getInt("style"),b.getInt("palette"),b.getInt("numerals"),b.getBoolean("dark",f==Face.ANALOG),b.getBoolean("time",true),b.getBoolean("date",true),b.getInt("nativeNumerals",0),b.getInt("digitalTypeface",0),b.getInt("digitalLook",0),b.getInt("calendarTypeface",3),b.getInt("calendarLook",5),OriginalCalendarWidgetOption.decode(b.getString("calendarHeader"))).checked(f))}.getOrDefault(fallback)
}
