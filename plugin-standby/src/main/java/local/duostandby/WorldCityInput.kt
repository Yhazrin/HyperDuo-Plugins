package local.duostandby

import android.content.Context
import org.json.JSONArray
import java.time.ZoneId

/** Platform data adapter, NOT an Apple editor or a fabricated set of cities.
 * Apple reads configured MobileTimer.WorldClockManager cities. Android has no
 * access to that private store. Only caller-supplied records are accepted here.
 * Reference intent data is transient; the app-owned JSON file is opt-in data.
 */
object WorldCityInput {
    fun read(context:Context,referenceJson:String?):List<OriginalWorldPins.City> {
        val json=referenceJson ?: java.io.File(context.filesDir,"world-cities-v1.json").let {
            if(!it.exists())return emptyList();require(it.length()<=262144);it.readText()
        }
        require(json.length<=262144)
        val a=JSONArray(json);require(a.length()<=256)
        val cities=(0 until a.length()).map { i ->
            val c=a.getJSONObject(i)
            OriginalWorldPins.City(c.getString("id"),c.getString("name"),c.getDouble("latitude"),
                c.getDouble("longitude"),ZoneId.of(c.getString("timeZone")),c.optBoolean("currentLocation",false))
        }
        require(cities.count{it.currentLocation}<=1)
        require(cities.map{it.id}.distinct().size==cities.size)
        return cities
    }
}
