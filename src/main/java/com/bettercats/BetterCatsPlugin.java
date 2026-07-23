package com.bettercats;

import com.bettercats.cat.CatCombatController;
import com.bettercats.cat.CatItemManager;
import com.bettercats.listener.CatListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * BetterCats - Phase 1.
 *
 * <p>This pass covers only: tamed cats defending/joining their owner in a
 * fight, fox-style pouncing, a flat placeholder bite damage, the Scratch
 * debuff on a landed hit, and the item-holding mechanic (visual + storage).
 * Sword/Flint&amp;Steel/Wind Charge/Fire Charge support are deliberately left
 * for separate follow-up passes - see the README.</p>
 */
public final class BetterCatsPlugin extends JavaPlugin {

    private CatItemManager itemManager;
    private CatCombatController combatController;

    @Override
    public void onEnable() {
        this.itemManager = new CatItemManager(this);
        this.combatController = new CatCombatController(this, itemManager);

        getServer().getPluginManager().registerEvents(new CatListener(combatController, itemManager), this);

        getServer().getScheduler().runTaskTimer(this, combatController::tick, 0L, 4L);

        getLogger().info("BetterCats loaded (Phase 1: base combat AI + item-holding).");
    }
}
