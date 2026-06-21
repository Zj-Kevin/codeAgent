package com.mewcode.tui;

import java.util.regex.Pattern;

/**
 * Renders Markdown to ANSI terminal output.
 *
 * <p>Line-by-line streaming: call {@link #renderLine(String)} as each line
 * completes during the stream. Maintains internal state for multi-line
 * constructs like code blocks.
 *
 * <p>Supported syntax:
 * <ul>
 *   <li>{@code **bold**} → ANSI bold</li>
 *   <li>{@code *italic*} → ANSI underline (dim in some terms)</li>
 *   <li>{@code `inline code`} → dimmed</li>
 *   <li>{@code ```code block```} → dimmed box with language tag</li>
 *   <li>{@code # heading} → bold</li>
 *   <li>{@code - list} / {@code * list} → bullet</li>
 *   <li>{@code > quote} → vertical bar prefix</li>
 * </ul>
 */
public class MarkdownRenderer {

    // ── ANSI ────────────────────────────────────────────
    private static final String RST    = "[0m";
    private static final String BOLD   = "[1m";
    private static final String DIM    = "[2m";
    private static final String ITALIC = "[3m";
    private static final String GRAY   = "[90m";

    // ── Patterns ────────────────────────────────────────
    private static final Pattern BOLD_PAT   = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC_PAT = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern INLINE_CODE_PAT = Pattern.compile("`([^`]+)`");
    private static final Pattern HEADING_PAT = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern LIST_PAT = Pattern.compile("^(\\s*)[-*]\\s+");
    private static final Pattern QUOTE_PAT = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern FENCE_PAT = Pattern.compile("^```(\\w*)");
    private static final Pattern LINK_PAT = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    // ── State ───────────────────────────────────────────
    private boolean inCodeBlock = false;
    private String fenceLang = null;

    /**
     * Render one line of markdown to ANSI.
     * Maintains code-block state across calls.
     */
    public String renderLine(String line) {
        // Empty line → preserve as visual break (compact)
        if (line.isEmpty()) return "";

        // Code fence detection
        var fenceMatch = FENCE_PAT.matcher(line.trim());
        if (fenceMatch.matches()) {
            if (!inCodeBlock) {
                // Opening fence
                inCodeBlock = true;
                fenceLang = fenceMatch.group(1);
                String lang = fenceLang.isEmpty() ? "" : " " + fenceLang;
                return GRAY + " ┌" + RST + DIM + lang + RST;
            } else {
                // Closing fence
                inCodeBlock = false;
                fenceLang = null;
                return GRAY + " └" + RST;
            }
        }

        // Inside a code block
        if (inCodeBlock) {
            return GRAY + " │ " + DIM + line + RST;
        }

        // ── Block-level formatting ──────────────────
        String result = line;

        // Heading — special handling
        var headingMatch = HEADING_PAT.matcher(result);
        if (headingMatch.matches()) {
            int level = headingMatch.group(1).length();
            String text = headingMatch.group(2);
            if (level <= 2) {
                return BOLD + text + RST;
            } else {
                return BOLD + DIM + " " + text + RST;
            }
        }

        // Blockquote
        var quoteMatch = QUOTE_PAT.matcher(result);
        if (quoteMatch.matches()) {
            result = GRAY + "│ " + RST + quoteMatch.group(1);
        }

        // Unordered list
        result = LIST_PAT.matcher(result).replaceFirst("  · ");

        // Ordered list: "1. text"
        result = result.replaceAll("^(\\s*)\\d+\\.\\s+", "  $1· ");

        // Horizontal rule
        if (result.trim().matches("^[-*_]{3,}$")) {
            return DIM + "─".repeat(40) + RST;
        }

        // ── Inline formatting ───────────────────────
        // Links: [text](url) → text (dimmed url)
        result = LINK_PAT.matcher(result).replaceAll(BOLD + "$1" + RST + DIM + " ($2)" + RST);
        // Bold: **text**
        result = BOLD_PAT.matcher(result).replaceAll(BOLD + "$1" + RST);
        // Italic: *text* (after bold to avoid conflict)
        result = ITALIC_PAT.matcher(result).replaceAll(ITALIC + "$1" + RST);
        // Inline code: `text`
        result = INLINE_CODE_PAT.matcher(result).replaceAll(DIM + "`$1`" + RST);

        return result;
    }

    /**
     * Render full markdown text (for use after streaming completes).
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        reset();
        StringBuilder sb = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append(renderLine(line));
        }
        return sb.toString();
    }

    /** Reset internal state for a fresh rendering. */
    public void reset() {
        inCodeBlock = false;
        fenceLang = null;
    }
}
