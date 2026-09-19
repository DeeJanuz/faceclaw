package com.faceclaw.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.util.List;

/**
 * One-shot, foreground-only location lookup for Weather. It prefers a fresh
 * coarse network fix, but can fall back to the newest cached provider fix if
 * Android cannot produce a new location before the timeout.
 */
public final class FaceclawLocationProvider implements LocationListener {
    private static final String CACHE_NAME = "faceclaw_weather_location";
    private static final String CACHE_LATITUDE = "latitude";
    private static final String CACHE_LONGITUDE = "longitude";
    private static final String CACHE_TIMESTAMP = "timestamp";
    private static final String CACHE_PROVIDER = "faceclaw-weather-cache";
    private static final long FRESH_CACHE_MS = 10L * 60L * 1000L;
    private static final long MAX_CACHE_MS = 24L * 60L * 60L * 1000L;
    private static final long TIMEOUT_MS = 15L * 1000L;
    private static final double WEATHER_COORDINATE_SCALE = 100.0;

    private final Context context;
    private final LocationManager locationManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable timeout = this::onTimeout;

    private FaceclawLocationListener listener;
    private Location cachedLocation;
    private boolean running;

    public FaceclawLocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setListener(FaceclawLocationListener listener) {
        this.listener = listener;
    }

    public void start() {
        mainHandler.post(this::startOnMainThread);
    }

    public void cancel() {
        mainHandler.post(() -> finish(null, null));
    }

    private void startOnMainThread() {
        if (running) {
            return;
        }
        if (locationManager == null) {
            deliverError("Android location service is unavailable.");
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            deliverError("Location permission is required.");
            return;
        }

        cachedLocation = newestCachedLocation();
        if (isRecent(cachedLocation, FRESH_CACHE_MS)) {
            deliverLocation(cachedLocation);
            return;
        }

        String provider = chooseProvider();
        if (provider == null) {
            if (isRecent(cachedLocation, MAX_CACHE_MS)) {
                deliverLocation(cachedLocation);
            } else {
                deliverError("Turn on Location on your phone, then retry.");
            }
            return;
        }

        try {
            running = true;
            locationManager.requestSingleUpdate(provider, this, Looper.getMainLooper());
            mainHandler.postDelayed(timeout, TIMEOUT_MS);
        } catch (SecurityException error) {
            finish(null, "Location permission is required.");
        } catch (Throwable error) {
            finish(null, "Unable to request the current location.");
        }
    }

    private String chooseProvider() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                return LocationManager.NETWORK_PROVIDER;
            }
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            criteria.setPowerRequirement(Criteria.POWER_LOW);
            return locationManager.getBestProvider(criteria, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Location newestCachedLocation() {
        Location newest = savedWeatherLocation();
        try {
            List<String> providers = locationManager.getProviders(true);
            for (String provider : providers) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (newest == null || candidate.getTime() > newest.getTime())) {
                    newest = candidate;
                }
            }
        } catch (SecurityException ignored) {
            // The explicit permission check above owns the user-facing error.
        } catch (Throwable ignored) {
            // A fresh request may still succeed even if cached providers fail.
        }
        return newest;
    }

    private static boolean isRecent(Location location, long maximumAgeMs) {
        if (location == null || location.getTime() <= 0L) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - location.getTime();
        return ageMs >= 0L && ageMs <= maximumAgeMs;
    }

    private void onTimeout() {
        if (isRecent(cachedLocation, MAX_CACHE_MS)) {
            finish(cachedLocation, null);
        } else {
            finish(null, "Couldn't get your current location. Tap to retry.");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        finish(location, null);
    }

    @Override
    public void onProviderDisabled(String provider) {
        // Keep waiting: Android may still deliver a queued fix or the timeout
        // can fall back to another provider's cached location.
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private void finish(Location location, String error) {
        if (running) {
            try {
                locationManager.removeUpdates(this);
            } catch (Throwable ignored) {
            }
        }
        running = false;
        mainHandler.removeCallbacks(timeout);
        if (location != null) {
            deliverLocation(location);
        } else if (error != null) {
            deliverError(error);
        }
    }

    private void deliverLocation(Location location) {
        FaceclawLocationListener current = listener;
        if (current != null && location != null) {
            saveWeatherLocation(location);
            current.onLocation(
                    location.getLatitude(),
                    location.getLongitude(),
                    location.hasAccuracy() ? location.getAccuracy() : -1f,
                    location.getTime());
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
    private void saveWeatherLocation(Location location) {
        if (CACHE_PROVIDER.equals(location.getProvider()) || !isValidLocation(location)) {
            return;
        }
        double latitude = roundWeatherCoordinate(location.getLatitude());
        double longitude = roundWeatherCoordinate(location.getLongitude());
        context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE).edit()
                .putLong(CACHE_LATITUDE, Double.doubleToRawLongBits(latitude))
                .putLong(CACHE_LONGITUDE, Double.doubleToRawLongBits(longitude))
                .putLong(CACHE_TIMESTAMP, location.getTime())
                .apply();
    }

    private Location savedWeatherLocation() {
        android.content.SharedPreferences preferences =
                context.getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE);
        if (!preferences.contains(CACHE_LATITUDE)
                || !preferences.contains(CACHE_LONGITUDE)
                || !preferences.contains(CACHE_TIMESTAMP)) {
            return null;
        }
        Location location = new Location(CACHE_PROVIDER);
        location.setLatitude(Double.longBitsToDouble(preferences.getLong(CACHE_LATITUDE, 0L)));
        location.setLongitude(Double.longBitsToDouble(preferences.getLong(CACHE_LONGITUDE, 0L)));
        location.setTime(preferences.getLong(CACHE_TIMESTAMP, 0L));
        if (!isValidLocation(location) || !isRecent(location, MAX_CACHE_MS)) {
            preferences.edit().clear().apply();
            return null;
        }
        return location;
    }

    private static boolean isValidLocation(Location location) {
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        return Double.isFinite(latitude) && latitude >= -90.0 && latitude <= 90.0
                && Double.isFinite(longitude) && longitude >= -180.0 && longitude <= 180.0;
    }

    private static double roundWeatherCoordinate(double value) {
        return Math.round(value * WEATHER_COORDINATE_SCALE) / WEATHER_COORDINATE_SCALE;
    }

    private void deliverError(String message) {
        FaceclawLocationListener current = listener;
        if (current != null) {
            current.onError(message);
        }
    }
}
