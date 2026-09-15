package com.faceclaw.app;

import android.app.Activity;
import android.companion.AssociationRequest;
import android.companion.BluetoothDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.Context;
import android.content.IntentSender;
import android.os.Build;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.regex.Pattern;

/**
 * User-consented Android companion association for the glasses.
 *
 * This association is separate from Faceclaw's existing BLE bond and never
 * grants notification access by itself. Android owns the device chooser and
 * the resulting association; this helper only stores the association identity
 * so the phone UI can report whether it is still present.
 */
public final class FaceclawCompanionTrust {
    private static final String TAG = "FaceclawCompanion";
    private static final String PREFS = "faceclaw_companion_trust";
    private static final String ASSOCIATION_ID = "association_id";
    private static final String ASSOCIATION_ADDRESS = "association_address";
    private static final int REQUEST_CODE = 0x46c1;
    private static WeakReference<Activity> pendingActivity;

    private FaceclawCompanionTrust() {}

    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
    }

    /** Whether the saved association still exists in Android's registry. */
    public static boolean isAssociated(Context context) {
        if (!isSupported() || context == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(ASSOCIATION_ID, -1);
                if (id < 0) return false;
                for (android.companion.AssociationInfo info : manager(context).getMyAssociations()) {
                    if (info != null && info.getId() == id) return true;
                }
                return false;
            }
            String address = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ASSOCIATION_ADDRESS, "");
            if (address == null || address.isEmpty()) return false;
            for (String current : manager(context).getAssociations()) {
                if (address.equalsIgnoreCase(current)) return true;
            }
            return false;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to inspect companion association", error);
            return false;
        }
    }

    /** Start Android's consent chooser. The user must approve the association. */
    public static boolean requestAssociation(Activity activity, String address) {
        if (!isSupported() || activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            BluetoothDeviceFilter.Builder filter = new BluetoothDeviceFilter.Builder();
            if (address != null && !address.trim().isEmpty()) {
                filter.setAddress(address.trim());
            } else {
                filter.setNamePattern(Pattern.compile("(?i)even.*"));
            }
            AssociationRequest request = new AssociationRequest.Builder()
                    .addDeviceFilter(filter.build())
                    .setSingleDevice(true)
                    .build();
            pendingActivity = new WeakReference<>(activity);
            CompanionDeviceManager deviceManager = manager(activity);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                deviceManager.associate(request, activity.getMainExecutor(), callback31(activity));
            } else {
                deviceManager.associate(request, callback26(activity), null);
            }
            return true;
        } catch (RuntimeException error) {
            pendingActivity = null;
            Log.w(TAG, "Unable to start companion association", error);
            return false;
        }
    }

    /** Remove only Faceclaw's saved association, after an explicit user action. */
    public static boolean disassociate(Context context) {
        if (!isSupported() || context == null) return false;
        try {
            CompanionDeviceManager deviceManager = manager(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(ASSOCIATION_ID, -1);
                if (id < 0) return false;
                deviceManager.disassociate(id);
            } else {
                String address = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ASSOCIATION_ADDRESS, "");
                if (address == null || address.isEmpty()) return false;
                deviceManager.disassociate(address);
            }
            clear(context);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to remove companion association", error);
            return false;
        }
    }

    private static CompanionDeviceManager manager(Context context) {
        return (CompanionDeviceManager) context.getApplicationContext()
                .getSystemService(Context.COMPANION_DEVICE_SERVICE);
    }

    private static CompanionDeviceManager.Callback callback31(Activity activity) {
        return new CompanionDeviceManager.Callback() {
            @Override public void onAssociationPending(IntentSender sender) {
                launchChooser(activity, sender);
            }

            @Override public void onAssociationCreated(android.companion.AssociationInfo info) {
                if (info == null) return;
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putInt(ASSOCIATION_ID, info.getId())
                        .remove(ASSOCIATION_ADDRESS)
                        .apply();
                pendingActivity = null;
            }

            @Override public void onFailure(CharSequence message) {
                pendingActivity = null;
                Log.w(TAG, "Companion association failed: " + message);
            }
        };
    }

    @SuppressWarnings("deprecation")
    private static CompanionDeviceManager.Callback callback26(Activity activity) {
        return new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender sender) {
                launchChooser(activity, sender);
            }

            @Override public void onFailure(CharSequence message) {
                pendingActivity = null;
                Log.w(TAG, "Companion association failed: " + message);
            }
        };
    }

    private static void launchChooser(Activity activity, IntentSender sender) {
        try {
            activity.startIntentSenderForResult(sender, REQUEST_CODE, null, 0, 0, 0);
        } catch (Exception error) {
            pendingActivity = null;
            Log.w(TAG, "Unable to launch companion chooser", error);
        }
    }

    /** Legacy devices return the selected Bluetooth address through the result. */
    public static void recordLegacyResult(Context context, String address) {
        if (context == null || address == null || address.isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(ASSOCIATION_ADDRESS, address)
                .remove(ASSOCIATION_ID)
                .apply();
    }

    public static int requestCode() {
        return REQUEST_CODE;
    }

    public static void clear(Context context) {
        if (context != null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }
}
