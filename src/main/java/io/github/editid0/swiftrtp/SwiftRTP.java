package io.github.editid0.swiftrtp;

import io.github.editid0.swiftrtp.api.SwiftRTPApi;
import io.github.editid0.swiftrtp.command.RtpCommands;
import io.github.editid0.swiftrtp.config.ConfigException;
import io.github.editid0.swiftrtp.config.ConfigLoader;
import io.github.editid0.swiftrtp.config.MessageService;
import io.github.editid0.swiftrtp.config.PluginSettings;
import io.github.editid0.swiftrtp.config.YamlMessageService;
import io.github.editid0.swiftrtp.gui.GuiService;
import io.github.editid0.swiftrtp.gui.RtpMenu;
import io.github.editid0.swiftrtp.hook.DefaultHookManager;
import io.github.editid0.swiftrtp.hook.HookManager;
import io.github.editid0.swiftrtp.listener.SignListener;
import io.github.editid0.swiftrtp.listener.TriggerListener;
import io.github.editid0.swiftrtp.pool.DefaultPoolManager;
import io.github.editid0.swiftrtp.pool.PoolManager;
import io.github.editid0.swiftrtp.scheduler.Scheduling;
import io.github.editid0.swiftrtp.search.ChunkLoader;
import io.github.editid0.swiftrtp.search.DefaultChunkLoader;
import io.github.editid0.swiftrtp.search.DefaultLocationSearcher;
import io.github.editid0.swiftrtp.search.LocationSearcher;
import io.github.editid0.swiftrtp.teleport.CooldownService;
import io.github.editid0.swiftrtp.teleport.DefaultCooldownService;
import io.github.editid0.swiftrtp.teleport.DefaultTeleportService;
import io.github.editid0.swiftrtp.teleport.TeleportService;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Plugin entry point and service locator. Services obtain each other through these getters (never in constructors).
 */
public final class SwiftRTP extends JavaPlugin {

    private volatile PluginSettings settings;
    private Scheduling scheduling;
    private MessageService messages;
    private HookManager hooks;
    private ChunkLoader chunkLoader;
    private LocationSearcher searcher;
    private PoolManager pools;
    private CooldownService cooldowns;
    private TeleportService teleports;
    private GuiService gui;

    /** Start order; stopped in reverse. */
    private final List<Service> services = new ArrayList<>();

    @Override
    public void onEnable() {
        scheduling = new Scheduling(this);
        try {
            settings = new ConfigLoader(this).load();
        } catch (ConfigException e) {
            getLogger().severe("config.yml is invalid, SwiftRTP will not enable: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        messages = new YamlMessageService(this);
        hooks = new DefaultHookManager(this);
        chunkLoader = new DefaultChunkLoader(this);
        searcher = new DefaultLocationSearcher(this);
        pools = new DefaultPoolManager(this);
        cooldowns = new DefaultCooldownService(this);
        teleports = new DefaultTeleportService(this);
        gui = new RtpMenu(this);
        services.addAll(List.of(messages, hooks, chunkLoader, searcher, pools, cooldowns, teleports, gui));

        for (Service service : services) {
            try {
                service.start();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Failed to start " + service.getClass().getSimpleName(), e);
            }
        }

        var pm = getServer().getPluginManager();
        pm.registerEvents(new TriggerListener(this), this);
        pm.registerEvents(new SignListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> new RtpCommands(this).register(event.registrar()));

        Bukkit.getServicesManager().register(SwiftRTPApi.class, new ApiImpl(this), this, ServicePriority.Normal);

        getLogger().info("SwiftRTP enabled (" + (Scheduling.isFolia() ? "Folia" : "Paper") + ", "
                + settings.worlds().size() + " configured world(s)).");
    }

    @Override
    public void onDisable() {
        for (int i = services.size() - 1; i >= 0; i--) {
            Service service = services.get(i);
            try {
                service.stop();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Failed to stop " + service.getClass().getSimpleName(), e);
            }
        }
        services.clear();
        Bukkit.getServicesManager().unregisterAll(this);
        if (scheduling != null) {
            scheduling.cancelAll();
        }
    }

    /**
     * Re-reads config.yml (and messages via the message service) and notifies all services. On failure the previous
     * settings stay active.
     *
     * @return null on success, otherwise the error message
     */
    public synchronized String reloadAll() {
        PluginSettings fresh;
        try {
            fresh = new ConfigLoader(this).load();
        } catch (ConfigException e) {
            getLogger().warning("Reload failed, keeping previous config: " + e.getMessage());
            return e.getMessage();
        }
        settings = fresh;
        for (Service service : services) {
            try {
                service.reload();
            } catch (RuntimeException e) {
                getLogger().log(Level.SEVERE, "Failed to reload " + service.getClass().getSimpleName(), e);
            }
        }
        return null;
    }

    /** Logs only when {@code debug: true}. */
    public void debug(String message) {
        PluginSettings s = settings;
        if (s != null && s.debug()) {
            getLogger().info("[debug] " + message);
        }
    }

    public PluginSettings settings() { return settings; }
    public Scheduling scheduling() { return scheduling; }
    public MessageService messages() { return messages; }
    public HookManager hooks() { return hooks; }
    public ChunkLoader chunkLoader() { return chunkLoader; }
    public LocationSearcher searcher() { return searcher; }
    public PoolManager pools() { return pools; }
    public CooldownService cooldowns() { return cooldowns; }
    public TeleportService teleports() { return teleports; }
    public GuiService gui() { return gui; }
}
