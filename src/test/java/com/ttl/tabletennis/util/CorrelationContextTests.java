package com.ttl.tabletennis.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorrelationContextTests {

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void currentOrCreateReusesSameThreadScopedValue() {
        String first = CorrelationContext.currentOrCreate();
        String second = CorrelationContext.currentOrCreate();

        assertNotNull(first);
        assertEquals(first, second);
        assertTrue(first.length() >= 32);
    }

    @Test
    void openRestoresPreviousValueAfterClose() {
        try (CorrelationContext.Scope ignored = CorrelationContext.open("outer-correlation")) {
            assertEquals("outer-correlation", CorrelationContext.current());

            try (CorrelationContext.Scope nested = CorrelationContext.open("inner-correlation")) {
                assertEquals("inner-correlation", CorrelationContext.current());
            }

            assertEquals("outer-correlation", CorrelationContext.current());
        }

        String newValue = CorrelationContext.currentOrCreate();
        assertNotSame("outer-correlation", newValue);
    }
}
