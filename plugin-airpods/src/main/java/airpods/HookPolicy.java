package airpods;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Decides whether one getDrawableFromFile call may be replaced.
 * PASS means the caller must leave the original result untouched,
 * including when that result is null. This class never produces a null drawable.
 */
public final class HookPolicy {
    public enum Action {
        PASS,
        REPLACE
    }

    private String address = "";
    private String broadcast = "";

    public synchronized void bind(String nextAddress, String nextBroadcast) {
        address = nextAddress == null ? "" : nextAddress;
        if (nextBroadcast != null && nextBroadcast.matches("[0-9A-Fa-f]{4}")) {
            broadcast = nextBroadcast.toUpperCase();
        } else {
            broadcast = "";
        }
    }

    public synchronized void clear() {
        address = "";
        broadcast = "";
    }

    public synchronized String broadcast() {
        return broadcast;
    }

    public synchronized String address() {
        return address;
    }

    public synchronized Action decide(String requestedFileName, Path assetRoot) {
        Optional<Path> file = StaticResourceResolver.override(broadcast, requestedFileName, assetRoot);
        return file.isPresent() ? Action.REPLACE : Action.PASS;
    }

    public synchronized Optional<Path> replacement(String requestedFileName, Path assetRoot) {
        if (decide(requestedFileName, assetRoot) != Action.REPLACE) {
            return Optional.empty();
        }
        return StaticResourceResolver.override(broadcast, requestedFileName, assetRoot);
    }
}
