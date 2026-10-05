package com.example.barangay_superapp;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * Repairs broken Filipino place names such as "Para\u00C3\u00B1aque", "Las Pi\u00C3\u00B1as" or "Para?aque"
 * so they display correctly as "Para\u00F1aque", "Las Pi\u00F1as", "Dasmari\u00F1as", etc.
 *
 * These happen when UTF-8 text is decoded as ISO-8859-1 / Windows-1252 (mojibake),
 * or when the "\u00F1" arrives decomposed as "n" + combining tilde (U+0303).
 *
 * NOTE: this file uses unicode escapes only (no literal special characters), so it
 * compiles identically no matter what file encoding Android Studio / Windows uses.
 */
public final class TextFix {

    private static final char REPLACEMENT_CHAR = '\uFFFD';
    private static final char N_TILDE_LOWER = '\u00F1'; // n with tilde (lowercase)
    private static final char N_TILDE_UPPER = '\u00D1'; // N with tilde (uppercase)

    private TextFix() {}

    public static String fix(String s) {
        if (s == null) return "";
        String out = s;

        // 1) Known mojibake sequences for \u00F1 / \u00D1 (Latin-1 and Windows-1252 mis-decoding)
        out = out.replace("\u00C3\u00B1", "\u00F1")   // mojibake A-tilde + plus-minus  -> n-tilde
                 .replace("\u00C3\u2018", "\u00D1")   // mojibake A-tilde + left quote  -> N-tilde
                 .replace("\u00C3\u0091", "\u00D1");  // mojibake A-tilde + 0x91 -> N-tilde

        // 2) Generic mojibake repair: if it still looks double-encoded, re-decode as UTF-8
        if (out.indexOf('\u00C3') >= 0 || out.indexOf('\u00C2') >= 0) {
            try {
                String redecoded = new String(out.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                if (redecoded.indexOf(REPLACEMENT_CHAR) < 0) out = redecoded;
            } catch (Exception ignored) {}
        }

        // 3) Replacement character (the "?" diamond). In PH place names this is almost always \u00F1 / \u00D1.
        if (out.indexOf(REPLACEMENT_CHAR) >= 0) {
            StringBuilder sb = new StringBuilder(out.length());
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (c == REPLACEMENT_CHAR) {
                    boolean prevUpper = i > 0 && Character.isUpperCase(out.charAt(i - 1));
                    boolean nextUpperOrEnd = i + 1 >= out.length() || Character.isUpperCase(out.charAt(i + 1));
                    sb.append(prevUpper && nextUpperOrEnd ? N_TILDE_UPPER : N_TILDE_LOWER);
                } else {
                    sb.append(c);
                }
            }
            out = sb.toString();
        }

        // 4) Compose "n" + U+0303 into a single "\u00F1" so comparisons and display are consistent
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }
}
