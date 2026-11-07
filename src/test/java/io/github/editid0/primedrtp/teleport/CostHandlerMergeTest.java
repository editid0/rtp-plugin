package io.github.editid0.primedrtp.teleport;

import io.github.editid0.primedrtp.config.WorldSettings.ItemCost;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostHandlerMergeTest {

    @Test
    void mergesSameMaterialKeepingFirstSeenOrder() {
        List<ItemCost> merged = CostHandler.mergeItems(List.of(
                new ItemCost(Material.DIAMOND, 2), new ItemCost(Material.STONE, 1), new ItemCost(Material.DIAMOND, 3)));
        assertEquals(List.of(new ItemCost(Material.DIAMOND, 5), new ItemCost(Material.STONE, 1)), merged);
    }

    @Test
    void zeroAmountEntryDoesNotDuplicateMaterial() {
        // Previously STONE x0 followed by STONE x3 produced two STONE x3 entries (charged 6).
        List<ItemCost> merged = CostHandler.mergeItems(List.of(
                new ItemCost(Material.STONE, 0), new ItemCost(Material.STONE, 3)));
        assertEquals(List.of(new ItemCost(Material.STONE, 3)), merged);
    }
}
