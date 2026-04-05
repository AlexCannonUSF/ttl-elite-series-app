package com.ttl.tabletennis.util;

import java.text.Normalizer;
import java.util.Objects;
import java.util.regex.Pattern;

public final class NameUtils {

    private static final Pattern NON_LETTER_APOS_HYPHEN_SPACE =
            Pattern.compile("[^\\p{L}\\s\\-']+");
    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    private NameUtils() {
    }

    public static boolean areNamesSimilar(String a, String b) {
        if (a == null || b == null) return false;
        String na = simplify(a, true);
        String nb = simplify(b, true);
        return na.equals(nb) || na.startsWith(nb) || nb.startsWith(na);
    }

    public static String cleanRawName(String raw) {
        return cleanRawName(raw, true);
    }

    public static String cleanRawName(String raw, boolean stripDiacritics) {
        return cleanRawName(raw, stripDiacritics, true);
    }

    public static String cleanRawName(String raw, boolean stripDiacritics, boolean keepAposHyphen) {
        if (raw == null) return "";
        String s = raw.trim();
        if (stripDiacritics) s = stripDiacritics(s);
        if (keepAposHyphen) {
            s = NON_LETTER_APOS_HYPHEN_SPACE.matcher(s).replaceAll(" ");
        } else {
            s = s.replaceAll("[^\\p{L}\\s]+", " ");
        }
        return MULTISPACE.matcher(s).replaceAll(" ").trim();
    }

    public static String[] splitFirstLast(String fullName) {
        return splitFirstLast(fullName, "\\s+");
    }

    public static String[] splitFirstLast(String fullName, String delimiterRegex) {
        if (fullName == null || fullName.isBlank()) return new String[]{"", ""};
        String trimmed = fullName.trim();
        if (trimmed.contains(",")) {
            String[] commaParts = trimmed.split(",", 2);
            String last = cleanRawName(commaParts[0]);
            String first = commaParts.length > 1 ? cleanRawName(commaParts[1]) : "";
            if (!first.isBlank() || !last.isBlank()) {
                return new String[]{first, last};
            }
        }

        String cleaned = cleanRawName(trimmed);
        String[] parts = cleaned.split(Objects.requireNonNullElse(delimiterRegex, "\\s+"));
        if (parts.length == 0) return new String[]{"", ""};
        if (parts.length == 1) return new String[]{"", parts[0]};

        String last = parts[parts.length - 1];
        StringBuilder first = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isBlank()) continue;
            if (first.length() > 0) first.append(' ');
            first.append(parts[i]);
        }
        return new String[]{first.toString(), last};
    }

    public static String normalizeForLookup(String raw) {
        String[] split = splitFirstLast(raw);
        String first = normalizeToken(split[0]);
        String last = normalizeToken(split[1]);
        String combined = (first + " " + last).trim();
        if (combined.isBlank()) {
            combined = normalizeToken(raw);
        }
        return MULTISPACE.matcher(combined).replaceAll(" ").trim();
    }

    private static String normalizeToken(String raw) {
        if (raw == null) return "";
        String stripped = stripDiacritics(raw).toLowerCase().replace("'", "");
        String lettersAndSpaceOnly = stripped.replaceAll("[^\\p{L}\\s]+", " ");
        return MULTISPACE.matcher(lettersAndSpaceOnly).replaceAll(" ").trim();
    }

    private static String stripDiacritics(String input) {
        String norm = Normalizer.normalize(input, Normalizer.Form.NFD);
        return norm.replaceAll("\\p{M}+", "");
    }

    private static String simplify(String s, boolean stripDiacritics) {
        String t = s == null ? "" : s;
        t = stripDiacritics ? stripDiacritics(t) : t;
        t = t.toLowerCase();
        return t.replaceAll("[^\\p{L}]+", "");
    }
}
