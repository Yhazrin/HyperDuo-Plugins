package local.duostandby

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * SolarPosition.percentComplete for the ClockPoster Sky palette.
 *
 * The source input is the apparent solar-time fraction (solar noon = 0.5), NOT local clock/24.
 * Without a device location this uses the zone's standard central meridian plus the equation of
 * time. The longitude-within-zone and apparent-vs-mean corrections are an explicit Android
 * adapter; once a real location is available the same function can take its longitude.
 */
object OriginalSolarPhase {
    @Volatile private var requestedLongitude: Double? = null

    /** One current-location request, only when the permission was already granted.
     * No permission prompt is raised here. */
    fun requestLocation(context: Context) {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        runCatching {
            val manager = context.getSystemService(LocationManager::class.java) ?: return
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 30) {
                val executor = context.mainExecutor
                providers.forEach { provider ->
                    manager.getCurrentLocation(provider, null, executor) { location ->
                        if (location != null) requestedLongitude = location.longitude
                    }
                }
            } else {
                val listener = android.location.LocationListener { location ->
                    if (location != null) requestedLongitude = location.longitude
                }
                providers.forEach { provider ->
                    @Suppress("DEPRECATION")
                    runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }
                }
            }
        }
    }

    /** Last requested or known coarse/fine longitude; null when location is unavailable. */
    fun locationLongitude(context: Context): Double? {
        requestedLongitude?.let { return it }
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return runCatching {
            val manager = context.getSystemService(LocationManager::class.java) ?: return null
            val location = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            location?.longitude
        }.getOrNull()
    }

    fun percentComplete(dateTime: LocalDateTime, zone: ZoneId): Float =
        percentComplete(dateTime, zone, longitudeDegrees = null)

    fun percentComplete(dateTime: LocalDateTime, zone: ZoneId, longitudeDegrees: Double?): Float {
        val instant = dateTime.atZone(zone).toInstant()
        val daySeconds = ((instant.epochSecond % 86400L) + 86400L) % 86400L
        val utcMinutes = daySeconds / 60.0
        val standardHours = zone.rules.getStandardOffset(instant).totalSeconds / 3600.0
        val longitude = longitudeDegrees ?: (standardHours * 15.0)
        val dayOfYear = dateTime.dayOfYear
        val b = 2.0 * PI * (dayOfYear - 81) / 364.0
        // NOAA approximation, minutes; apparent solar time = mean solar time + EoT.
        val eot = 9.87 * sin(2.0 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val solarMinutes = utcMinutes + longitude * 4.0 + eot
        val wrapped = ((solarMinutes % 1440.0) + 1440.0) % 1440.0
        return (wrapped / 1440.0).toFloat()
    }
}
