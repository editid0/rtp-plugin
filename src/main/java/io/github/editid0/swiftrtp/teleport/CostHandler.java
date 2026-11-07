package io.github.editid0.swiftrtp.teleport;

import io.github.editid0.swiftrtp.SwiftRTP;
import io.github.editid0.swiftrtp.config.MessageService;
import io.github.editid0.swiftrtp.config.WorldSettings.CostSettings;
import io.github.editid0.swiftrtp.config.WorldSettings.ItemCost;
import io.github.editid0.swiftrtp.hook.EconomyHook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Checks, charges and refunds {@link CostSettings} (Vault money, XP levels, items). Items are matched by material
 * only (any meta). Money is ignored when no economy is hooked. All methods touching a player must run on that
 * player's thread.
 */
final class CostHandler {

    /** What was actually taken from a player, so it can be refunded exactly. */
    record Receipt(double money, int xpLevels, List<ItemCost> items) {
        static final Receipt EMPTY = new Receipt(0, 0, List.of());

        boolean isEmpty() {
            return money <= 0 && xpLevels <= 0 && items.isEmpty();
        }
    }

    private final SwiftRTP plugin;

    CostHandler(SwiftRTP plugin) {
        this.plugin = plugin;
    }

    /** The cost that applies right now (money dropped when no economy is hooked). */
    Receipt effectiveCost(CostSettings cost) {
        double money = cost.money();
        if (money > 0 && plugin.hooks().economy().isEmpty()) {
            plugin.debug("Ignoring money cost " + money + ": no economy plugin hooked.");
            money = 0;
        }
        List<ItemCost> items = new ArrayList<>();
        for (ItemCost item : mergeItems(cost.items())) {
            if (item.amount() > 0) {
                items.add(item);
            }
        }
        return new Receipt(Math.max(0, money), Math.max(0, cost.xpLevels()), List.copyOf(items));
    }

    boolean canAfford(Player player, Receipt cost) {
        if (cost.money() > 0) {
            Optional<EconomyHook> eco = plugin.hooks().economy();
            if (eco.isPresent() && !eco.get().has(player, cost.money())) {
                return false;
            }
        }
        if (cost.xpLevels() > 0 && player.getLevel() < cost.xpLevels()) {
            return false;
        }
        PlayerInventory inv = player.getInventory();
        for (ItemCost item : cost.items()) {
            if (count(inv, item.material()) < item.amount()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Verifies and takes the cost. Returns the receipt, or null if the player can no longer afford it (nothing is
     * taken in that case).
     */
    @Nullable Receipt charge(Player player, Receipt cost) {
        if (cost.isEmpty()) {
            return Receipt.EMPTY;
        }
        if (!canAfford(player, cost)) {
            return null;
        }
        double money = 0;
        if (cost.money() > 0) {
            Optional<EconomyHook> eco = plugin.hooks().economy();
            if (eco.isPresent()) {
                if (!eco.get().withdraw(player, cost.money())) {
                    return null;
                }
                money = cost.money();
            }
        }
        if (cost.xpLevels() > 0) {
            player.setLevel(player.getLevel() - cost.xpLevels());
        }
        PlayerInventory inv = player.getInventory();
        for (ItemCost item : cost.items()) {
            remove(inv, item.material(), item.amount());
        }
        return new Receipt(money, cost.xpLevels(), cost.items());
    }

    /** Gives back everything on the receipt. Items that don't fit are dropped at the player's feet. */
    void refund(Player player, Receipt receipt) {
        if (receipt.isEmpty()) {
            return;
        }
        try {
            if (receipt.money() > 0) {
                plugin.hooks().economy().ifPresentOrElse(eco -> eco.deposit(player, receipt.money()),
                        () -> plugin.getLogger().warning("Could not refund " + receipt.money() + " to "
                                + player.getName() + ": economy plugin is gone."));
            }
            if (receipt.xpLevels() > 0) {
                player.giveExpLevels(receipt.xpLevels());
            }
            for (ItemCost item : receipt.items()) {
                giveItems(player, item.material(), item.amount());
            }
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Refund to " + player.getName() + " failed", e);
        }
    }

    /** Renders a cost as {@code cost-money / cost-xp / cost-item} fragments joined with {@code cost-separator}. */
    Component describe(Receipt cost) {
        MessageService messages = plugin.messages();
        List<Component> parts = new ArrayList<>(2 + cost.items().size());
        if (cost.money() > 0) {
            String formatted = plugin.hooks().economy().map(eco -> eco.format(cost.money()))
                    .orElseGet(() -> String.format(java.util.Locale.ROOT, "%.2f", cost.money()));
            parts.add(messages.get("teleport.cost-money", Placeholder.unparsed("amount", formatted)));
        }
        if (cost.xpLevels() > 0) {
            parts.add(messages.get("teleport.cost-xp", Placeholder.unparsed("amount", Integer.toString(cost.xpLevels()))));
        }
        for (ItemCost item : cost.items()) {
            parts.add(messages.get("teleport.cost-item",
                    Placeholder.unparsed("amount", Integer.toString(item.amount())),
                    Placeholder.component("item", Component.translatable(item.material()))));
        }
        return Component.join(JoinConfiguration.separator(messages.get("teleport.cost-separator")), parts);
    }

    // ---- inventory helpers -----------------------------------------------------------------------------------

    static List<ItemCost> mergeItems(List<ItemCost> items) {
        // Insertion-ordered; the old "merge result equals amount" first-seen test duplicated a material whose
        // earlier entries summed to 0 (e.g. STONE x0 then STONE x3 charged 6).
        Map<Material, Integer> merged = new LinkedHashMap<>();
        for (ItemCost item : items) {
            merged.merge(item.material(), item.amount(), Integer::sum);
        }
        List<ItemCost> out = new ArrayList<>(merged.size());
        for (Map.Entry<Material, Integer> e : merged.entrySet()) {
            out.add(new ItemCost(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static int count(PlayerInventory inv, Material material) {
        int total = 0;
        for (ItemStack stack : inv.getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private static void remove(PlayerInventory inv, Material material, int amount) {
        ItemStack[] contents = inv.getStorageContents();
        int left = amount;
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            left -= take;
            if (take >= stack.getAmount()) {
                inv.setItem(slot, null);
            } else {
                ItemStack reduced = stack.clone();
                reduced.setAmount(stack.getAmount() - take);
                inv.setItem(slot, reduced);
            }
        }
    }

    private static void giveItems(Player player, Material material, int amount) {
        int maxStack = Math.max(1, material.getMaxStackSize());
        int left = amount;
        while (left > 0) {
            int n = Math.min(left, maxStack);
            left -= n;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(new ItemStack(material, n));
            for (ItemStack rest : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }
}
