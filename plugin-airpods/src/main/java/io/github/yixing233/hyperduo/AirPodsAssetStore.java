package io.github.yixing233.hyperduo;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Rebuildable APK-backed cache; no stamp can authorize a missing/partial image. */
public final class AirPodsAssetStore {
    private static ZipFile zip;
    private static String apkIdentity;
    private static final Map<String,String> verified = new HashMap<>();
    private AirPodsAssetStore() {}

    public static synchronized File materialize(File apk, File root, String relative) throws IOException {
        if (!relative.matches("[0-9A-F]{4}/(?:frames/[0-9]{4}|hs_ok|hs_disc_l|hs_disc_r|box|still)\\.png"))
            throw new IOException("Unrecognized asset path");
        String identity = apk.getCanonicalPath()+":"+apk.length()+":"+apk.lastModified();
        if (!identity.equals(apkIdentity)) {
            if (zip != null) zip.close();
            zip = null; verified.clear(); apkIdentity = null;
            zip = new ZipFile(apk); apkIdentity = identity;
        }
        ZipEntry entry = zip.getEntry("assets/test/"+relative);
        if (entry == null || entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > 16*1024*1024)
            throw new IOException("Asset missing from APK");
        File canonicalRoot = root.getCanonicalFile();
        File file = new File(canonicalRoot, relative);
        if (!file.getCanonicalPath().startsWith(canonicalRoot.getPath()+File.separator))
            throw new IOException("Cache path escaped root");
        String key = file.getCanonicalPath();
        String mark = file.length()+":"+file.lastModified()+":"+entry.getCrc();
        if (file.isFile() && file.length()==entry.getSize() && mark.equals(verified.get(key))) return file;
        if (file.isFile() && file.length()==entry.getSize() && crc(file)==entry.getCrc()) {
            verified.put(key,mark); return file;
        }
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create asset directory");
        Path temporary = Files.createTempFile(parent.toPath(), ".asset-", ".tmp");
        try {
            CRC32 crc = new CRC32(); long total=0;
            try (InputStream in=zip.getInputStream(entry); OutputStream out=Files.newOutputStream(temporary)) {
                byte[] bytes=new byte[8192]; int count;
                while ((count=in.read(bytes))!=-1) {total+=count;if(total>entry.getSize())throw new IOException("Oversized asset");out.write(bytes,0,count);crc.update(bytes,0,count);}
            }
            if(total!=entry.getSize() || crc.getValue()!=entry.getCrc())throw new IOException("Incomplete asset");
            try {Files.move(temporary,file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            verified.put(key,file.length()+":"+file.lastModified()+":"+entry.getCrc());
            return file;
        } finally {Files.deleteIfExists(temporary);}
    }
    /** Read-only identity of a materialized and CRC-validated asset, null if no longer current. */
    public static synchronized String verifiedIdentity(File file) {
        if (file==null || apkIdentity==null) return null;
        try {
            String key=file.getCanonicalPath(); String mark=verified.get(key);
            String current=file.length()+":"+file.lastModified()+":";
            return file.isFile() && mark!=null && mark.startsWith(current)
                ? apkIdentity+"|"+key+"|"+mark : null;
        } catch(IOException ignored) { return null; }
    }
    private static long crc(File file) throws IOException {
        CRC32 crc=new CRC32();byte[] b=new byte[8192];int n;
        try(InputStream in=new FileInputStream(file)){while((n=in.read(b))!=-1)crc.update(b,0,n);}
        return crc.getValue();
    }
}
