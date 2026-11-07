package io.github.editid0.primedrtp.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.api.RtpCause;
import io.github.editid0.primedrtp.config.MessageService;
import io.github.editid0.primedrtp.config.PluginSettings;
import io.github.editid0.primedrtp.config.WorldSettings;
import io.github.editid0.primedrtp.pool.PoolStats;
import io.github.editid0.primedrtp.scheduler.Scheduling;
import io.github.editid0.primedrtp.search.ChunkLoader;
import io.github.editid0.primedrtp.teleport.TeleportOutcome;
import io.github.editid0.primedrtp.teleport.TeleportRequest;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Registers {@code /rtp} (plus {@code command-aliases} from config.yml) through Paper's Brigadier API.
 *
 * <pre>
 * /rtp                                    primedrtp.use     GUI (if gui.yml opens it on bare command) or RTP
 * /rtp &lt;world&gt;                            primedrtp.use     RTP into a world
 * /rtp &lt;world&gt; &lt;targets&gt;                  primedrtp.others  send players (selectors allowed; console OK)
 * /rtp gui                                primedrtp.gui     open the world menu
 * /rtp cancel                             primedrtp.use     cancel a pending warmup
 * /rtp help                               primedrtp.use     help (+ admin help for primedrtp.admin)
 * /rtp admin reload|info                  primedrtp.admin
 * /rtp admin pool [world]                 primedrtp.admin   pool status
 * /rtp admin pool clear|refill &lt;world&gt;    primedrtp.admin
 * /rtp admin cooldown reset &lt;player&gt; [world]  primedrtp.admin
 * </pre>
 * Literal subcommands take precedence over the world argument. Worlds whose name equals a subcommand
 * ({@code help}, {@code gui}, {@code cancel}, {@code admin}) or contains characters outside {@code [A-Za-z0-9_.+-]}
 * are reachable by quoting them: {@code /rtp "admin"}; tab-completion inserts the quotes automatically.
 * <p>
 * Threading: Brigadier runs commands on the global/main thread for console and on the player's region thread for
 * players on Folia. Nothing here touches another entity directly: the teleport service and GUI hop threads
 * themselves, and reload runs on the global region scheduler.
 */
public final class RtpCommands {

    static final String PERM_USE = "primedrtp.use";
    static final String PERM_GUI = "primedrtp.gui";
    static final String PERM_OTHERS = "primedrtp.others";
    static final String PERM_ADMIN = "primedrtp.admin";

    /** Literal children of /rtp; worlds with these names must be quoted. */
    static final Set<String> RESERVED = Set.of("help", "gui", "cancel", "admin");
    /** Literal children of /rtp admin pool. */
    private static final Set<String> POOL_RESERVED = Set.of("clear", "refill");

    private final PrimedRTP plugin;

    public RtpCommands(PrimedRTP plugin) {
        this.plugin = plugin;
    }

    /** Called from the COMMANDS lifecycle event (may run more than once, e.g. on /minecraft:reload). */
    public void register(Commands commands) {
        PluginSettings settings = plugin.settings();
        List<String> aliases = settings == null ? List.of() : settings.commandAliases().stream()
                .map(a -> a.trim().toLowerCase(Locale.ROOT))
                .filter(a -> !a.isEmpty() && !a.equals("rtp"))
                .distinct()
                .toList();
        Set<String> registered = commands.register(buildRoot().build(), "Random teleport", aliases);
        plugin.debug("Registered command labels: " + registered);
    }

    // =========================================================================================================
    // tree
    // =========================================================================================================

