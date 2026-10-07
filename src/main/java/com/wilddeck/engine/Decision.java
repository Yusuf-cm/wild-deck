package com.wilddeck.engine;

public record Decision(boolean allowed, String phase, String reason) {
    public static Decision allow() { return new Decision(true, "SUCCESS", ""); }
    public static Decision reject(String phase, String reason) { return new Decision(false, phase, reason); }
}
