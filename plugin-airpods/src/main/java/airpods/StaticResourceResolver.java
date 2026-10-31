package airpods;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Static PNG/WebP only. Missing files and every video extension return empty,
 * which means the caller keeps Xiaomi's own drawable.
 *
 * Popup static slots wired here: hs_ok, hs_disc_l, hs_disc_r, box (under {@code assets/test/<hex>/}).
 * {@code still.png} is not resolved here; {@code AirPodsNativeHooks.showTakenOutStill} loads it directly.
 * Settings full-headset image uses AirPodsSettingsHooks, not this popup slot resolver.
 */
public final class StaticResourceResolver {
    public enum Slot {
        /** Success-state paired buds; packaged 2420 asset is opaque white and must be rejected at decode. */
        HS_OK("hs_ok"),
        HS_DISC_L("hs_disc_l"),
        HS_DISC_R("hs_disc_r"),
        BOX("box");

        final String fileStem;

        Slot(String fileStem) {
            this.fileStem = fileStem;
        }

        static Slot fromXiaomiName(String requestedFileName) {
            String name = requestedFileName.toLowerCase(Locale.ROOT);
            if (name.endsWith("_hs_ok.png") || name.endsWith("_3000.webp") || name.endsWith("_3000.png")) {
                return HS_OK;
            }
            if (name.endsWith("_hs_disc_l.png")) {
                return HS_DISC_L;
            }
            if (name.endsWith("_hs_disc_r.png")) {
                return HS_DISC_R;
            }
            if (name.endsWith("_box.png")) {
                return BOX;
            }
            return null;
        }
    }

    private StaticResourceResolver() {}

    public static Optional<Path> resolve(String broadcastHex, Slot slot, Path assetRoot) {
        if (slot == null || assetRoot == null || ModelRegistry.find(broadcastHex).isEmpty()) {
            return Optional.empty();
        }
        Path dir = assetRoot.resolve(broadcastHex.toUpperCase());
        for (String ext : new String[] {".png", ".webp"}) {
            Path candidate = dir.resolve(slot.fileStem + ext);
            if (isStaticFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Hook point: MiuiFastConnectResource.getDrawableFromFile(deviceId, fileName).
     * Returning empty leaves the original drawable in place.
     */
    public static Optional<Path> override(String broadcastHex, String requestedFileName, Path assetRoot) {
        if (requestedFileName == null || isMotion(requestedFileName)) {
            return Optional.empty();
        }
        Slot slot = Slot.fromXiaomiName(requestedFileName);
        return resolve(broadcastHex, slot, assetRoot);
    }

    static boolean isMotion(String requestedFileName) {
        String name = requestedFileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".mp4")
                || name.endsWith(".mov")
                || name.endsWith(".webm")
                || name.contains("earphone");
    }

    private static boolean isStaticFile(Path candidate) {
        try {
            if (!Files.isRegularFile(candidate)) {
                return false;
            }
            String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".png") || name.endsWith(".webp");
        } catch (SecurityException ex) {
            return false;
        }
    }

    static void writePlaceholder(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
    }
}
