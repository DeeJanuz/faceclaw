package android.media.audiofx;

public class AutomaticGainControl extends AudioEffect {
    public static boolean isAvailable() { return false; }
    public static AutomaticGainControl create(int audioSessionId) { return null; }
}
