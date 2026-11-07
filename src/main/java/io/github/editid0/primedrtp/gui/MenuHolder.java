package io.github.editid0.primedrtp.gui;

import io.github.editid0.primedrtp.gui.GuiConfig.StaticButton;
import io.github.editid0.primedrtp.gui.GuiConfig.WorldButton;
import io.github.editid0.primedrtp.gui.MenuPlaceholders.State;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Identifies an open RTP menu and carries its per-viewer state. Everything except {@link #cancelRefresh()} is only
 * touched on the viewer's entity thread.
 */
final class MenuHolder implements InventoryHolder {

    /** What a slot does when clicked. */
    sealed interface Target permits WorldTarget, ButtonTarget {}

    record WorldTarget(WorldButton button, State state) implements Target {}

    record ButtonTarget(StaticButton button) implements Target {}

    final GuiConfig config;
    final UUID viewer;
    private @Nullable Inventory inventory;
    final Map<Integer, Target> targets = new HashMap<>();
    /** Set once a teleport/close action ran, so double clicks don't fire twice. */
    boolean actionTaken;
    private volatile @Nullable ScheduledTask refreshTask;

    MenuHolder(GuiConfig config, UUID viewer) {
        this.config = config;
        this.viewer = viewer;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("menu inventory not created yet");
        }
        return inventory;
    }

    void refreshTask(@Nullable ScheduledTask task) {
        this.refreshTask = task;
    }

    void cancelRefresh() {
        ScheduledTask task = refreshTask;
        refreshTask = null;
        if (task != null) {
            task.cancel();
        }
    }
}
