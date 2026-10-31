package airpods;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Broadcast hex is the field Xiaomi stores. Bluetooth PID, hardware id and
 * asset key are other namespaces. A row only claims a link the cited source
 * actually states.
 */
public final class ModelRegistry {
    public enum Source {
        XIAOMI_ALLOWLIST,
        APPLE_WIKI_PID,
        MACOS_ASSET_PLIST,
        IOS27_SHARING_STRING
    }

    public static final class Row {
        public final String broadcastHex;
        public final int bluetoothPid;
        public final String product;
        public final String hardwareId;
        public final String assetKey;
        public final Source broadcastSource;
        public final Source pidSource;

        Row(String broadcastHex, int bluetoothPid, String product, String hardwareId,
                String assetKey, Source broadcastSource, Source pidSource) {
            this.broadcastHex = broadcastHex;
            this.bluetoothPid = bluetoothPid;
            this.product = product;
            this.hardwareId = hardwareId;
            this.assetKey = assetKey;
            this.broadcastSource = broadcastSource;
            this.pidSource = pidSource;
        }
    }

    private static final Map<String, Row> BY_BROADCAST;

    static {
        Map<String, Row> rows = new LinkedHashMap<>();
        put(rows, "0220", 0x2002, "AirPods", "AirPods1,1", "B188", Source.APPLE_WIKI_PID);
        put(rows, "0F20", 0x200F, "AirPods", "AirPods1,2", "B288", Source.APPLE_WIKI_PID);
        put(rows, "1320", 0x2013, "AirPods", "AirPods1,3", "B688", Source.APPLE_WIKI_PID);
        put(rows, "1920", 0x2019, "AirPods", "AirPods1,4", "B768", Source.MACOS_ASSET_PLIST);
        put(rows, "1B20", 0x201B, "AirPods", "AirPods1,5", "B768", Source.APPLE_WIKI_PID);
        put(rows, "0E20", 0x200E, "AirPods Pro", "AirPodsPro1,1", "B298", Source.APPLE_WIKI_PID);
        put(rows, "1420", 0x2014, "AirPods Pro", "AirPodsPro1,2", "B698", Source.APPLE_WIKI_PID);
        put(rows, "2420", 0x2024, "AirPods Pro", "Device1,8228", "B698", Source.APPLE_WIKI_PID);
        put(rows, "2720", 0x2027, "AirPods Pro", "AirPodsPro1,3", "B788", Source.MACOS_ASSET_PLIST);
        put(rows, "0A20", 0x200A, "AirPods Max", "AirPodsMax1,1", "B515", Source.APPLE_WIKI_PID);
        put(rows, "1F20", 0x201F, "AirPods Max", "Device1,8223", "B515c", Source.APPLE_WIKI_PID);
        BY_BROADCAST = Collections.unmodifiableMap(rows);
    }

    private static void put(Map<String, Row> rows, String broadcast, int pid, String product,
            String hardwareId, String assetKey, Source pidSource) {
        rows.put(broadcast, new Row(broadcast, pid, product, hardwareId, assetKey,
                Source.XIAOMI_ALLOWLIST, pidSource));
    }

    public static Optional<Row> find(String broadcastHex) {
        if (broadcastHex == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_BROADCAST.get(broadcastHex.toUpperCase()));
    }

    public static Map<String, Row> all() {
        return BY_BROADCAST;
    }

    private ModelRegistry() {}
}