    private LiteralArgumentBuilder<CommandSourceStack> buildRoot() {
        return Commands.literal("rtp")
                .requires(src -> has(src, PERM_USE) || has(src, PERM_OTHERS) || has(src, PERM_ADMIN))
                .executes(this::bare)
                .then(Commands.literal("help")
                        .requires(src -> has(src, PERM_USE) || has(src, PERM_ADMIN))
                        .executes(this::help))
                .then(Commands.literal("gui")
                        .requires(src -> has(src, PERM_GUI))
                        .executes(this::gui))
                .then(Commands.literal("cancel")
                        .requires(src -> has(src, PERM_USE))
                        .executes(this::cancel))
                .then(buildAdmin())
                .then(Commands.argument("world", StringArgumentType.string())
                        .requires(src -> has(src, PERM_USE) || has(src, PERM_OTHERS))
                        .suggests(playerWorldSuggestions())
                        .executes(this::selfWorld)
                        .then(Commands.argument("targets", ArgumentTypes.players())
                                .requires(src -> has(src, PERM_OTHERS))
                                .executes(this::sendOthers)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdmin() {
        return Commands.literal("admin")
                .requires(src -> has(src, PERM_ADMIN))
                .executes(this::help)
                .then(Commands.literal("reload").executes(this::adminReload))
                .then(Commands.literal("info").executes(this::adminInfo))
                .then(Commands.literal("pool")
                        .executes(ctx -> adminPool(ctx, null))
                        .then(Commands.literal("clear")
                                .then(adminWorldArg().executes(ctx -> adminPoolClear(ctx, true))))
                        .then(Commands.literal("refill")
                                .then(adminWorldArg().executes(ctx -> adminPoolClear(ctx, false))))
                        .then(Commands.argument("world", StringArgumentType.string())
                                .suggests(adminWorldSuggestions(POOL_RESERVED))
                                .executes(ctx -> adminPool(ctx, StringArgumentType.getString(ctx, "world")))))
                .then(Commands.literal("cooldown")
                        .then(Commands.literal("reset")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(onlinePlayerSuggestions())
                                        .executes(ctx -> adminCooldownReset(ctx, null))
                                        .then(Commands.argument("world", StringArgumentType.string())
                                                .suggests(adminWorldSuggestions(Set.of()))
                                                .executes(ctx -> adminCooldownReset(ctx,
                                                        StringArgumentType.getString(ctx, "world")))))));
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> adminWorldArg() {
        return Commands.argument("world", StringArgumentType.string()).suggests(adminWorldSuggestions(Set.of()));
    }

    // =========================================================================================================
    // player commands
    // =========================================================================================================

    private int bare(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNotify(src);
        if (player == null) {
            return 0;
        }
        if (!has(src, PERM_USE)) {
            messages().send(src.getSender(), "general.no-permission");
            return 0;
        }
        if (plugin.gui().isEnabled() && plugin.gui().opensOnBareCommand()) {
            plugin.gui().open(player);
            return Command.SINGLE_SUCCESS;
        }
        watch(plugin.teleports().teleport(TeleportRequest.self(player, null, RtpCause.COMMAND)), player.getName());
        return Command.SINGLE_SUCCESS;
    }

    private int selfWorld(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNotify(src);
        if (player == null) {
            return 0;
        }
        if (!has(src, PERM_USE)) {
            messages().send(src.getSender(), "general.no-permission");
            return 0;
        }
        World world = resolveEnabledWorld(src.getSender(), StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            return 0;
        }
        // World permission, cooldown, cost etc. are checked (and reported) by the teleport service.
        watch(plugin.teleports().teleport(TeleportRequest.self(player, world, RtpCause.COMMAND)), player.getName());
        return Command.SINGLE_SUCCESS;
    }

    private int sendOthers(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        CommandSender sender = src.getSender();
        World world = resolveEnabledWorld(sender, StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            return 0;
        }
        List<Player> targets = ctx.getArgument("targets", PlayerSelectorArgumentResolver.class).resolve(src);
        if (targets.isEmpty()) {
            // Paper's selector resolves an unknown name to an empty list instead of throwing.
            String typed = ctx.getNodes().stream()
                    .filter(n -> n.getNode().getName().equals("targets"))
                    .map(n -> n.getRange().get(ctx.getInput()))
                    .findFirst().orElse("?");
            messages().send(sender, "general.player-not-found", Placeholder.unparsed("player", typed));
            return 0;
        }
        for (Player target : targets) {
            watch(plugin.teleports().teleport(TeleportRequest.forced(target, world, sender, RtpCause.COMMAND_OTHER)),
                    target.getName());
        }
        return targets.size();
    }

    private int gui(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNotify(src);
        if (player == null) {
            return 0;
        }
        if (!plugin.gui().isEnabled()) {
            messages().send(src.getSender(), "command.gui-disabled");
            return 0;
        }
        plugin.gui().open(player);
        return Command.SINGLE_SUCCESS;
    }

    private int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Player player = playerOrNotify(src);
        if (player == null) {
            return 0;
        }
        // On success the teleport service reports the cancellation itself (outcome CANCELLED_OTHER).
        if (!plugin.teleports().cancel(player)) {
            messages().send(src.getSender(), "teleport.nothing-to-cancel");
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private int help(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        messages().send(sender, "command.help");
        if (sender.hasPermission(PERM_ADMIN)) {
            messages().send(sender, "command.help-admin");
        }
        return Command.SINGLE_SUCCESS;
    }

    // =========================================================================================================
    // admin commands
    // =========================================================================================================

    private int adminReload(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        // Services expect reload on the global/main thread, not a player's region thread (Folia).
        plugin.scheduling().global(() -> {
            String error;
            try {
                error = plugin.reloadAll();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Reload failed", e);
                error = String.valueOf(e.getMessage());
            }
            if (error == null) {
                messages().send(sender, "general.reloaded");
            } else {
                messages().send(sender, "general.reload-failed", Placeholder.unparsed("error", error));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private int adminInfo(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        MessageService msg = messages();
        List<String> hooks = plugin.hooks().activeHooks();
        List<String> worlds = enabledLoadedWorlds().stream().map(World::getName).toList();
        ChunkLoader loader = plugin.chunkLoader();
        Component paused = loader.isPaused()
                ? msg.get("admin.info-paused", Placeholder.unparsed("reason", String.valueOf(loader.pauseReason())))
                : msg.get("admin.info-running");
        String none = msg.raw("admin.none") == null ? "none" : msg.raw("admin.none");
        msg.send(sender, "admin.info",
                Placeholder.unparsed("version", plugin.getPluginMeta().getVersion()),
                Placeholder.unparsed("platform", Scheduling.isFolia() ? "Folia" : "Paper"),
                Placeholder.unparsed("hooks", hooks.isEmpty() ? none : String.join(", ", hooks)),
                Placeholder.unparsed("worlds", worlds.isEmpty() ? none : String.join(", ", worlds)),
                Placeholder.unparsed("inflight", Integer.toString(loader.inFlight())),
                Placeholder.unparsed("queued", Integer.toString(loader.queued())),
                Placeholder.unparsed("loaded", Long.toString(loader.totalLoaded())),
                Placeholder.component("paused", paused));
        return Command.SINGLE_SUCCESS;
    }

    private int adminPool(CommandContext<CommandSourceStack> ctx, @Nullable String worldName) {
        CommandSender sender = ctx.getSource().getSender();
        List<PoolStats> stats;
        if (worldName == null) {
            stats = plugin.pools().allStats();
        } else {
            World world = resolveEnabledWorld(sender, worldName);
            if (world == null) {
                return 0;
            }
            stats = List.of(plugin.pools().stats(world));
        }
        if (stats.isEmpty()) {
            messages().send(sender, "admin.pool-empty-list");
            return 0;
        }
        messages().send(sender, "admin.pool-header");
        for (PoolStats s : stats) {
            sendPoolLine(sender, s);
        }
        return stats.size();
    }

    private void sendPoolLine(CommandSender sender, PoolStats s) {
        MessageService msg = messages();
        Component status = msg.get(PoolBar.statusKey(s), Placeholder.unparsed("reason", String.valueOf(s.pauseReason())));
        msg.send(sender, "admin.pool-line",
                Placeholder.unparsed("world", s.worldName()),
                Placeholder.parsed("bar", PoolBar.render(s.fillRatio())),
                Placeholder.unparsed("size", Integer.toString(s.size())),
                Placeholder.unparsed("target", Integer.toString(s.target())),
                Placeholder.unparsed("searching", Integer.toString(s.searching())),
                Placeholder.unparsed("found", Long.toString(s.totalFound())),
                Placeholder.unparsed("served", Long.toString(s.totalServed())),
                Placeholder.unparsed("failed", Long.toString(s.totalFailedSearches())),
                Placeholder.unparsed("expired", Long.toString(s.totalExpired())),
                Placeholder.unparsed("avg_ms", String.format(Locale.ROOT, "%.1f", s.avgMillisPerFind())),
                Placeholder.unparsed("avg_attempts", String.format(Locale.ROOT, "%.1f", s.avgAttemptsPerFind())),
                Placeholder.unparsed("chunks", Integer.toString(s.ticketedChunks())),
                Placeholder.component("status", status));
    }

    /** {@code clear == true}: drop the pool (refills afterwards); false: refill immediately. */
    private int adminPoolClear(CommandContext<CommandSourceStack> ctx, boolean clear) {
        CommandSender sender = ctx.getSource().getSender();
        World world = resolveEnabledWorld(sender, StringArgumentType.getString(ctx, "world"));
        if (world == null) {
            return 0;
        }
        if (clear) {
            plugin.pools().clear(world);
            plugin.pools().refillNow(world);
            messages().send(sender, "admin.pool-cleared", Placeholder.unparsed("world", world.getName()));
        } else {
            plugin.pools().refillNow(world);
            messages().send(sender, "admin.pool-refilling", Placeholder.unparsed("world", world.getName()));
        }
        return Command.SINGLE_SUCCESS;
    }

    private int adminCooldownReset(CommandContext<CommandSourceStack> ctx, @Nullable String worldArg) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "player");
        OfflinePlayer target = Bukkit.getPlayerExact(name);
        if (target == null) {
            target = Bukkit.getOfflinePlayerIfCached(name);
        }
        if (target == null) {
            messages().send(sender, "general.player-not-found", Placeholder.unparsed("player", name));
            return 0;
        }
        String worldName = null;
        if (worldArg != null) {
            // Canonicalise against loaded/configured worlds, but allow unloaded names (cooldowns are keyed by name).
            List<String> known = new ArrayList<>(Bukkit.getWorlds().stream().map(World::getName).toList());
            PluginSettings settings = plugin.settings();
            if (settings != null) {
                known.addAll(settings.worlds().keySet());
            }
            String resolved = WorldNames.resolve(worldArg, known);
            worldName = resolved != null ? resolved : worldArg;
        }
        plugin.cooldowns().reset(target.getUniqueId(), worldName);
        String display = target.getName() != null ? target.getName() : name;
        Component worldDisplay = worldName != null ? Component.text(worldName) : messages().get("admin.all-worlds");
        messages().send(sender, "admin.cooldown-reset",
                Placeholder.unparsed("player", display),
                Placeholder.component("world", worldDisplay));
        return Command.SINGLE_SUCCESS;
    }

    // =========================================================================================================
    // suggestions
    // =========================================================================================================

    /** Worlds the sender may RTP into (enabled + permission). Senders with primedrtp.others see every enabled world. */
    private SuggestionProvider<CommandSourceStack> playerWorldSuggestions() {
        return (ctx, builder) -> {
            CommandSender sender = ctx.getSource().getSender();
            boolean all = sender.hasPermission(PERM_OTHERS);
            List<String> names = selectableWorlds(all ? perm -> true
                    : perm -> io.github.editid0.primedrtp.Permissions.canUseWorld(sender, perm));
            for (String name : names) {
                if (WorldNames.matchesPrefix(name, builder.getRemaining())) {
                    builder.suggest(WorldNames.suggestionText(name, RESERVED));
                }
            }
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> adminWorldSuggestions(Set<String> reserved) {
        return (ctx, builder) -> {
            for (String name : selectableWorlds(perm -> true)) {
                if (WorldNames.matchesPrefix(name, builder.getRemaining())) {
                    builder.suggest(WorldNames.suggestionText(name, reserved));
                }
            }
            return builder.buildFuture();
        };
    }

    private static SuggestionProvider<CommandSourceStack> onlinePlayerSuggestions() {
        return (ctx, builder) -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (WorldNames.matchesPrefix(p.getName(), builder.getRemaining())) {
                    builder.suggest(p.getName());
                }
            }
            return builder.buildFuture();
        };
    }

    private List<String> selectableWorlds(java.util.function.Predicate<String> hasPermission) {
        PluginSettings settings = plugin.settings();
        if (settings == null) {
            return List.of();
        }
        List<String> loaded = Bukkit.getWorlds().stream().map(World::getName).toList();
        return WorldNames.selectable(loaded,
                name -> settings.world(name).map(ws -> new WorldNames.Access(ws.enabled(), ws.permission())),
                hasPermission);
    }

    // =========================================================================================================
    // helpers
    // =========================================================================================================

    private List<World> enabledLoadedWorlds() {
        PluginSettings settings = plugin.settings();
        if (settings == null) {
            return List.of();
        }
        return Bukkit.getWorlds().stream()
                .filter(w -> settings.world(w.getName()).map(WorldSettings::enabled).orElse(false))
                .toList();
    }

    /**
     * Resolves a loaded world (exact name, else unique case-insensitive match) that is RTP-enabled. Sends
     * {@code general.world-not-found} / {@code general.world-disabled} and returns null otherwise.
     */
    private @Nullable World resolveEnabledWorld(CommandSender sender, String typed) {
        List<String> loaded = Bukkit.getWorlds().stream().map(World::getName).toList();
        String name = WorldNames.resolve(typed, loaded);
        World world = name == null ? null : Bukkit.getWorld(name);
        if (world == null) {
            messages().send(sender, "general.world-not-found", Placeholder.unparsed("world", typed));
            return null;
        }
        PluginSettings settings = plugin.settings();
        Optional<WorldSettings> ws = settings == null ? Optional.empty() : settings.world(world.getName());
        if (ws.isEmpty() || !ws.get().enabled()) {
            messages().send(sender, "general.world-disabled", Placeholder.unparsed("world", world.getName()));
            return null;
        }
        return world;
    }

    /**
     * The player the command acts on: the executor if it is a player (supports {@code /execute as}), else the sender.
     * Sends {@code general.player-only} and returns null for console/command blocks.
     */
    private @Nullable Player playerOrNotify(CommandSourceStack src) {
        if (src.getExecutor() instanceof Player p) {
            return p;
        }
        if (src.getSender() instanceof Player p) {
            return p;
        }
        messages().send(src.getSender(), "general.player-only");
        return null;
    }

    private static boolean has(CommandSourceStack src, String permission) {
        return src.getSender().hasPermission(permission);
    }

    private MessageService messages() {
        return plugin.messages();
    }

    /** Teleport futures are fire-and-forget (the service messages everyone); only log unexpected failures. */
    private void watch(CompletableFuture<TeleportOutcome> future, String who) {
        future.whenComplete((outcome, error) -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Random teleport of " + who + " failed unexpectedly", error);
            } else if (outcome != null) {
                plugin.debug("RTP " + who + " -> " + outcome.status());
            }
        });
    }
}
