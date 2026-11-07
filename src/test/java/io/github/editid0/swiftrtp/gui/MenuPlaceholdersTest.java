package io.github.editid0.swiftrtp.gui;

import io.github.editid0.swiftrtp.gui.MenuPlaceholders.State;
import io.github.editid0.swiftrtp.gui.MenuPlaceholders.WorldView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuPlaceholdersTest {

    private static final GuiConfig.Texts TEXTS =
            GuiConfigParser.parse(new YamlConfiguration(), null, w -> {}).texts();

    private static String render(String template, WorldView v) {
        return PlainTextComponentSerializer.plainText().serialize(MenuText.render(template, MenuPlaceholders.world(v, TEXTS)));
    }

    @Test
    void fillsAllPlaceholders() {
        WorldView v = new WorldView("world_nether", "Steve", State.COOLDOWN, "1m 5s", "5m", Component.text("$10"),
                3, 10, 0.3, 5000, 100, "3s");
        assertEquals("world_nether Steve On cooldown 1m 5s 5m $10 3/10 30% 5000 100 3s",
                render("<world> <player> <status> <cooldown> <cooldown_length> <cost> <pool_size>/<pool_target> "
                        + "<pool_percent>% <radius> <min_radius> <warmup>", v));
        assertEquals("|".repeat(10), render("<pool_bar>", v));
    }

    @Test
    void worldNameIsNotParsedAsMiniMessage() {
        WorldView v = new WorldView("<red>evil", "p", State.READY, "Ready", "None", Component.empty(), 0, 0, 0, 0, 0, "0s");
        assertEquals("<red>evil Ready", render("<world> <status>", v));
    }

    @Test
    void statusTemplates() {
        assertEquals(TEXTS.statusLocked(), MenuPlaceholders.statusTemplate(State.LOCKED, TEXTS));
        assertEquals(TEXTS.statusReady(), MenuPlaceholders.statusTemplate(State.READY, TEXTS));
    }
}
