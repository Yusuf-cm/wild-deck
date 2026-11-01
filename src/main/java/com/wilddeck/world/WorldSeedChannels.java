package com.wilddeck.world;

/** Independent, stable random channels. Never derive terrain from sampling order. */
public final class WorldSeedChannels {
    private WorldSeedChannels() {}
    public static long derive(long worldSeed, long channel) {
        long value = worldSeed ^ (channel * 0x9E3779B97F4A7C15L);
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
