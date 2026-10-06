package com.faceclaw.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import androidx.core.content.ContextCompat

/**
 * One-shot, foreground-only location lookup for Weather. It prefers a fresh
 * coarse network fix, but can fall back to the newest cached provider fix if
 * Android cannot produce a new location before the timeout.
 */
class FaceclawLocationProvider(context: Context) : LocationListener {
    companion object {
        private const val CACHE_NAME = "faceclaw_weather_location"
        private const val CACHE_LATITUDE = "latitude"
        private const val CACHE_LONGITUDE = "longitude"
        private const val CACHE_TIMESTAMP = "timestamp"
        private const val CACHE_PROVIDER = "faceclaw-weather-cache"
        private const val FRESH_CACHE_MS = 10L * 60L * 1000L
        // Android's own last-known fixes are only trusted for a day.
        private const val MAX_PROVIDER_CACHE_MS = 24L * 60L * 60L * 1000L
        // With while-in-use permission, Android only produces fixes while the
        // Faceclaw screen is open. Keep the rounded weather fix for a week so a
        // glasses-only week still has local weather; callers label its age.
        const val MAX_SAVED_WEATHER_LOCATION_MS = 7L * 24L * 60L * 60L * 1000L
        private const val TIMEOUT_MS = 15L * 1000L
        private const val WEATHER_COORDINATE_SCALE = 100.0

        fun isUsableFallback(location: Location?): Boolean {
            if (location == null) return false
            return isRecent(location, if (CACHE_PROVIDER == location.provider)
                MAX_SAVED_WEATHER_LOCATION_MS else MAX_PROVIDER_CACHE_MS)
        }

        private fun isRecent(location: Location?, maximumAgeMs: Long): Boolean {
            if (location == null || location.time <= 0L) return false
            val ageMs = System.currentTimeMillis() - location.time
            return ageMs in 0L..maximumAgeMs
        }

        private fun isValidLocation(location: Location): Boolean {
            val latitude = location.latitude
            val longitude = location.longitude
            return latitude.isFinite() && latitude >= -90.0 && latitude <= 90.0 &&
                longitude.isFinite() && longitude >= -180.0 && longitude <= 180.0
        }

        private fun roundWeatherCoordinate(value: Double): Double =
            Math.round(value * WEATHER_COORDINATE_SCALE) / WEATHER_COORDINATE_SCALE
    }

    private val context: Context = context.applicationContext
    private val locationManager: LocationManager? =
        this.context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { onTimeout() }

    private var listener: FaceclawLocationListener? = null
    private var cachedLocation: Location? = null
    private var running = false

    fun setListener(listener: FaceclawLocationListener?) {
        this.listener = listener
    }

    fun start() {
        mainHandler.post { startOnMainThread() }
    }

    fun cancel() {
        mainHandler.post { finish(null, null) }
    }

