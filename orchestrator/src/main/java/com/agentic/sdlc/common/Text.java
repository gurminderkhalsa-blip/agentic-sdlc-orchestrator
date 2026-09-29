package com.agentic.sdlc.common;

public final class Text {

    private Text() {
    }

    /** Cuts {@code value} to at most {@code max} characters, marking the cut so readers know text is missing. */
    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        String marker = "\n... [" + (value.length() - max) + " more characters truncated]";
        return value.substring(0, Math.max(0, max - marker.length())) + marker;
    }
}
