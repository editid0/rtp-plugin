package io.github.editid0.swiftrtp.hook;

import org.bukkit.entity.Player;

/** Economy bridge (Vault). Call on the player's thread. */
public interface EconomyHook {
    String name();

    boolean has(Player player, double amount);

    /** Returns false if the withdrawal failed (nothing taken). */
    boolean withdraw(Player player, double amount);

    void deposit(Player player, double amount);

    String format(double amount);
}
