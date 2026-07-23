package com.bettercats.cat;

import com.bettercats.items.AxeBehavior;
import com.bettercats.items.SwordBehavior;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives tamed-cat combat: acquiring targets to defend/join their owner,
 * chasing them down, and attacking. Every {@link #HITS_PER_POUNCE}th landed
 * hit is a fox-style pounce instead of a plain attack - only the pounce can
 * crit (and only if the cat is holding a sword or axe), and only the pounce
 * applies Scratch. This keeps a swarm of cats visually varied instead of
 * every single one leaping and critting on every hit.
 *
 * <p>Damage is a flat placeholder ("base bite damage") only when the cat has
 * nothing (or a non-supported item) in its mouth. Holding a sword or axe
 * swaps in its real computed damage (see {@link SwordBehavior} /
 * {@link AxeBehavior}) for every hit, with the crit multiplier applying on
 * top for a landed pounce. Flint & Steel, Wind Charge, and Fire Charge are
 * still separate follow-up passes.</p>
 */
public final class CatCombatController {

    private static final double GUARD_RADIUS = 20.0;      // how far a cat reacts to its owner from
    private static final double ATTACK_RANGE = 2.2;        // distance to start an attack
    private static final double MAX_CHASE_RANGE = 24.0;    // give up the chase past this
    private static final double HIT_CONFIRM_RANGE = 2.5;   // must still be this close when a pounce lands
    private static final long ATTACK_COOLDOWN_MILLIS = 1200;
    private static final long PATH_REISSUE_MILLIS = 500;
    private static final int HIT_CONFIRM_DELAY_TICKS = 5;

    /** Every this-many-th landed hit is a pounce instead of a plain attack. */
    private static final int HITS_PER_POUNCE = 3;

    private static final double LEAP_HORIZONTAL_FORCE = 0.7;
    private static final double LEAP_UPWARD_FORCE = 0.4;

    private static final double BASE_BITE_DAMAGE = 3.0;
    private static final double CRIT_MULTIPLIER = 1.5;

    private static final int SCRATCH_DURATION_TICKS = 100; // 5 seconds
    private static final PotionEffectType[] SCRATCH_EFFECTS = {
            PotionEffectType.WEAKNESS, PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE
    };

    private final Plugin plugin;
    private final CatItemManager itemManager;
    private final Map<UUID, CatState> activeCats = new HashMap<>();

    public CatCombatController(Plugin plugin, CatItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
    }

    /** All tamed cats owned by the given player within radius blocks. */
    public List<Cat> findNearbyOwnedCats(Player owner, double radius) {
        List<Cat> result = new ArrayList<>();
        for (Entity entity : owner.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Cat cat && cat.isTamed() && owner.equals(cat.getOwner())) {
                result.add(cat);
            }
        }
        return result;
    }

    /** Convenience overload using the default guard radius. */
    public List<Cat> findNearbyOwnedCats(Player owner) {
        return findNearbyOwnedCats(owner, GUARD_RADIUS);
    }

    /** Points a cat at a new target, unless it's already busy with one (Phase 1: no target-switching mid-fight). */
    public void acquireTarget(Cat cat, LivingEntity target) {
        if (!cat.isTamed() || cat.isDead() || cat.getUniqueId().equals(target.getUniqueId())) {
            return;
        }
        CatState state = activeCats.computeIfAbsent(cat.getUniqueId(), k -> new CatState());
        if (state.targetId != null) {
            return;
        }
        state.targetId = target.getUniqueId();
        cat.setTarget(target);
    }

    /** Drops all tracking for a cat - call this when it dies or is removed. */
    public void forget(UUID catId) {
        activeCats.remove(catId);
    }

    /** Call this every few ticks from the main plugin's scheduler. */
    public void tick() {
        Iterator<Map.Entry<UUID, CatState>> iterator = activeCats.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, CatState> entry = iterator.next();
            CatState state = entry.getValue();

            if (!(Bukkit.getEntity(entry.getKey()) instanceof Cat cat) || cat.isDead() || !cat.isValid()) {
                iterator.remove();
                continue;
            }

            LivingEntity target = resolveTarget(state.targetId, cat);
            if (target == null) {
                cat.setTarget(null);
                iterator.remove();
                continue;
            }

            double distance = cat.getLocation().distance(target.getLocation());
            if (distance > MAX_CHASE_RANGE) {
                cat.setTarget(null);
                iterator.remove();
                continue;
            }

            long now = System.currentTimeMillis();

            if (distance > ATTACK_RANGE) {
                if (now - state.lastPathUpdateMillis > PATH_REISSUE_MILLIS) {
                    cat.getPathfinder().moveTo(target.getLocation(), 1.4);
                    state.lastPathUpdateMillis = now;
                }
            } else if (now >= state.nextAttackReadyAtMillis) {
                state.nextAttackReadyAtMillis = now + ATTACK_COOLDOWN_MILLIS;
                state.hitCounter++;

                if (state.hitCounter >= HITS_PER_POUNCE) {
                    state.hitCounter = 0;
                    executePounce(cat, target);
                } else {
                    executeNormalAttack(cat, target);
                }
            }
        }
    }

    private LivingEntity resolveTarget(UUID targetId, Cat cat) {
        if (targetId == null) {
            return null;
        }
        if (!(Bukkit.getEntity(targetId) instanceof LivingEntity living) || living.isDead()) {
            return null;
        }
        if (!living.getWorld().equals(cat.getWorld())) {
            return null;
        }
        return living;
    }

    /** What this held item deals against this target - Sword/Axe real damage, or the flat placeholder for anything else (including nothing). */
    private double resolveDamage(ItemStack held, LivingEntity target) {
        if (held == null) {
            return BASE_BITE_DAMAGE;
        }
        if (CatItemManager.isSword(held.getType())) {
            return SwordBehavior.computeDamage(held);
        }
        if (CatItemManager.isAxe(held.getType())) {
            return AxeBehavior.computeDamage(held, target);
        }
        return BASE_BITE_DAMAGE;
    }

    /** Swords and axes both count as "real weapons" for crit purposes - vanilla crits aren't sword-exclusive either. */
    private boolean isRealWeapon(ItemStack held) {
        return held != null && (CatItemManager.isSword(held.getType()) || CatItemManager.isAxe(held.getType()));
    }

    private void applyWeaponOnHitEffects(ItemStack held, Cat cat, LivingEntity target) {
        if (held == null) {
            return;
        }
        if (CatItemManager.isSword(held.getType())) {
            SwordBehavior.applyOnHitEffects(held, cat, target);
        } else if (CatItemManager.isAxe(held.getType())) {
            AxeBehavior.applyOnHitEffects(held, cat, target);
        }
    }

    /** A plain hit - no leap, no crit, no Scratch. Most attacks are this. */
    private void executeNormalAttack(Cat cat, LivingEntity target) {
        cat.swingMainHand();

        ItemStack held = itemManager.getHeldItem(cat);
        double damage = resolveDamage(held, target);

        target.damage(damage, cat);
        cat.getWorld().playSound(cat.getLocation(), Sound.ENTITY_CAT_HISS, 0.6f, 1.4f);

        applyWeaponOnHitEffects(held, cat, target);
    }

    /** Every {@link #HITS_PER_POUNCE}th hit: a fox-style leap, crit if holding a sword or axe, and Scratch on landing. */
    private void executePounce(Cat cat, LivingEntity target) {
        Vector direction = target.getLocation().toVector().subtract(cat.getLocation().toVector());
        direction.setY(0);
        if (direction.lengthSquared() > 0.0001) {
            direction.normalize();
        }
        Vector leap = direction.multiply(LEAP_HORIZONTAL_FORCE).setY(LEAP_UPWARD_FORCE);
        cat.setVelocity(leap);
        cat.getWorld().playSound(cat.getLocation(), Sound.ENTITY_CAT_HISS, 1f, 1.2f);

        UUID catId = cat.getUniqueId();
        UUID targetId = target.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> confirmPounceHit(catId, targetId), HIT_CONFIRM_DELAY_TICKS);
    }

    private void confirmPounceHit(UUID catId, UUID targetId) {
        if (!(Bukkit.getEntity(catId) instanceof Cat cat) || cat.isDead()) {
            return;
        }
        if (!(Bukkit.getEntity(targetId) instanceof LivingEntity target) || target.isDead()) {
            return;
        }
        if (!cat.getWorld().equals(target.getWorld())
                || cat.getLocation().distance(target.getLocation()) > HIT_CONFIRM_RANGE) {
            return; // the pounce missed - target moved out of range mid-leap
        }

        ItemStack held = itemManager.getHeldItem(cat);
        boolean crit = isRealWeapon(held);
        double damage = resolveDamage(held, target) * (crit ? CRIT_MULTIPLIER : 1.0);
        target.damage(damage, cat);

        if (crit) {
            Location center = target.getLocation().add(0, target.getHeight() / 2.0, 0);
            target.getWorld().spawnParticle(Particle.CRIT, center, 8, 0.3, 0.3, 0.3, 0.01);
            target.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 1f);
        }
        applyWeaponOnHitEffects(held, cat, target);

        applyScratch(target);
    }

    /**
     * Only ever one Scratch effect active on a target at a time. If they're
     * already affected, this refreshes that same effect's duration back to
     * full instead of re-rolling - so a swarm of cats landing pounces faster
     * than the 5-second timer runs out keeps a target locked down
     * indefinitely, rather than each cat fighting over separate timers.
     */
    private void applyScratch(LivingEntity target) {
        for (PotionEffectType type : SCRATCH_EFFECTS) {
            if (target.hasPotionEffect(type)) {
                // Explicit remove-then-add rather than relying on addPotionEffect
                // alone to refresh - current versions support multiple stacked
                // instances of the same effect type, so this guarantees a clean
                // single refreshed instance instead of an ambiguous stack.
                target.removePotionEffect(type);
                target.addPotionEffect(new PotionEffect(type, SCRATCH_DURATION_TICKS, 0, true, true, true));
                return;
            }
        }

        PotionEffectType chosen = SCRATCH_EFFECTS[ThreadLocalRandom.current().nextInt(SCRATCH_EFFECTS.length)];
        target.addPotionEffect(new PotionEffect(chosen, SCRATCH_DURATION_TICKS, 0, true, true, true));
    }
}
