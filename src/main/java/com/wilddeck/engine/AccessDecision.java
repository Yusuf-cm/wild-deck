package com.wilddeck.engine;

public record AccessDecision(boolean allowed, String reason, String routeId) {
    public static AccessDecision allow(String reason) {
        return new AccessDecision(true, reason, null);
    }

    public static AccessDecision allow(String reason, String routeId) {
        return new AccessDecision(true, reason, routeId);
    }

    public static AccessDecision deny(String reason) {
        return new AccessDecision(false, reason, null);
    }
}
