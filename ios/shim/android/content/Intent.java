package android.content;

/** Desktop stand-in for Intent: only the battery-status broadcast Ocr reads, which a Mac never sends. */
public class Intent {
    public static final String ACTION_BATTERY_CHANGED="android.intent.action.BATTERY_CHANGED";
    public int getIntExtra(String name,int fallback){return fallback;}
}
