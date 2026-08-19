package com.ttl.tabletennis.util;

import org.slf4j.MDC;

import java.util.UUID;

public final class CorrelationContext {

    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    private static final Scope NOOP_SCOPE = () -> { };

    private CorrelationContext() {
    }

    public static String current() {
        return CURRENT.get();
    }

    public static String currentOrCreate() {
        String current = CURRENT.get();
        if (hasText(current)) {
            return current;
        }
        String generated = generate();
        set(generated);
        return generated;
    }

    public static Scope open(String requestedId) {
        String previous = CURRENT.get();
        String next = hasText(requestedId) ? requestedId.trim() : generate();
        set(next);
        return () -> restore(previous);
    }

    public static Scope openIfAbsent(String requestedId) {
        if (hasText(CURRENT.get())) {
            return NOOP_SCOPE;
        }
        return open(requestedId);
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }

    private static void restore(String previous) {
        if (hasText(previous)) {
            set(previous);
            return;
        }
        clear();
    }

    private static void set(String value) {
        CURRENT.set(value);
        MDC.put(MDC_KEY, value);
    }

    private static String generate() {
        return UUID.randomUUID().toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
