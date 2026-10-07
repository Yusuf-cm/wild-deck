package com.wilddeck.app;

public record ActionExecutionResult(boolean success, String message) {
    public static ActionExecutionResult ok(String message) {
        return new ActionExecutionResult(true,message);
    }

    public static ActionExecutionResult fail(String message) {
        return new ActionExecutionResult(false,message);
    }
}
