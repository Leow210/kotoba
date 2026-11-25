package app.kotoba.ios;

/**
 * An Objective-C autorelease pool around a unit of Java work. J2ObjC's runtime leaves temporary objects in the thread's
 * autorelease pool, which only drains when the thread's task ends, so a long import grows by gigabytes and iOS kills the
 * app. The shared loops are wrapped in push/pop by ios/patch-sources.py when the core is translated.
 */
public final class Pool {
    private Pool() {}

    /*-[
    extern void *objc_autoreleasePoolPush(void);
    extern void objc_autoreleasePoolPop(void *pool);
    ]-*/

    public static native long push() /*-[ return (jlong)objc_autoreleasePoolPush(); ]-*/;
    public static native void pop(long pool) /*-[ objc_autoreleasePoolPop((void *)pool); ]-*/;

    /**
     * Writes a whole file. J2ObjC's java.nio.file.Files.write goes through Files.newByteChannel, which isn't implemented
     * there and throws a NullPointerException; a plain stream works. patch-sources.py points the shared classes'
     * Files.write calls here. (It lives in this class because the Xcode project lists its translated files one by one.)
     */
    public static java.nio.file.Path writeFile(java.nio.file.Path path, byte[] data) throws java.io.IOException {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(path.toFile())) { out.write(data); }
        return path;
    }
}
