package io.github.editid0.primedrtp.hook.papi;

import io.github.editid0.primedrtp.PrimedRTP;
import io.github.editid0.primedrtp.hook.PlaceholderRequest;
import io.github.editid0.primedrtp.hook.PlaceholderValues;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * PlaceholderAPI expansion {@code %primedrtp_*%}. Only loaded once PlaceholderAPI is confirmed enabled.
 * See {@link PlaceholderRequest} for the syntax.
 */
public final class PrimedRtpExpansion extends PlaceholderExpansion {

    private final PrimedRTP plugin;
    private final PlaceholderValues values;

    public PrimedRtpExpansion(PrimedRTP plugin) {
        this.plugin = plugin;
        this.values = new PlaceholderValues(plugin);
    }

    @Override
    public String getIdentifier() {
        return "primedrtp";
    }

    @Override
    public String getAuthor() {
        List<String> authors = plugin.getPluginMeta().getAuthors();
        return authors.isEmpty() ? "unknown" : String.join(", ", authors);
    }

    @Override
    public String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        // Survive /papi reload; we unregister ourselves on disable.
        return true;
    }

    @Override
    public List<String> getPlaceholders() {
        return PlaceholderRequest.documented();
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        PlaceholderRequest request = PlaceholderRequest.parse(params);
        if (request == null) {
            return null;
        }
        Player online = player == null ? null : player.getPlayer();
        try {
            String value = values.resolve(online, request);
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            plugin.debug("Placeholder %primedrtp_" + params + "% failed: " + e);
            return "";
        }
    }
}
