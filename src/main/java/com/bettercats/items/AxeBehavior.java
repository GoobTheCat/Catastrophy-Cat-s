package com.bettercats.items;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * Computes a held axe's real damage and applies its on-hit effects.
 *
 * <p>Axes genuinely cannot be enchanted with Fire Aspect, Knockback, Looting,
 * or Sweeping Edge in vanilla Minecraft at all - those are sword-exclusive,
 * confirmed directly against the current wiki page for Axe. So unlike Sword,
 * there's no fire/knockback logic to port over here; the two things that
 * actually differ for axes are the base damage (higher than a sword of the
 * same tier) and the shield-disable mechanic, which is what this class
 * covers.</p>
 *
 * <p>Sharpness, Smite, and Bane of Arthropods CAN go on an axe, same as a
 * sword, so those are handled the same way {@link SwordBehavior} handles
 * them.</p>
 */
public final class AxeBehavior {

    /** Vanilla: an axe hit disables a blocking shield for 5 seconds. */
    private static final int SHIELD_DISABLE_TICKS = 100;

    private static final Set<EntityType> UNDEAD_TYPES = Set.of(
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN, EntityType.WITHER, EntityType.PHANTOM
    );

    private static final Set<EntityType> ARTHROPOD_TYPES = Set.of(
            EntityType.SPIDER, EntityType.CAVE_SPIDER, EntityType.SILVERFISH, EntityType.ENDERMITE
    );

    private AxeBehavior() {
    }

    /** Base attack damage for each vanilla axe tier - notably higher than the same-tier sword. */
    private static double baseDamage(Material material) {
        return switch (material) {
            case WOODEN_AXE, GOLDEN_AXE -> 7.0;
            case STONE_AXE, IRON_AXE, DIAMOND_AXE -> 9.0;
            case NETHERITE_AXE -> 10.0;
            default -> 6.0; // fallback, shouldn't normally be reached
        };
    }

    /** Sharpness applies universally; Smite/Bane of Arthropods only against their matching mob types. */
    private static double damageEnchantBonus(ItemStack axe, LivingEntity target) {
        int sharpness = axe.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness > 0) {
            return 0.5 * sharpness + 0.5;
        }

        int smite = axe.getEnchantmentLevel(Enchantment.SMITE);
        if (smite > 0 && UNDEAD_TYPES.contains(target.getType())) {
            return 2.5 * smite;
        }

        int bane = axe.getEnchantmentLevel(Enchantment.BANE_OF_ARTHROPODS);
        if (bane > 0 && ARTHROPOD_TYPES.contains(target.getType())) {
            return 2.5 * bane;
        }

        return 0.0;
    }

    /** The axe's full effective damage against this specific target. */
    public static double computeDamage(ItemStack axe, LivingEntity target) {
        return baseDamage(axe.getType()) + damageEnchantBonus(axe, target);
    }

    /**
     * Disables the target's shield if they're currently blocking with one.
     * Vanilla's own wiki-documented behaviour: a player's axe hit only has a
     * chance to disable a shield (boosted by Efficiency, guaranteed only
     * while sprinting), but a mob's axe hit always disables it unconditionally
     * (as with Vindicators). A cat counts as a mob, so this always triggers.
     */
    public static void applyOnHitEffects(ItemStack axe, LivingEntity attacker, LivingEntity target) {
        if (target instanceof Player player && player.isBlocking()) {
            player.setCooldown(Material.SHIELD, SHIELD_DISABLE_TICKS);
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1f, 1f);
        }
    }
}
