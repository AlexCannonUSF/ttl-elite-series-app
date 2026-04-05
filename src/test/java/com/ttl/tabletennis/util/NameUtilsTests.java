package com.ttl.tabletennis.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NameUtilsTests {

    @Test
    void normalizeForLookupRemovesDiacriticsAndPunctuation() {
        assertEquals("robert lewandowski", NameUtils.normalizeForLookup("Róbért-Lewandowski!!!"));
    }

    @Test
    void normalizeForLookupHandlesLastNameFirstFormat() {
        assertEquals("adrian fabis", NameUtils.normalizeForLookup("Fabis, Adrian"));
    }

    @Test
    void splitFirstLastHandlesLastNameFirstFormat() {
        String[] split = NameUtils.splitFirstLast("Poloszczanski, Dawid");
        assertEquals("Dawid", split[0]);
        assertEquals("Poloszczanski", split[1]);
    }
}
