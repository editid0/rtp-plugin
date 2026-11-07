package io.github.editid0.primedrtp.gui;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.api.RtpCause;
import io.github.editid0.primedrtp.config.MessageService;
import io.github.editid0.primedrtp.config.WorldSettings;
import io.github.editid0.primedrtp.config.WorldSettings.CostSettings;
import io.github.editid0.primedrtp.config.WorldSettings.ItemCost;
import io.github.editid0.primedrtp.config.WorldSettings.SoundSpec;
import io.github.editid0.primedrtp.gui.GuiConfig.ItemTemplate;
import io.github.editid0.primedrtp.gui.GuiConfig.StaticButton;
import io.github.editid0.primedrtp.gui.GuiConfig.WorldButton;
import io.github.editid0.primedrtp.gui.MenuHolder.ButtonTarget;
import io.github.editid0.primedrtp.gui.MenuHolder.Target;
import io.github.editid0.primedrtp.gui.MenuHolder.WorldTarget;
import io.github.editid0.primedrtp.gui.MenuPlaceholders.State;
import io.github.editid0.primedrtp.gui.MenuPlaceholders.WorldView;
import io.github.editid0.primedrtp.hook.EconomyHook;
import io.github.editid0.primedrtp.pool.PoolStats;
import io.github.editid0.primedrtp.teleport.TeleportRequest;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.key.InvalidKeyException;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The configurable world-selector menu (gui.yml).
 * <p>
 * Threading: all inventory/player work runs on the viewer's entity thread (Folia-safe). Inventory events for a
 * player already fire on that thread. Each open menu has an entity-scheduled refresh timer that re-renders the
 * items (cooldowns, pool fill) and stops itself as soon as the menu is no longer the player's open inventory or the
 * player leaves.
 */
public final class RtpMenu implements GuiService, Listener {

    private static final String FILE = "gui.yml";
    private static final String PERM_GUI = "primedrtp.gui";
    private static final String PERM_BYPASS_COST = "primedrtp.bypass.cost";

    private final PrimedRTP plugin;
    private final ItemFactory items;
    private final Set<MenuHolder> openMenus = ConcurrentHashMap.newKeySet();
    private volatile GuiConfig config;
    private boolean listening;

    public RtpMenu(PrimedRTP plugin) {
        this.plugin = plugin;
        this.items = new ItemFactory(msg -> plugin.getLogger().warning(msg));
        this.config = GuiConfigParser.parse(new YamlConfiguration(), null, msg -> {});
    }

    // ---- lifecycle ---------------------------------------------------------------------------------------------

    @Override
    public void start() {
        load();
        if (!listening) {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            listening = true;
        }
    }

    @Override
    public void reload() {
        load();
        // Open menus keep the layout they were built with; close them so the new layout is used on next open.
        closeAll();
    }

    @Override
    public void stop() {
        closeAll();
        HandlerList.unregisterAll(this);
        listening = false;
    }

    @Override
    public boolean isEnabled() {
        return config.enabled();
    }

    @Override
    public boolean opensOnBareCommand() {
        GuiConfig c = config;
        return c.enabled() && c.openOnBareCommand();
    }

    @Override
    public void open(Player player) {
        GuiConfig cfg = config;
        if (!cfg.enabled()) {
            plugin.messages().send(player, "command.gui-disabled");
            return;
        }
        plugin.scheduling().entityOrNow(player, () -> openNow(player, cfg), null);
    }

    // ---- loading -----------------------------------------------------------------------------------------------

    /** Loads gui.yml (creating it if missing), adds keys introduced by newer versions, and parses it. */
    private void load() {
        File file = new File(plugin.getDataFolder(), FILE);
        if (!file.exists()) {
            plugin.saveResource(FILE, false);
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try (InputStream in = plugin.getResource(FILE)) {
            if (in != null) {
                try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    defaults.load(reader);
                }
            }
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read bundled gui.yml", e);
        }

        YamlConfiguration user = new YamlConfiguration();
        try {
            user.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().warning("gui.yml is invalid, " + (config.worlds().isEmpty() && config.buttons().isEmpty()
                    ? "using the bundled defaults" : "keeping the previous menu") + ": " + e.getMessage());
            if (config.worlds().isEmpty() && config.buttons().isEmpty()) {
                config = GuiConfigParser.parse(defaults, null, msg -> plugin.getLogger().warning(msg));
            }
            return;
        }

        if (addMissingKeys(user, defaults)) {
            try {
                user.save(file);
                plugin.debug("Added missing keys to gui.yml");
            } catch (IOException e) {
                plugin.getLogger().warning("Could not update gui.yml with new keys: " + e.getMessage());
            }
        }
        items.resetWarnings();
        config = GuiConfigParser.parse(user, defaults, msg -> plugin.getLogger().warning(msg));
        plugin.debug("Loaded gui.yml: " + config.worlds().size() + " world item(s), " + config.buttons().size()
                + " button(s), enabled=" + config.enabled());
    }

