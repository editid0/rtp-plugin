package io.github.editid0.primedrtp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * MiniMessage placeholders available in world item names/lore. Pure (unit-tested).
 * <ul>
 *   <li>{@code <world>} world name</li>
 *   <li>{@code <player>} viewer name</li>
 *   <li>{@code <cooldown>} remaining cooldown (formatted) or {@code text.cooldown-ready}</li>
 *   <li>{@code <cooldown_length>} the cooldown this player gets after teleporting</li>
 *   <li>{@code <cost>} cost (from messages.yml cost fragments) or {@code text.cost-free}</li>
 *   <li>{@code <pool_size>}, {@code <pool_target>}, {@code <pool_percent>}, {@code <pool_bar>}</li>
 *   <li>{@code <status>} one of {@code text.status-ready / status-cooldown / status-locked}</li>
 *   <li>{@code <radius>}, {@code <min_radius>} search area radii in blocks</li>
 *   <li>{@code <warmup>} warmup duration (formatted)</li>
 * </ul>
 */
public final class MenuPlaceholders {

    /** Item state of a world button for one viewer. */
    public enum State { READY, COOLDOWN, LOCKED }

    /** Everything needed to fill a world item, collected on the player's thread. */
    public record WorldView(String world, String player, State state, String cooldown, String cooldownLength,
                            Component cost, int poolSize, int poolTarget, double poolFill, int radius, int minRadius,
                            String warmup) {}

    private MenuPlaceholders() {}

    public static String statusTemplate(State state, GuiConfig.Texts texts) {
        return switch (state) {
            case READY -> texts.statusReady();
            case COOLDOWN -> texts.statusCooldown();
            case LOCKED -> texts.statusLocked();
        };
    }

    public static TagResolver world(WorldView v, GuiConfig.Texts texts) {
        String bar = MenuText.progressBar(v.poolFill(), texts.barLength(), texts.barSymbol(), texts.barFilledColor(),
                texts.barEmptyColor());
        return TagResolver.resolver(
                Placeholder.unparsed("world", v.world()),
                Placeholder.unparsed("player", v.player()),
                Placeholder.parsed("status", statusTemplate(v.state(), texts)),
                // Formatted durations come from messages.yml and may contain MiniMessage.
                Placeholder.parsed("cooldown", v.cooldown()),
                Placeholder.parsed("cooldown_length", v.cooldownLength()),
                Placeholder.component("cost", v.cost()),
                Placeholder.unparsed("pool_size", Integer.toString(v.poolSize())),
                Placeholder.unparsed("pool_target", Integer.toString(v.poolTarget())),
                Placeholder.unparsed("pool_percent", Integer.toString(MenuText.percent(v.poolFill()))),
                Placeholder.parsed("pool_bar", bar),
                Placeholder.unparsed("radius", Integer.toString(v.radius())),
                Placeholder.unparsed("min_radius", Integer.toString(v.minRadius())),
                Placeholder.parsed("warmup", v.warmup()));
    }

    /** Placeholders for static buttons and the title: {@code <player>}. */
    public static TagResolver basic(String player) {
        return Placeholder.unparsed("player", player);
    }
}
