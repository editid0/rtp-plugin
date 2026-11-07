package io.github.editid0.swiftrtp.gui;

import io.github.editid0.swiftrtp.gui.GuiConfig.ItemTemplate;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Turns {@link ItemTemplate}s into item stacks. Invalid materials/keys are warned about once and replaced. */
final class ItemFactory {

    private final Consumer<String> warn;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    ItemFactory(Consumer<String> warn) {
        this.warn = warn;
    }

    /** Forget one-time warnings (after a reload). */
    void resetWarnings() {
        warned.clear();
    }

    /**
     * Builds the item.
     *
     * @return null when the template's material is AIR (empty slot)
     */
    @Nullable ItemStack build(ItemTemplate t, TagResolver resolver) {
        Material material = resolveMaterial(t.material());
        if (material == null) {
            return null;
        }
        ItemStack item = ItemStack.of(material, t.amount());
        item.editMeta(meta -> {
            if (!t.name().isEmpty()) {
                meta.displayName(MenuText.render(t.name(), resolver));
            }
            if (!t.lore().isEmpty()) {
                meta.lore(MenuText.renderLore(t.lore(), resolver));
            }
            if (t.glow()) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (t.customModelData() != null) {
                CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
                cmd.setFloats(List.of(t.customModelData()));
                meta.setCustomModelDataComponent(cmd);
            }
            if (t.itemModel() != null) {
                NamespacedKey key = NamespacedKey.fromString(t.itemModel().toLowerCase(Locale.ROOT));
                if (key == null) {
                    warnOnce("item-model:" + t.itemModel(), "gui.yml: invalid item-model '" + t.itemModel() + "'");
                } else {
                    meta.setItemModel(key);
                }
            }
            if (t.hideTooltip()) {
                meta.setHideTooltip(true);
            }
            meta.addItemFlags(ItemFlag.values());
        });
        return item;
    }

    private @Nullable Material resolveMaterial(String name) {
        Material m = Material.matchMaterial(name);
        if (m != null && m.isAir()) {
            return null;
        }
        if (m == null || !m.isItem()) {
            warnOnce("material:" + name, "gui.yml: '" + name + "' is not a valid item material, using BARRIER");
            return Material.BARRIER;
        }
        return m;
    }

    private void warnOnce(String key, String message) {
        if (warned.add(key)) {
            warn.accept(message);
        }
    }
}
