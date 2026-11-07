package io.github.editid0.swiftrtp.gui;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Pure slot arithmetic for chest menus (9 columns). */
public final class SlotLayout {

    public static final int COLUMNS = 9;

    private SlotLayout() {}

    public static int size(int rows) {
        return rows * COLUMNS;
    }

    /** Clamps a configured row count into the valid chest range 1..6. */
    public static int clampRows(int rows) {
        return Math.max(1, Math.min(6, rows));
    }

    /**
     * Parses a slot value: an index ({@code 13}) or {@code "row:column"} / {@code "row,column"} with 1-based row and
     * column ({@code "2:5"} = centre of row 2 = slot 13).
     *
     * @return the 0-based slot, or null if the value is missing, {@code auto}, or invalid
     */
    public static @Nullable Integer parse(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = value.toString().strip();
        if (s.isEmpty() || s.equalsIgnoreCase("auto")) {
            return null;
        }
        String[] parts = s.split("[:,]");
        try {
            if (parts.length == 1) {
                return Integer.parseInt(parts[0].strip());
            }
            if (parts.length == 2) {
                int row = Integer.parseInt(parts[0].strip());
                int col = Integer.parseInt(parts[1].strip());
                if (row < 1 || col < 1 || col > COLUMNS) {
                    return null;
                }
                return (row - 1) * COLUMNS + (col - 1);
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return null;
    }

    public static boolean inBounds(int slot, int rows) {
        return slot >= 0 && slot < size(rows);
    }

    /**
     * Picks {@code count} free slots for items without an explicit slot: evenly spaced and centred in the middle row
     * (one gap between items when it fits), falling back to the first free slots of the menu.
     *
     * @param taken slots already occupied
     * @return up to {@code count} distinct slots (fewer only if the menu is full)
     */
    public static List<Integer> autoPlace(int rows, int count, Set<Integer> taken) {
        List<Integer> result = new ArrayList<>(count);
        if (count <= 0) {
            return result;
        }
        int middleRowStart = ((rows - 1) / 2) * COLUMNS;
        int step = count * 2 - 1 <= COLUMNS ? 2 : 1;
        if (count <= COLUMNS) {
            int span = (count - 1) * step + 1;
            int startCol = (COLUMNS - span) / 2;
            List<Integer> candidate = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                candidate.add(middleRowStart + startCol + i * step);
            }
            if (candidate.stream().noneMatch(taken::contains)) {
                return candidate;
            }
        }
        for (int slot = 0; slot < size(rows) && result.size() < count; slot++) {
            if (!taken.contains(slot)) {
                result.add(slot);
            }
        }
        return result;
    }
}
