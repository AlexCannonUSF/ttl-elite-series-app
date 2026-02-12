package com.ttl.tabletennis.Utility;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

public class NameUtils {

    private static final Pattern NON_LETTER_APOS_HYPHEN_SPACE =
            Pattern.compile("[^\\p{L}\\s\\-']+");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    /**
     * Your code calls NameUtils.areNamesSimilar(String, String)
     */
    public static boolean areNamesSimilar(String a, String b) {
        if (a == null || b == null) return false;
        String na = simplify(a, true);
        String nb = simplify(b, true);
        return na.equals(nb) || na.startsWith(nb) || nb.startsWith(na);
    }

    /**
     * Overload used by the scraper: basic cleaning.
     * - trims
     * - collapses spaces
     * - removes non-letters except apostrophes/hyphens
     * - strips diacritics
     */
    public static String cleanRawName(String raw) {
        return cleanRawName(raw, true);
    }

    /**
     * Overload with control over diacritic stripping.
     */
    public static String cleanRawName(String raw, boolean stripDiacritics) {
        return cleanRawName(raw, stripDiacritics, true);
    }

    /**
     * Most flexible overload. If keepAposHyphen is false, apostrophes/hyphens are removed too.
     */
    public static String cleanRawName(String raw, boolean stripDiacritics, boolean keepAposHyphen) {
        if (raw == null) return "";
        String s = raw.trim();
        if (stripDiacritics) s = stripDiacritics(s);
        if (keepAposHyphen) {
            s = NON_LETTER_APOS_HYPHEN_SPACE.matcher(s).replaceAll(" ");
        } else {
            // remove everything non-letter
            s = s.replaceAll("[^\\p{L}\\s]+", " ");
        }
        s = MULTISPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * Split a full name into first and last name (very simple heuristic).
     * Returns a String[2] = {firstName, lastName}. Missing parts become "".
     */
    public static String[] splitFirstLast(String fullName) {
        return splitFirstLast(fullName, "\\s+");
    }

    /**
     * Same as above but allows a custom delimiter regex.
     */
    public static String[] splitFirstLast(String fullName, String delimiterRegex) {
        if (fullName == null || fullName.isBlank()) return new String[] {"", ""};
        String cleaned = cleanRawName(fullName);
        String[] parts = cleaned.split(Objects.requireNonNullElse(delimiterRegex, "\\s+"));
        if (parts.length == 0) return new String[] {"", ""};
        if (parts.length == 1) return new String[] {parts[0], ""};

        String first = parts[0];
        StringBuilder last = new StringBuilder();
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) continue;
            if (last.length() > 0) last.append(' ');
            last.append(parts[i]);
        }
        return new String[] { first, last.toString() };
    }

    // -------------------- helpers --------------------

    private static String stripDiacritics(String input) {
        String norm = Normalizer.normalize(input, Normalizer.Form.NFD);
        return norm.replaceAll("\\p{M}+", "");
    }

    private static String simplify(String s, boolean stripDiacritics) {
        String t = s == null ? "" : s;
        t = stripDiacritics ? stripDiacritics(t) : t;
        t = t.toLowerCase();
        t = t.replaceAll("[^\\p{L}]+", "");
        return t;
    }
}