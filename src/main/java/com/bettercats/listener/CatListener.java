package com.bettercats.listener;

import com.bettercats.cat.CatCombatController;
import com.bettercats.cat.CatItemManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class CatListener implements Listener {

    private final CatCombatController combat;
    private final CatItemManager items;

    public CatListener(CatCombatController combat, CatItemManager items) {
        this.combat = combat;
        this.items = items;
    }

    /** Shift + right-click a tamed cat you own: give it your held item, or take back what it's holding. */
    @EventHandler(ignoreCancelled = true)
    public void onInteractCat(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!(event.getRightClicked() instanceof Cat cat)) {
            return;
        }
        Player player = event.getPlayer();
        if (!cat.isTamed() || !player.equals(cat.getOwner()) || !player.isSneaking()) {
            return;
        }

        event.setCancelled(true);

        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem.getType().isAir()) {
            ItemStack taken = items.takeItem(cat);
            if (taken != null) {
                player.getInventory().setItemInMainHand(taken);
                player.sendActionBar(Component.text("Took the item back.", NamedTextColor.GRAY));
            }
        } else {
            items.giveItem(cat, handItem);
            if (handItem.getAmount() <= 0) {
                player.getInventory().setItemInMainHand(null);
            }
            player.sendActionBar(Component.text("Your cat is holding it now.", NamedTextColor.GRAY));
        }
    }

    /** Owner takes damage -> nearby owned cats defend them by targeting the attacker. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player owner)) {
            return;
        }
        LivingEntity attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.equals(owner)) {
            return;
        }

        List<Cat> cats = combat.findNearbyOwnedCats(owner);
        for (Cat cat : cats) {
            combat.acquireTarget(cat, attacker);
        }
    }

    /** Owner attacks something -> nearby owned cats join in by targeting the same victim. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOwnerAttacks(EntityDamageByEntityEvent event) {
        LivingEntity attacker = resolveAttacker(event.getDamager());
        if (!(attacker instanceof Player owner)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }

        List<Cat> cats = combat.findNearbyOwnedCats(owner);
        for (Cat cat : cats) {
            combat.acquireTarget(cat, victim);
        }
    }

    /** Cats drop whatever they're holding on death, and stop being tracked for combat. */
    @EventHandler
    public void onCatDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Cat cat)) {
            return;
        }
        combat.forget(cat.getUniqueId());

        ItemStack held = items.takeItem(cat);
        if (held != null) {
            cat.getWorld().dropItemNaturally(cat.getLocation(), held);
        }
    }

    /** Resolves the "real" attacker behind an EntityDamageByEntityEvent, following projectiles back to their shooter. */
    private LivingEntity resolveAttacker(Entity damager) {
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
            return shooter;
        }
        if (damager instanceof LivingEntity living) {
            return living;
        }
        return null;
    }
}
