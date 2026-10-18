package com.dsoft.macrointegrate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** One-shot current location via the framework LocationManager (no Play Services). */
object Locations {
    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Calls [cb] on the main thread with a fix, or null on timeout / no provider / no permission. */
    fun current(ctx: Context, timeoutMs: Long = 20_000, cb: (Location?) -> Unit) {
        if (!hasPermission(ctx)) return cb(null)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val main = Handler(Looper.getMainLooper())
        var done = false
        fun finish(loc: Location?) {
            if (done) return
            done = true
            main.post { cb(loc) }
        }
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        // A very recent cached fix is good enough and instant.
        providers.mapNotNull { runCatching { @Suppress("MissingPermission") lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { if (System.currentTimeMillis() - it.time < 30_000) return finish(it) }
        if (providers.isEmpty()) return finish(null)
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val signal = CancellationSignal()
                val exec = Executors.newSingleThreadExecutor()
                val provider = if (LocationManager.GPS_PROVIDER in providers) LocationManager.GPS_PROVIDER else providers.first()
                @Suppress("MissingPermission")
                lm.getCurrentLocation(provider, signal, exec) { finish(it) }
                main.postDelayed({ if (!done) { signal.cancel(); finish(null) } }, timeoutMs)
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        @Suppress("DEPRECATION") lm.removeUpdates(this)
                        finish(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                    override fun onProviderDisabled(p: String) {}
                    override fun onProviderEnabled(p: String) {}
                }
                val provider = if (LocationManager.GPS_PROVIDER in providers) LocationManager.GPS_PROVIDER else providers.first()
                @Suppress("MissingPermission", "DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                main.postDelayed({ if (!done) { @Suppress("DEPRECATION") lm.removeUpdates(listener); finish(null) } }, timeoutMs)
            }
        } catch (_: Exception) {
            finish(null)
        }
    }

    /** A Discord message with the coordinates, accuracy and a Google Maps link. */
    fun describe(loc: Location): String {
        val lat = "%.6f".format(loc.latitude)
        val lon = "%.6f".format(loc.longitude)
        val acc = if (loc.hasAccuracy()) " · ±${loc.accuracy.toInt()}m" else ""
        val alt = if (loc.hasAltitude()) " · ${loc.altitude.toInt()}m cao" else ""
        return "📍 Vị trí: `$lat, $lon`$acc$alt\nhttps://maps.google.com/?q=$lat,$lon"
    }
}
