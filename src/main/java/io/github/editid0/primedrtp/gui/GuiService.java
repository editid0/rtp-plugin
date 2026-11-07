package io.github.editid0.primedrtp.gui;

import io.github.editid0.primedrtp.Service;
import org.bukkit.entity.Player;

/** Configurable world-selector menu (gui.yml). */
public interface GuiService extends Service {

    /** True if gui.yml enables the menu. */
    boolean isEnabled();

    /** True if plain "/rtp" should open the menu instead of teleporting. */
    boolean opensOnBareCommand();

    /** Opens the menu (hops to the player's thread itself). */
    void open(Player player);
}
