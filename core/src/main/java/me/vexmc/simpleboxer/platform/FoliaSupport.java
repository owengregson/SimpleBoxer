package me.vexmc.simpleboxer.platform;

/**
 * Detects a Folia (region-threaded) runtime. The marker class only exists on
 * Folia and its forks; classic Paper/Spigot never ship it, so a single
 * {@code Class.forName} cleanly separates the two scheduling worlds.
 */
public final class FoliaSupport {

    private static final boolean FOLIA = detect();

    private FoliaSupport() {}

    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException notFolia) {
            return false;
        }
    }
}
