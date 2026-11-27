package com.winlator.cmod.ai;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * Turns the model's markdown-ish output into something readable in a plain TextView.
 *
 * A full markdown renderer would be overkill here, but showing raw {@code **} and backticks in a
 * chat bubble looks broken. This keeps code blocks monospaced, bolds emphasised runs, and drops the
 * remaining markers.
 */
final class AiTextFormatter {

    private AiTextFormatter() {}

    static CharSequence format(String text) {
        if (text == null || text.isEmpty()) return "";

        SpannableStringBuilder out = new SpannableStringBuilder();
        // Split on triple-backtick fences; odd segments are code blocks.
        String[] parts = text.split("```", -1);
        for (int i = 0; i < parts.length; i++) {
            boolean isCode = (i % 2) == 1;
            String part = parts[i];
            if (isCode) {
                // Drop an optional language hint on the first line.
                int newline = part.indexOf('\n');
                if (newline >= 0 && newline < 20) {
                    String firstLine = part.substring(0, newline).trim();
                    if (!firstLine.contains(" ")) part = part.substring(newline + 1);
                }
                part = part.trim();
                int start = out.length();
                out.append(part);
                out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
            }
            else {
                appendInline(out, part);
            }
        }
        trimTrailingNewlines(out);
        return out;
    }

    /** Handles **bold**, `code` and stray leading list markers inside a prose run. */
    private static void appendInline(SpannableStringBuilder out, String text) {
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("**", i) || text.startsWith("__", i)) {
                String marker = text.substring(i, i + 2);
                int close = text.indexOf(marker, i + 2);
                if (close > i + 2) {
                    int start = out.length();
                    out.append(text, i + 2, close);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 2;
                    continue;
                }
            }
            if (text.charAt(i) == '`') {
                int close = text.indexOf('`', i + 1);
                if (close > i + 1) {
                    int start = out.length();
                    out.append(text, i + 1, close);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            if (text.startsWith("### ", i)) { i += 4; continue; }
            if (text.startsWith("## ", i)) { i += 3; continue; }
            if (text.startsWith("# ", i)) { i += 2; continue; }
            out.append(text.charAt(i));
            i++;
        }
    }

    private static void trimTrailingNewlines(SpannableStringBuilder out) {
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
    }
}
