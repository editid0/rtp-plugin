package io.github.editid0.swiftrtp;

import io.github.editid0.swiftrtp.config.WorldSettings;
import org.bukkit.permissions.Permissible;
import org.jspecify.annotations.Nullable;

/** Shared permission checks so the command, GUI and teleport flow always agree. */
public final class Permissions {

    /** Grants every world permission. Bukkit doesn't expand wildcards itself, so it is checked explicitly. */
    public static final String ALL_WORLDS = "swiftrtp.world.*";

    private Permissions() {}

    /** True if the world needs no permission, or the subject has it (or {@link #ALL_WORLDS}). */
    public static boolean canUseWorld(Permissible subject, WorldSettings world) {
        return canUseWorld(subject, world.permission());
    }

    public static boolean canUseWorld(Permissible subject, @Nullable String permission) {
        return permission == null || permission.isBlank()
                || subject.hasPermission(permission) || subject.hasPermission(ALL_WORLDS);
    }
}
