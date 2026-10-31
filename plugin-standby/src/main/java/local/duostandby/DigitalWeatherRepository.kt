package local.duostandby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real weather data adapter for the digital face's weather/sunEvents slots.
 *
 * The original components are Weather.app/PlugIns/WeatherWidget.appex
 * (TemperatureComplicationWidget / SunEventsContentView) backed by WeatherKit.
 * WeatherKit is not available on Android, so this adapter uses Open-Meteo
 * (real observations, no fabricated values) at the device's last known location.
 * If no location/permission/network is available the snapshot is null and the
 * widgets stay hidden.
 */
object DigitalWeatherRepository {
    private const val PREFS = "digital_weather"
    private const val KEY = "snapshot"
    private val fetching = AtomicBoolean(false)
    @Volatile var current: Snapshot? = null
        private set
    /** Called on the UI thread target after a fresh snapshot; the host invalidates. */
    @Volatile var onUpdate: (() -> Unit)? = null

    data class Hour(val tempC: Int, val code: Int, val hour: String)
    data class Day(val code: Int, val maxC: Int, val minC: Int, val date: String)
    data class Snapshot(
        val tempC: Int?, val code: Int?,
        val sunrise: String?, val sunset: String?,
        val fetchedAt: Long,
        val hours: List<Hour> = emptyList(),
        val days: List<Day> = emptyList(),
        val nextHourMm: List<Double> = emptyList(),
    )

