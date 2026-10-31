package io.github.yixing233.hyperduo;

import android.graphics.Bitmap;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reject only demonstrably blank decoded rasters. Never recolors or edits pixels.
 * Opaque white background + actual visible artwork is NOT rejected.
 * Alpha white silhouettes are NOT rejected. An all-transparent image IS rejected.
 */
public final class AirPodsBitmapGuard {
    private AirPodsBitmapGuard() {}
    // Cache quality by APK-validated file identity, not by model or popup session.
    // Bounded to exceed the currently packaged 278 PNGs without retaining Bitmap memory.
    private static final Map<String,Boolean> QUALITY=new LinkedHashMap<String,Boolean>(512,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Boolean> eldest) { return size()>512; }
    };
    /** Prior decision for a CRC-validated file, or null if this raster has not been read. */
    static Boolean cached(File file) {
        String identity = AirPodsAssetStore.verifiedIdentity(file);
        if (identity == null) return null;
        synchronized (QUALITY) { return QUALITY.get(identity); }
    }
    public static boolean reject(File file, Bitmap image) {
        if (image==null || image.isRecycled()) return true;
        String identity=AirPodsAssetStore.verifiedIdentity(file);
        if (identity!=null) synchronized(QUALITY) {
            Boolean known=QUALITY.get(identity); if (known!=null) return known;
        }
        boolean rejected=reject(image);
        if (identity!=null) synchronized(QUALITY) { QUALITY.put(identity,rejected); }
        return rejected;
    }
    public static boolean reject(Bitmap image) {
        if (image==null || image.isRecycled() || image.getWidth()<=0 || image.getHeight()<=0) return true;
        try {
            int width=image.getWidth(), height=image.getHeight();
            int[] row=new int[width];
            boolean allTransparent=true, allOpaqueWhite=true;
            for (int y=0; y<height; y++) {
                image.getPixels(row,0,width,0,y,width,1);
                for (int pixel:row) {
                    if ((pixel >>> 24)!=0) allTransparent=false;
                    if (pixel != 0xffffffff) allOpaqueWhite=false;
                    if (!allTransparent && !allOpaqueWhite) return false;
                }
            }
            return allTransparent || allOpaqueWhite;
        } catch (RuntimeException unreadable) {
            // Unreadable replacement is not grounds to overwrite a valid native image.
            return true;
        }
    }
}