    /**
     * Copies default keys the user file lacks, except inside the user-owned {@code items}/{@code buttons} maps and
     * below any key the user deliberately set to a non-section value (e.g. {@code filler: false}).
     */
    static boolean addMissingKeys(ConfigurationSection user, ConfigurationSection defaults) {
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (key.equals("items") || key.startsWith("items.") || key.equals("buttons") || key.startsWith("buttons.")) {
                continue;
            }
            if (defaults.isConfigurationSection(key) || user.isSet(key) || blockedByScalarParent(user, key)) {
                continue;
            }
            user.set(key, defaults.get(key));
            changed = true;
        }
        return changed;
    }

    private static boolean blockedByScalarParent(ConfigurationSection user, String key) {
        int dot = key.indexOf('.');
        while (dot > 0) {
            String parent = key.substring(0, dot);
            if (user.isSet(parent) && !user.isConfigurationSection(parent)) {
                return true;
            }
            dot = key.indexOf('.', dot + 1);
        }
        return false;
    }

    // ---- opening & rendering -----------------------------------------------------------------------------------

    private void openNow(Player player, GuiConfig cfg) {
        if (!player.isOnline()) {
            return;
        }
        if (!player.hasPermission(PERM_GUI)) {
            plugin.messages().send(player, "general.no-permission");
            return;
        }
        MenuHolder holder = new MenuHolder(cfg, player.getUniqueId());
        Component title = MenuText.render(cfg.title(), MenuPlaceholders.basic(player.getName()));
        Inventory inventory = Bukkit.createInventory(holder, cfg.size(), title);
        holder.inventory(inventory);
        render(holder, player);
        player.openInventory(inventory);
        if (player.getOpenInventory().getTopInventory() != inventory) {
            return; // another plugin cancelled the open
        }
        openMenus.add(holder);
        play(player, cfg.sounds().open());

        Duration interval = cfg.refreshInterval();
        if (!interval.isZero()) {
            long ticks = Math.max(1, interval.toMillis() / 50);
            ScheduledTask task = plugin.scheduling().entityTimer(player, t -> refresh(holder, player, t),
                    () -> forget(holder), ticks, ticks);
            holder.refreshTask(task);
        }
    }

    private void refresh(MenuHolder holder, Player player, ScheduledTask task) {
        if (!player.isOnline() || !isViewing(player, holder)) {
            task.cancel();
            forget(holder);
            return;
        }
        try {
            render(holder, player);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to refresh RTP menu for " + player.getName(), e);
            task.cancel();
        }
    }

    private void forget(MenuHolder holder) {
        holder.cancelRefresh();
        openMenus.remove(holder);
    }

    private static boolean isViewing(Player player, MenuHolder holder) {
        return player.getOpenInventory().getTopInventory().getHolder(false) == holder;
    }

    /** (Re)builds every slot; only changed slots are written to avoid needless packets. */
    private void render(MenuHolder holder, Player player) {
        GuiConfig cfg = holder.config;
        ItemStack[] contents = new ItemStack[cfg.size()];
        holder.targets.clear();

        if (cfg.filler() != null) {
            ItemStack filler = items.build(cfg.filler(), MenuPlaceholders.basic(player.getName()));
            if (filler != null) {
                for (int i = 0; i < contents.length; i++) {
                    contents[i] = filler;
                }
            }
        }
        for (StaticButton button : cfg.buttons()) {
            ItemStack item = items.build(button.item(), MenuPlaceholders.basic(player.getName()));
            for (int slot : button.slots()) {
                contents[slot] = item;
                holder.targets.put(slot, new ButtonTarget(button));
            }
        }
        for (WorldButton button : cfg.worlds()) {
            renderWorld(holder, player, button, contents);
        }

        Inventory inv = holder.getInventory();
        for (int i = 0; i < contents.length; i++) {
            ItemStack current = inv.getItem(i);
            if (!sameItem(current, contents[i])) {
                inv.setItem(i, contents[i]);
            }
        }
    }

    private static boolean sameItem(@Nullable ItemStack a, @Nullable ItemStack b) {
        boolean aEmpty = a == null || a.isEmpty();
        boolean bEmpty = b == null || b.isEmpty();
        if (aEmpty || bEmpty) {
            return aEmpty == bEmpty;
        }
        return Objects.equals(a, b);
    }

    private void renderWorld(MenuHolder holder, Player player, WorldButton button, ItemStack[] contents) {
        World world = Bukkit.getWorld(button.world());
        Optional<WorldSettings> wsOpt = plugin.settings().world(button.world());
        boolean available = world != null && wsOpt.isPresent() && wsOpt.get().enabled();
        if (!available && button.hideIfUnavailable()) {
            return;
        }
        GuiConfig.Texts texts = holder.config.texts();
        MessageService messages = plugin.messages();

        WorldView view;
        if (available) {
            WorldSettings ws = wsOpt.get();
            boolean permitted = io.github.editid0.primedrtp.Permissions.canUseWorld(player, ws);
            if (!permitted && button.hideIfNoPermission()) {
                return;
            }
            Duration remaining = plugin.cooldowns().remaining(player, ws);
            Duration length = plugin.cooldowns().cooldownFor(player, ws);
            State state = !permitted ? State.LOCKED : remaining.compareTo(Duration.ZERO) > 0 ? State.COOLDOWN : State.READY;
            PoolStats stats = plugin.pools().stats(world);
            view = new WorldView(button.world(), player.getName(), state,
                    state == State.COOLDOWN ? messages.formatDuration(remaining) : texts.cooldownReady(),
                    length.isZero() ? texts.noCooldown() : messages.formatDuration(length),
                    cost(player, ws.cost(), texts),
                    stats.size(), stats.target(), stats.fillRatio(),
                    ws.area().maxRadius(), ws.area().minRadius(),
                    messages.formatDuration(ws.teleport().warmup()));
        } else {
            view = new WorldView(button.world(), player.getName(), State.LOCKED, texts.cooldownReady(),
                    texts.noCooldown(), MenuText.render(texts.costFree(), TagResolver.empty()), 0, 0, 0, 0, 0,
                    messages.formatDuration(Duration.ZERO));
        }

        ItemTemplate template = switch (view.state()) {
            case READY -> button.normal();
            case COOLDOWN -> button.cooldown();
            case LOCKED -> button.locked();
        };
        contents[button.slot()] = items.build(template, MenuPlaceholders.world(view, texts));
        holder.targets.put(button.slot(), new WorldTarget(button, view.state()));
    }

    /** Builds {@code <cost>} from the messages.yml cost fragments, or {@code text.cost-free}. */
    private Component cost(Player player, CostSettings cost, GuiConfig.Texts texts) {
        if (cost.isFree() || player.hasPermission(PERM_BYPASS_COST)) {
            return MenuText.render(texts.costFree(), TagResolver.empty());
        }
        MessageService messages = plugin.messages();
        List<Component> parts = new ArrayList<>();
        if (cost.money() > 0) {
            Optional<EconomyHook> eco = plugin.hooks().economy();
            eco.ifPresent(e -> parts.add(messages.get("teleport.cost-money",
                    Placeholder.unparsed("amount", e.format(cost.money())))));
        }
        if (cost.xpLevels() > 0) {
            parts.add(messages.get("teleport.cost-xp", Placeholder.unparsed("amount", Integer.toString(cost.xpLevels()))));
        }
        for (ItemCost item : cost.items()) {
            parts.add(messages.get("teleport.cost-item",
                    Placeholder.unparsed("amount", Integer.toString(item.amount())),
                    Placeholder.unparsed("item", MenuText.prettyName(item.material().name()))));
        }
        if (parts.isEmpty()) {
            return MenuText.render(texts.costFree(), TagResolver.empty());
        }
        return Component.join(JoinConfiguration.separator(messages.get("teleport.cost-separator")), parts);
    }

    // ---- events ------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof MenuHolder holder)) {
            return;
        }
        // Cancel everything (shift-click, number keys, double-click collect, drops, bottom inventory, ...).
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        // Raw slots below the top inventory's size belong to the menu (inventory wrappers aren't identity-stable).
        if (rawSlot < 0 || rawSlot >= top.getSize() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT) {
            return;
        }
        Target target = holder.targets.get(rawSlot);
        if (target == null || holder.actionTaken) {
            return;
        }
        switch (target) {
            case WorldTarget wt -> clickWorld(holder, player, wt);
            case ButtonTarget bt -> clickButton(holder, player, bt.button());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        InventoryHolder h = event.getInventory().getHolder(false);
        if (h instanceof MenuHolder holder) {
            forget(holder);
        }
    }

    private void clickWorld(MenuHolder holder, Player player, WorldTarget target) {
        holder.actionTaken = true;
        GuiConfig.Sounds sounds = holder.config.sounds();
        play(player, target.state() == State.READY ? sounds.click() : sounds.denied());
        String worldName = target.button().world();
        // Close on the next tick (closing inside the click handler is unsafe), then start the teleport, which
        // sends every success/failure message (cooldown, permission, cost) itself.
        plugin.scheduling().entity(player, () -> {
            player.closeInventory();
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                plugin.messages().send(player, "general.world-not-found", Placeholder.unparsed("world", worldName));
                return;
            }
            plugin.teleports().teleport(TeleportRequest.self(player, world, RtpCause.GUI));
        }, null);
    }

    private void clickButton(MenuHolder holder, Player player, StaticButton button) {
        if (button.actions().isEmpty()) {
            return;
        }
        boolean closes = button.actions().stream()
                .anyMatch(a -> a instanceof MenuAction.Close || a instanceof MenuAction.Rtp);
        if (closes) {
            holder.actionTaken = true;
        }
        play(player, holder.config.sounds().click());
        plugin.scheduling().entity(player, () -> {
            for (MenuAction action : button.actions()) {
                runAction(player, action);
            }
        }, null);
    }

    private void runAction(Player player, MenuAction action) {
        switch (action) {
            case MenuAction.Close c -> player.closeInventory();
            case MenuAction.Rtp rtp -> {
                player.closeInventory();
                World world = null;
                if (rtp.world() != null) {
                    world = Bukkit.getWorld(rtp.world());
                    if (world == null) {
                        plugin.messages().send(player, "general.world-not-found", Placeholder.unparsed("world", rtp.world()));
                        return;
                    }
                }
                plugin.teleports().teleport(TeleportRequest.self(player, world, RtpCause.GUI));
            }
            case MenuAction.PlayerCommand pc -> player.performCommand(expand(pc.command(), player));
            case MenuAction.ConsoleCommand cc -> {
                String cmd = expand(cc.command(), player);
                plugin.scheduling().global(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd));
            }
        }
    }

    private static String expand(String command, Player player) {
        return MenuAction.expand(command, player.getName(), player.getUniqueId().toString(), player.getWorld().getName());
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private void play(Player player, @Nullable SoundSpec spec) {
        if (spec == null) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(spec.key()), Sound.Source.MASTER, spec.volume(), spec.pitch()),
                    Sound.Emitter.self());
        } catch (InvalidKeyException e) {
            plugin.debug("Invalid gui.yml sound key '" + spec.key() + "'");
        }
    }

    /** Closes every open menu (best effort during shutdown). */
    private void closeAll() {
        for (MenuHolder holder : List.copyOf(openMenus)) {
            forget(holder);
            Player player = Bukkit.getPlayer(holder.viewer);
            if (player == null) {
                continue;
            }
            try {
                plugin.scheduling().entityOrNow(player, () -> {
                    if (isViewing(player, holder)) {
                        player.closeInventory();
                    }
                }, null);
            } catch (RuntimeException e) {
                // Scheduling is refused while the plugin is disabling on Folia; the server closes inventories anyway.
                plugin.debug("Could not close RTP menu for " + player.getName() + ": " + e.getMessage());
            }
        }
        openMenus.clear();
    }
}
