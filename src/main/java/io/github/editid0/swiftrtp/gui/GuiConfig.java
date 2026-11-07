package io.github.editid0.swiftrtp.gui;

import io.github.editid0.swiftrtp.config.WorldSettings.SoundSpec;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;

/**
 * Immutable, parsed gui.yml (see the shipped gui.yml for documentation of every key). Materials are kept as strings
 * here and resolved when items are built, so parsing needs no server. Produced by {@link GuiConfigParser}.
 */
public record GuiConfig(
        boolean enabled,
        boolean openOnBareCommand,
        String title,
        int rows,
        /* ZERO = no live refresh. */
        Duration refreshInterval,
        /* null = no filler. */
        @Nullable ItemTemplate filler,
        Texts texts,
        Sounds sounds,
        List<WorldButton> worlds,
        List<StaticButton> buttons
) {

    /** Slot count of the menu. */
    public int size() {
        return SlotLayout.size(rows);
    }

    /**
     * Visual definition of one item.
     *
     * @param material        material name (e.g. {@code GRASS_BLOCK}); resolved when building
     * @param name            MiniMessage display name; empty = keep the vanilla item name
     * @param lore            MiniMessage lore lines
     * @param glow            enchantment glint
     * @param customModelData float for the custom-model-data component; null = none
     * @param itemModel       namespaced item model key (e.g. {@code mypack:rtp/overworld}); null = default
     * @param hideTooltip     hide the whole tooltip (useful for filler panes)
     * @param amount          stack size 1-99
     */
    public record ItemTemplate(String material, String name, List<String> lore, boolean glow,
                               @Nullable Float customModelData, @Nullable String itemModel, boolean hideTooltip,
                               int amount) {}

    /**
     * A world selector button.
     *
     * @param slot      0-based slot (already resolved, including auto-placement)
     * @param normal    item when the player can teleport now
     * @param cooldown  item while the player is on cooldown
     * @param locked    item when the player lacks the world's permission
     */
    public record WorldButton(String world, int slot, ItemTemplate normal, ItemTemplate cooldown, ItemTemplate locked,
                              boolean hideIfNoPermission, boolean hideIfUnavailable) {}

    /** A static button with click actions (executed in order). */
    public record StaticButton(String id, List<Integer> slots, ItemTemplate item, List<MenuAction> actions) {}

    /** Values for {@code <status>}, {@code <cooldown>} (when ready), {@code <cost>} (when free), {@code <cooldown_length>} (when zero) and {@code <pool_bar>}. */
    public record Texts(String statusReady, String statusCooldown, String statusLocked, String cooldownReady,
                        String costFree, String noCooldown, int barLength, String barSymbol, String barFilledColor,
                        String barEmptyColor) {}

    /** Optional menu sounds (null = silent). */
    public record Sounds(@Nullable SoundSpec open, @Nullable SoundSpec click, @Nullable SoundSpec denied) {}
}
