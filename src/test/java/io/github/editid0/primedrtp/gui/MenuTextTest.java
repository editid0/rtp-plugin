package io.github.editid0.primedrtp.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MenuTextTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void renderDisablesItalicUnlessExplicit() {
        Component c = MenuText.render("<green>Hi", TagResolver.empty());
        assertEquals(TextDecoration.State.FALSE, c.decoration(TextDecoration.ITALIC));
        Component i = MenuText.render("<italic>Hi", TagResolver.empty());
        assertEquals("Hi", plain(i));
        assertEquals(TextDecoration.State.TRUE, i.children().isEmpty()
                ? i.decoration(TextDecoration.ITALIC) : i.children().getFirst().decoration(TextDecoration.ITALIC));
    }

    @Test
    void progressBar() {
        assertEquals("<green>|||||</green><gray>|||||</gray>", MenuText.progressBar(0.5, 10, "|", "green", "gray"));
        assertEquals("<green>|||</green>", MenuText.progressBar(1.5, 3, "|", "green", "gray"));
        assertEquals("<gray>|||</gray>", MenuText.progressBar(Double.NaN, 3, "|", "green", "gray"));
        assertEquals("<gray>|</gray>", MenuText.progressBar(0, 0, "", "green", "gray"));
        // Symbols with tags are escaped.
        assertEquals("<a>\\<b></a>", MenuText.progressBar(1, 1, "<b>", "a", "c"));
    }

    @Test
    void percentAndNames() {
        assertEquals(50, MenuText.percent(0.5));
        assertEquals(100, MenuText.percent(2));
        assertEquals(0, MenuText.percent(-1));
        assertEquals("Oak Log", MenuText.prettyName("OAK_LOG"));
        assertEquals("Diamond", MenuText.prettyName("minecraft:diamond"));
    }
}
