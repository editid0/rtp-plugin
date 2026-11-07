package io.github.editid0.swiftrtp.hook.vault;

import io.github.editid0.swiftrtp.hook.EconomyHook;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jspecify.annotations.Nullable;

/**
 * {@link EconomyHook} over Vault's {@link Economy} service. Only loaded once Vault is confirmed enabled.
 * Instances are bound to one provider; {@link #lookup()} returns a fresh hook for the current highest-priority one.
 */
public final class VaultEconomyHook implements EconomyHook {

    private final Economy economy;

    private VaultEconomyHook(Economy economy) {
        this.economy = economy;
    }

    /** Current Vault economy, or null if no economy plugin registered one (yet). */
    public static @Nullable VaultEconomyHook lookup() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return null;
        }
        Economy economy = rsp.getProvider();
        return economy == null ? null : new VaultEconomyHook(economy);
    }

    /** True if this hook still wraps the registered provider. */
    public boolean isCurrent() {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        return rsp != null && rsp.getProvider() == economy;
    }

    @Override
    public String name() {
        return economy.getName();
    }

    @Override
    public boolean has(Player player, double amount) {
        return amount <= 0 || economy.has(player, amount);
    }

    @Override
    public boolean withdraw(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    @Override
    public void deposit(Player player, double amount) {
        if (amount > 0) {
            economy.depositPlayer(player, amount);
        }
    }

    @Override
    public String format(double amount) {
        try {
            String formatted = economy.format(amount);
            return formatted == null ? fallbackFormat(amount) : formatted;
        } catch (RuntimeException e) {
            return fallbackFormat(amount);
        }
    }

    static String fallbackFormat(double amount) {
        return amount == Math.rint(amount) ? Long.toString((long) amount) : String.format(java.util.Locale.ROOT, "%.2f", amount);
    }
}
