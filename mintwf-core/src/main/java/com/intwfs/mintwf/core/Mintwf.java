package com.intwfs.mintwf.core;

/**
 * Entry point for the mintwf engine core.
 */
public final class Mintwf {

    private Mintwf() {
    }

    /**
     * Returns the engine version, or {@code "dev"} when not running from a packaged jar.
     */
    public static String version() {
        String version = Mintwf.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