    private fun startOnMainThread() {
        if (running) {
            return
        }
        val manager = locationManager
        if (manager == null) {
            deliverError("Android location service is unavailable.")
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) {
            deliverError("Location permission is required.")
            return
        }

        cachedLocation = newestCachedLocation()
        if (isRecent(cachedLocation, FRESH_CACHE_MS)) {
            deliverLocation(cachedLocation)
            return
        }

        val provider = chooseProvider()
        if (provider == null) {
            if (isUsableFallback(cachedLocation)) {
                deliverLocation(cachedLocation)
            } else {
                deliverError("Turn on Location on your phone, then retry.")
            }
            return
        }

        try {
            running = true
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, this, Looper.getMainLooper())
            mainHandler.postDelayed(timeout, TIMEOUT_MS)
        } catch (error: SecurityException) {
            finish(null, "Location permission is required.")
        } catch (error: Throwable) {
            finish(null, "Unable to request the current location.")
        }
    }

    private fun chooseProvider(): String? {
        try {
            if (locationManager!!.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER
            }
            val criteria = Criteria()
            criteria.accuracy = Criteria.ACCURACY_COARSE
            criteria.powerRequirement = Criteria.POWER_LOW
            return locationManager.getBestProvider(criteria, true)
        } catch (ignored: Throwable) {
            return null
        }
    }

    private fun newestCachedLocation(): Location? {
        var newest: Location? = savedWeatherLocation()
        try {
            val providers = locationManager!!.getProviders(true)
            for (provider in providers) {
                val candidate = locationManager.getLastKnownLocation(provider)
                if (candidate != null && isRecent(candidate, MAX_PROVIDER_CACHE_MS) &&
                    (newest == null || candidate.time > newest.time)) {
                    newest = candidate
                }
            }
        } catch (ignored: SecurityException) {
            // The explicit permission check above owns the user-facing error.
        } catch (ignored: Throwable) {
            // A fresh request may still succeed even if cached providers fail.
        }
        return newest
    }

    private fun onTimeout() {
        if (isUsableFallback(cachedLocation)) {
            finish(cachedLocation, null)
        } else {
            finish(null, "Couldn't get your location. Open Faceclaw on your phone to update it.")
        }
    }

    override fun onLocationChanged(location: Location) {
        finish(location, null)
    }

    override fun onProviderDisabled(provider: String) {
        // Keep waiting: Android may still deliver a queued fix or the timeout
        // can fall back to another provider's cached location.
    }

    override fun onProviderEnabled(provider: String) {
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    private fun finish(location: Location?, error: String?) {
        if (running) {
            try {
                locationManager!!.removeUpdates(this)
            } catch (ignored: Throwable) {
            }
        }
        running = false
        mainHandler.removeCallbacks(timeout)
        if (location != null) {
            deliverLocation(location)
        } else if (error != null) {
            deliverError(error)
        }
    }

    private fun deliverLocation(location: Location?) {
        val current = listener
        if (current != null && location != null) {
            saveWeatherLocation(location)
            current.onLocation(
                location.latitude,
                location.longitude,
                if (location.hasAccuracy()) location.accuracy else -1f,
                location.time)
        }
    }

    /**
     * Android hides provider caches and suppresses new fixes when the app only
     * has while-in-use permission and its Activity is in the background. Keep
     * the last successful weather fix in the app sandbox so a glasses dashboard
     * can refresh without requesting permanent background-location access.
     * Two decimal places are ample for an NWS forecast grid and avoid retaining
     * the precise coordinate delivered for navigation or another foreground use.
     */
    private fun saveWeatherLocation(location: Location) {
        if (CACHE_PROVIDER == location.provider || !isValidLocation(location)) {
            return
        }
        val latitude = roundWeatherCoordinate(location.latitude)
        val longitude = roundWeatherCoordinate(location.longitude)
        context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE).edit()
            .putLong(CACHE_LATITUDE, java.lang.Double.doubleToRawLongBits(latitude))
            .putLong(CACHE_LONGITUDE, java.lang.Double.doubleToRawLongBits(longitude))
            .putLong(CACHE_TIMESTAMP, location.time)
            .apply()
    }

    private fun savedWeatherLocation(): Location? {
        val preferences = context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
        if (!preferences.contains(CACHE_LATITUDE) ||
            !preferences.contains(CACHE_LONGITUDE) ||
            !preferences.contains(CACHE_TIMESTAMP)) {
            return null
        }
        val location = Location(CACHE_PROVIDER)
        location.latitude = java.lang.Double.longBitsToDouble(preferences.getLong(CACHE_LATITUDE, 0L))
        location.longitude = java.lang.Double.longBitsToDouble(preferences.getLong(CACHE_LONGITUDE, 0L))
        location.time = preferences.getLong(CACHE_TIMESTAMP, 0L)
        if (!isValidLocation(location) || !isRecent(location, MAX_SAVED_WEATHER_LOCATION_MS)) {
            preferences.edit().clear().apply()
            return null
        }
        return location
    }

    private fun deliverError(message: String) {
        val current = listener
        current?.onError(message)
    }
}
