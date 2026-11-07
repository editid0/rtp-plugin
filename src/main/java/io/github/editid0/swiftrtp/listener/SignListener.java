package io.github.editid0.swiftrtp.listener;

import io.github.editid0.swiftrtp.SwiftRTP;
import io.github.editid0.swiftrtp.api.RtpCause;
import io.github.editid0.swiftrtp.command.WorldNames;
import io.github.editid0.swiftrtp.config.MessageService;
import io.github.editid0.swiftrtp.config.PluginSettings;
import io.github.editid0.swiftrtp.config.WorldSettings;
import io.github.editid0.swiftrtp.teleport.TeleportRequest;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * RTP signs ({@code triggers.signs} in config.yml).
 * <ul>
 *   <li><b>Create:</b> write the configured header (default {@code [RTP]}, case-insensitive) on line 1 and optionally a
 *       world name on line 2. Requires {@code triggers.signs.create-permission}. The text is replaced with
 *       {@code sign.line-1..4} and the sign block is marked with PDC key {@code swiftrtp:rtp_sign} (value = world name,
 *       or "" for the player's current world).</li>
 *   <li><b>Use:</b> right-click a marked sign (needs {@code swiftrtp.sign.use}). The mark, not the text, identifies RTP
 *       signs, so they keep working if the text is changed later.</li>
 *   <li><b>Protect:</b> marked signs can only be edited by players with the create permission, and only while
 *       sneaking (a plain right-click always teleports). Break the sign to remove it.</li>
 * </ul>
 * All handlers run on the region thread owning the sign (Folia) / main thread (Paper).
 */
public final class SignListener implements Listener {

    private static final long USE_DEBOUNCE_MILLIS = 750;
    private static final String DEFAULT_CREATE_PERMISSION = "swiftrtp.sign.create";
    private static final String USE_PERMISSION = "swiftrtp.sign.use";

    private final SwiftRTP plugin;
    private final NamespacedKey key;
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
    /** Last sign use per player, to swallow repeated interact events from one click/held button. */
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    public SignListener(SwiftRTP plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "rtp_sign");
    }

    // ---- creation --------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        PluginSettings.SignTrigger cfg = signSettings();
        if (cfg == null) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String createPerm = createPermission(cfg);
        boolean alreadyMarked = markOf(block.getState(false)) != null;

        if (!SignText.isHeader(plainLine(event, 0), cfg.header())) {
            // Editing an existing RTP sign: only sign creators may (normally prevented by onOpenSign already).
            if (alreadyMarked && !player.hasPermission(createPerm)) {
                event.setCancelled(true);
            }
            return;
        }
        if (!player.hasPermission(createPerm)) {
            messages().send(player, "general.no-permission");
            event.setCancelled(true);
            return;
        }

        String typedWorld = SignText.worldLine(plainLine(event, 1));
        String worldName = "";
        if (alreadyMarked && typedWorld.equalsIgnoreCase(plain.serialize(messages().get("sign.current-world")).trim())) {
            // Staff re-saving an existing current-world sign: line 2 shows the "current world" label, not a world.
            typedWorld = "";
        }
        if (!typedWorld.isEmpty()) {
            worldName = resolveEnabledWorldName(typedWorld);
            if (worldName == null) {
                messages().send(player, "sign.invalid-world", Placeholder.unparsed("world", typedWorld));
                event.setCancelled(true);
                return;
            }
        }

        TagResolver worldTag = worldName.isEmpty()
                ? Placeholder.component("world", messages().get("sign.current-world"))
                : Placeholder.unparsed("world", worldName);
        for (int i = 0; i < 4; i++) {
            event.line(i, messages().get("sign.line-" + (i + 1), worldTag));
        }

        // The new text is written to the block entity after this event; mark it on the next tick from a fresh
        // snapshot so our update() can't race the text change.
        String mark = worldName;
        plugin.scheduling().regionLater(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, () -> {
            if (block.getState() instanceof Sign sign) {
                sign.getPersistentDataContainer().set(key, PersistentDataType.STRING, mark);
                sign.update(true, false);
            }
        }, 1L);
        messages().send(player, "sign.created", worldTag);
        plugin.debug(player.getName() + " created an RTP sign at " + block.getLocation() + " for '" + mark + "'");
    }

    // ---- use -------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !(block.getState(false) instanceof Sign sign)) {
            return;
        }
        String mark = markOf(sign);
        if (mark == null) {
            return;
        }
        PluginSettings.SignTrigger cfg = signSettings();
        if (cfg == null) {
            return;
        }
        Player player = event.getPlayer();
        if (player.isSneaking() && player.hasPermission(createPermission(cfg))) {
            return; // let staff edit / wax / dye the sign
        }
        // Never open the editor nor apply dye/ink/wax for normal use.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        long now = System.currentTimeMillis();
        Long last = lastUse.put(player.getUniqueId(), now);
        if (last != null && now - last < USE_DEBOUNCE_MILLIS) {
            return;
        }
        if (!player.hasPermission(USE_PERMISSION)) {
            messages().send(player, "general.no-permission");
            return;
        }
        World world = null;
        if (!mark.isEmpty()) {
            world = Bukkit.getWorld(mark);
            if (world == null) {
                messages().send(player, "general.world-not-found", Placeholder.unparsed("world", mark));
                return;
            }
        }
        plugin.teleports().teleport(TeleportRequest.self(player, world, RtpCause.SIGN))
                .whenComplete((outcome, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "Sign RTP of " + player.getName() + " failed", error);
                    }
                });
    }

    // ---- protection ------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenSign(PlayerOpenSignEvent event) {
        if (markOf(event.getSign()) == null) {
            return;
        }
        PluginSettings.SignTrigger cfg = signSettings();
        if (cfg == null) {
            return;
        }
        Player player = event.getPlayer();
        if (event.getCause() == PlayerOpenSignEvent.Cause.PLUGIN) {
            return; // another plugin explicitly opened it
        }
        if (!(player.isSneaking() && player.hasPermission(createPermission(cfg)))) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** The sign's RTP mark (world name or ""), or null if it is not an RTP sign. */
    private @Nullable String markOf(BlockState state) {
        if (!(state instanceof Sign sign)) {
            return null;
        }
        return sign.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /** Sign settings if signs are enabled, else null. */
    private PluginSettings.@Nullable SignTrigger signSettings() {
        PluginSettings settings = plugin.settings();
        if (settings == null || !settings.triggers().signs().enabled()) {
            return null;
        }
        return settings.triggers().signs();
    }

    private static String createPermission(PluginSettings.SignTrigger cfg) {
        String perm = cfg.createPermission();
        return perm == null || perm.isBlank() ? DEFAULT_CREATE_PERMISSION : perm;
    }

    private @Nullable String plainLine(SignChangeEvent event, int index) {
        Component line = event.line(index);
        return line == null ? null : plain.serialize(line);
    }

    /** Canonical name of a loaded, RTP-enabled world matching {@code typed} (case-insensitive), else null. */
    private @Nullable String resolveEnabledWorldName(String typed) {
        List<String> loaded = Bukkit.getWorlds().stream().map(World::getName).toList();
        String name = WorldNames.resolve(typed, loaded);
        PluginSettings settings = plugin.settings();
        if (name == null || settings == null) {
            return null;
        }
        Optional<WorldSettings> ws = settings.world(name);
        return ws.isPresent() && ws.get().enabled() ? name : null;
    }

    private MessageService messages() {
        return plugin.messages();
    }
}
