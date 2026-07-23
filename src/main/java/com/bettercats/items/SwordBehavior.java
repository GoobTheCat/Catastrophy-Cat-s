package com.bettercats.items;

import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Computes a held sword's real damage (base material damage + Sharpness) and
 * applies its on-hit enchantment effects (Fire Aspect, Knockback).
 *
 * <p>This deliberately doesn't lean on the entity Attribute system - it's not
 * clear that a passive mob like Cat has an ATTACK_DAMAGE attribute registered
 * at all, so the numbers are computed directly from the item instead. Pure
 * Bukkit API, no NMS: read the material and enchantments, do the arithmetic,
 * apply the result with plain damage()/setFireTicks()/setVelocity() calls.</p>
 *
 * <p>Not implemented: Sweeping Edge (needs multi-target AoE detection, low
 * value for a single-target cat bite) and durability loss on the held sword
 * (kept simple - a cat's sword doesn't wear out and vanish mid-video). Looting
 * should already work for free: since the sword is mirrored into the cat's
 * real main-hand equipment slot, vanilla's own loot table evaluation reads
 * the killer's equipped weapon regardless of whether the killer is a player
 * or another mob.</p>
 */
public final class SwordBehavior {

    private SwordBehavior() {
    }

    /** Base attack damage for each vanilla sword tier, before enchantments. */
    private static double baseDamage(Material material) {
        return switch (material) {
            case WOODEN_SWORD, GOLDEN_SWORD -> 4.0;
            case STONE_SWORD -> 5.0;
            case IRON_SWORD -> 6.0;
            case DIAMOND_SWORD -> 7.0;
            case NETHERITE_SWORD -> 8.0;
            default -> 3.0; // fallback, shouldn't normally be reached
        };
    }

    /** Vanilla Sharpness formula: +0.5 damage per level, plus a flat +0.5. */
    private static double sharpnessBonus(ItemStack sword) {
        int level = sword.getEnchantmentLevel(Enchantment.SHARPNESS);
        return level > 0 ? (0.5 * level + 0.5) : 0.0;
    }

    /** The sword's full effective damage: base material damage + Sharpness. */
    public static double computeDamage(ItemStack sword) {
        return baseDamage(sword.getType()) + sharpnessBonus(sword);
    }

    /** Applies Fire Aspect ignition and bonus Knockback, if the sword has them. */
    public static void applyOnHitEffects(ItemStack sword, LivingEntity attacker, LivingEntity target) {
        applyFireAspect(sword, target);
        applyKnockback(sword, attacker, target);
    }

    private static void applyFireAspect(ItemStack sword, LivingEntity target) {
        int level = sword.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        if (level <= 0) {
            return;
        }
        int fireTicks = level * 4 * 20; // vanilla: level x 4 seconds
        if (target.getFireTicks() < fireTicks) {
            target.setFireTicks(fireTicks);
        }
    }

    /** Vanilla already applies a small baseline knockback on any damage() call - this only adds the enchantment's extra push. */
    private static void applyKnockback(ItemStack sword, LivingEntity attacker, LivingEntity target) {
        int level = sword.getEnchantmentLevel(Enchantment.KNOCKBACK);
        if (level <= 0) {
            return;
        }
        Vector direction = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() < 0.0001) {
            return;
        }
        direction.normalize().multiply(0.5 * level);

        Vector newVelocity = target.getVelocity().add(direction);
        newVelocity.setY(Math.max(newVelocity.getY(), 0.35));
        target.setVelocity(newVelocity);
    }
}
