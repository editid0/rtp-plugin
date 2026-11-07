package io.github.editid0.swiftrtp.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MenuActionTest {

    @Test
    void parsesActions() {
        assertEquals(new MenuAction.Close(), MenuAction.parse("close"));
        assertEquals(new MenuAction.Close(), MenuAction.parse("  CLOSE "));
        assertEquals(new MenuAction.Rtp(null), MenuAction.parse("rtp"));
        assertEquals(new MenuAction.Rtp("world_nether"), MenuAction.parse("rtp:world_nether"));
        assertEquals(new MenuAction.PlayerCommand("spawn"), MenuAction.parse("command:/spawn"));
        assertEquals(new MenuAction.PlayerCommand("warp a:b"), MenuAction.parse("command: warp a:b"));
        assertEquals(new MenuAction.ConsoleCommand("say {player}"), MenuAction.parse("console:say {player}"));
    }

    @Test
    void rejectsInvalid() {
        assertNull(MenuAction.parse(null));
        assertNull(MenuAction.parse(""));
        assertNull(MenuAction.parse("explode"));
        assertNull(MenuAction.parse("command:"));
        assertNull(MenuAction.parse("console:  "));
    }

    @Test
    void expandsPlaceholders() {
        assertEquals("give Steve 1 u w", MenuAction.expand("give {player} 1 {uuid} {world}", "Steve", "u", "w"));
    }
}
