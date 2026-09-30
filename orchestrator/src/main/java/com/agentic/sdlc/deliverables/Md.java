package com.agentic.sdlc.deliverables;

import java.util.List;

/** Tiny Markdown builder for the deliverable reports. */
final class Md {

    private final StringBuilder out = new StringBuilder();

    Md h1(String text) {
        out.append("# ").append(text).append("\n\n");
        return this;
    }

    Md h2(String text) {
        out.append("## ").append(text).append("\n\n");
        return this;
    }

    Md h3(String text) {
        out.append("### ").append(text).append("\n\n");
        return this;
    }

    Md p(String text) {
        if (text != null && !text.isBlank()) {
            out.append(text.strip()).append("\n\n");
        }
        return this;
    }

    Md bullets(List<String> items) {
        if (items.isEmpty()) {
            out.append("_None._\n\n");
            return this;
        }
        items.forEach(i -> out.append("- ").append(i.replace("\n", " ")).append('\n'));
        out.append('\n');
        return this;
    }

    Md table(List<String> header, List<List<String>> rows) {
        if (rows.isEmpty()) {
            out.append("_None._\n\n");
            return this;
        }
        out.append("| ").append(String.join(" | ", header)).append(" |\n");
        out.append("|").append("---|".repeat(header.size())).append('\n');
        for (List<String> row : rows) {
            out.append("| ").append(String.join(" | ", row.stream().map(Md::cell).toList())).append(" |\n");
        }
        out.append('\n');
        return this;
    }

    Md code(String language, String body) {
        out.append("```").append(language).append('\n').append(body.strip()).append("\n```\n\n");
        return this;
    }

    Md raw(String markdown) {
        out.append(markdown.strip()).append("\n\n");
        return this;
    }

    static String cell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replace("\r", "").replace("\n", "<br>");
    }

    @Override
    public String toString() {
        return out.toString();
    }
}
