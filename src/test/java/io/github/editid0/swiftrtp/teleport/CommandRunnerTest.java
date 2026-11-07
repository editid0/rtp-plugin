package io.github.editid0.swiftrtp.teleport;

import io.github.editid0.swiftrtp.teleport.CommandRunner.Executor;
import io.github.editid0.swiftrtp.teleport.CommandRunner.ParsedCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CommandRunnerTest {

    private static final Map<String, String> PH = CommandRunner.placeholders("Steve",
            "00000000-0000-0000-0000-000000000001", "world", 10, 64, -20);

    @Test
    void parsesPrefixes() {
        assertEquals(new ParsedCommand(Executor.CONSOLE, "say hi"), CommandRunner.parse("console: say hi"));
        assertEquals(new ParsedCommand(Executor.PLAYER, "spawn"), CommandRunner.parse("player: /spawn"));
        assertEquals(new ParsedCommand(Executor.OP, "gamemode survival"), CommandRunner.parse("OP:gamemode survival"));
        assertEquals(new ParsedCommand(Executor.CONSOLE, "say hi"), CommandRunner.parse("/say hi"));
        // Unknown prefix: whole line is the console command.
        assertEquals(new ParsedCommand(Executor.CONSOLE, "minecraft:give x"), CommandRunner.parse("minecraft:give x"));
        assertNull(CommandRunner.parse("   "));
        assertNull(CommandRunner.parse("player:  "));
        assertNull(CommandRunner.parse(null));
    }

    @Test
    void replacesPlaceholders() {
        assertEquals("tp Steve 10 64 -20 in world (00000000-0000-0000-0000-000000000001)",
                CommandRunner.applyPlaceholders("tp {player} {x} {y} {z} in {world} ({uuid})", PH));
    }

    @Test
    void leavesUnknownAndBrokenBracesAlone() {
        assertEquals("say {unknown} Steve {", CommandRunner.applyPlaceholders("say {unknown} {player} {", PH));
        assertEquals("plain", CommandRunner.applyPlaceholders("plain", PH));
    }

    @Test
    void valuesAreNotRescanned() {
        Map<String, String> ph = Map.of("player", "{x}", "x", "1");
        assertEquals("{x} 1", CommandRunner.applyPlaceholders("{player} {x}", ph));
    }

    @Test
    void prepareSkipsBlankAndResolves() {
        List<ParsedCommand> out = CommandRunner.prepare(List.of("console: give {player} bread", "", "player: me {world}"), PH);
        assertEquals(List.of(new ParsedCommand(Executor.CONSOLE, "give Steve bread"),
                new ParsedCommand(Executor.PLAYER, "me world")), out);
    }
}
