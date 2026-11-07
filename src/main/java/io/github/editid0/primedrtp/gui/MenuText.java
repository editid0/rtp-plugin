package io.github.editid0.primedrtp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure text helpers for menu item names/lore (no server access; unit-tested). */
public final class MenuText {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MenuText() {}

    /**
     * Renders a MiniMessage template. Item text is non-italic unless the template explicitly sets italics
     * ({@code <italic>} / {@code <i>}), overriding the client's default italic item-name styling.
     */
    public static Component render(String template, TagResolver resolver) {
        return MM.deserialize(template, resolver).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Renders each lore line with {@link #render}; blank lines stay empty lines. */
    public static List<Component> renderLore(List<String> lines, TagResolver resolver) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(render(line, resolver));
        }
        return out;
    }

    /**
     * Builds a MiniMessage progress bar, e.g. {@code <green>||||</green><dark_gray>||||||</dark_gray>}.
     *
     * @param ratio       fill ratio, clamped to [0, 1]; NaN counts as 0
     * @param length      total symbols (at least 1)
     * @param symbol      symbol repeated per cell (MiniMessage tags in it are escaped)
     * @param filledColor MiniMessage colour tag name for filled cells, e.g. {@code green} or {@code #22c55e}
     * @param emptyColor  colour tag name for empty cells
     */
    public static String progressBar(double ratio, int length, String symbol, String filledColor, String emptyColor) {
        int len = Math.max(1, length);
        double r = Double.isNaN(ratio) ? 0 : Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(r * len);
        String sym = MM.escapeTags(symbol.isEmpty() ? "|" : symbol);
        StringBuilder sb = new StringBuilder();
        if (filled > 0) {
            sb.append('<').append(filledColor).append('>').append(sym.repeat(filled)).append("</").append(filledColor).append('>');
        }
        if (filled < len) {
            sb.append('<').append(emptyColor).append('>').append(sym.repeat(len - filled)).append("</").append(emptyColor).append('>');
        }
        return sb.toString();
    }

    /** Whole-number percentage (0-100) of a fill ratio. */
    public static int percent(double ratio) {
        double r = Double.isNaN(ratio) ? 0 : Math.max(0, Math.min(1, ratio));
        return (int) Math.round(r * 100);
    }

    /** {@code OAK_LOG} / {@code minecraft:oak_log} → {@code Oak Log}. */
    public static String prettyName(String enumOrKey) {
        String s = enumOrKey;
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (String word : s.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }
}
