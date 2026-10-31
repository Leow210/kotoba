package android.os;

/** SDK_INT is 0 on the Mac, so Android-version-gated code (thermal status) is skipped. */
public class Build {
    public static class VERSION { public static final int SDK_INT=0; }
}
