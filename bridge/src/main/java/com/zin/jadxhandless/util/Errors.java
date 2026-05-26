package com.zin.jadxhandless.util;

import io.javalin.http.Context;
import org.slf4j.Logger;

import java.util.Map;

public final class Errors {

    private Errors() {
    }

    public static void send(Context ctx, int status, String message, Logger logger) {
        if (logger != null) logger.warn("HTTP {} {}", status, message);
        ctx.status(status).json(Map.of("error", message, "status", status));
    }

    public static void internal(Context ctx, String message, Throwable t, Logger logger) {
        if (logger != null) logger.error(message, t);
        ctx.status(500).json(Map.of("error", message, "status", 500,
                "cause", t == null ? "" : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }
}