    fun load(context: Context) {
        current = current ?: runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
            val o = JSONObject(raw)
            val hours = mutableListOf<Hour>()
            o.optJSONArray("hours")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { h ->
                    hours += Hour(h.optInt("t"), h.optInt("c"), h.optString("h"))
                }
            }
            val days = mutableListOf<Day>()
            o.optJSONArray("days")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { d ->
                    days += Day(d.optInt("c"), d.optInt("max"), d.optInt("min"), d.optString("date"))
                }
            }
            val nextHour = mutableListOf<Double>()
            o.optJSONArray("nextHour")?.let { a -> for (i in 0 until a.length()) nextHour += a.optDouble(i, 0.0) }
            Snapshot(o.optInt("temp", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                o.optInt("code", -1).takeIf { it >= 0 },
                o.optString("sunrise", null), o.optString("sunset", null), o.optLong("at", 0),
                hours, days, nextHour)
        }.getOrNull()
    }

    fun refresh(context: Context) {
        val snapshot = current
        if (snapshot != null && System.currentTimeMillis() - snapshot.fetchedAt < 30 * 60_000L) return
        if (!fetching.compareAndSet(false, true)) return
        Thread {
            try {
                val location = lastLocation(context) ?: return@Thread
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${location.first}" +
                    "&longitude=${location.second}&current=temperature_2m,weather_code" +
                    "&hourly=temperature_2m,weather_code" +
                    "&minutely_15=precipitation" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                    "&timezone=auto&forecast_days=4")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val currentObj = root.optJSONObject("current")
                val daily = root.optJSONObject("daily")
                val sunrise = daily?.optJSONArray("sunrise")?.optString(0)
                val sunset = daily?.optJSONArray("sunset")?.optString(0)
                val hours = mutableListOf<Hour>()
                val hourly = root.optJSONObject("hourly")
                val hTimes = hourly?.optJSONArray("time")
                val hTemps = hourly?.optJSONArray("temperature_2m")
                val hCodes = hourly?.optJSONArray("weather_code")
                if (hTimes != null && hTemps != null && hCodes != null) {
                    val nowHour = java.time.LocalDateTime.now().hour
                    for (i in 0 until hTimes.length()) {
                        val t = hTimes.optString(i)
                        val hour = t.substringAfter('T').take(2).toIntOrNull() ?: continue
                        if (hour < nowHour) continue
                        hours += Hour(hTemps.optDouble(i, Double.NaN).takeIf { !it.isNaN() }?.toInt() ?: continue,
                            hCodes.optInt(i, -1), hour.toString().padStart(2, '0'))
                        if (hours.size >= 12) break
                    }
                }
                val days = mutableListOf<Day>()
                val dCodes = daily?.optJSONArray("weather_code")
                val dMax = daily?.optJSONArray("temperature_2m_max")
                val dMin = daily?.optJSONArray("temperature_2m_min")
                val dTimes = daily?.optJSONArray("time")
                if (dCodes != null && dMax != null && dMin != null && dTimes != null) {
                    for (i in 0 until minOf(4, dTimes.length())) {
                        days += Day(dCodes.optInt(i, 0),
                            dMax.optDouble(i, Double.NaN).takeIf { !it.isNaN() }?.toInt() ?: 0,
                            dMin.optDouble(i, Double.NaN).takeIf { !it.isNaN() }?.toInt() ?: 0,
                            dTimes.optString(i))
                    }
                }
                val nextHour = mutableListOf<Double>()
                val minutely = root.optJSONObject("minutely_15")
                val mTimes = minutely?.optJSONArray("time")
                val mPrecip = minutely?.optJSONArray("precipitation")
                if (mTimes != null && mPrecip != null) {
                    val nowIso = java.time.LocalDateTime.now().toString().take(16)
                    for (i in 0 until mTimes.length()) {
                        if (mTimes.optString(i).take(16) < nowIso) continue
                        nextHour += mPrecip.optDouble(i, 0.0)
                        if (nextHour.size >= 4) break
                    }
                }
                val snapshot = Snapshot(
                    currentObj?.optDouble("temperature_2m", Double.NaN)?.takeIf { !it.isNaN() }?.toInt(),
                    currentObj?.optInt("weather_code", -1)?.takeIf { it >= 0 },
                    sunrise?.substringAfter('T'), sunset?.substringAfter('T'),
                    System.currentTimeMillis(), hours, days, nextHour)
                current = snapshot
                onUpdate?.invoke()
                val hourArray = org.json.JSONArray()
                snapshot.hours.forEach { hourArray.put(JSONObject().put("t", it.tempC).put("c", it.code).put("h", it.hour)) }
                val dayArray = org.json.JSONArray()
                snapshot.days.forEach { dayArray.put(JSONObject().put("c", it.code).put("max", it.maxC).put("min", it.minC).put("date", it.date)) }
                val nextArray = org.json.JSONArray()
                snapshot.nextHourMm.forEach { nextArray.put(it) }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY,
                    JSONObject().put("temp", snapshot.tempC ?: Int.MIN_VALUE)
                        .put("code", snapshot.code ?: -1)
                        .put("sunrise", snapshot.sunrise).put("sunset", snapshot.sunset)
                        .put("hours", hourArray).put("days", dayArray).put("nextHour", nextArray)
                        .put("at", snapshot.fetchedAt).toString()).apply()
            } catch (_: Throwable) {
                // Keep the previous snapshot; no fabricated fallback.
            } finally {
                fetching.set(false)
            }
        }.start()
    }

    private fun lastLocation(context: Context): Pair<Double, Double>? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val manager = context.getSystemService(LocationManager::class.java) ?: return null
            val location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            location?.let { it.latitude to it.longitude }
        }.getOrNull()
    }

    /** Next sun event after [now] from the daily sunrise/sunset strings. */
    fun nextSunEvent(now: LocalTime): Pair<String, String>? {
        val s = current ?: return null
        val sunrise = s.sunrise?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
        val sunset = s.sunset?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
        return when {
            sunrise != null && sunrise.isAfter(now) -> "sunrise" to sunrise.toString()
            sunset != null && sunset.isAfter(now) -> "sunset" to sunset.toString()
            sunrise != null -> "sunrise" to sunrise.toString()
            sunset != null -> "sunset" to sunset.toString()
            else -> null
        }
    }
}
