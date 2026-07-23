package com.bettercats.cat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Handles a cat "holding" an item in its mouth - vanilla doesn't support this
 * for cats the way it does for foxes, so this fakes it with three layers:
 *
 * <ul>
 *   <li>The item is serialized into the cat's PersistentDataContainer - this
 *       is the source of truth and survives restarts.</li>
 *   <li>It's also mirrored into the cat's actual main-hand equipment slot, in
 *       case the client ever renders held equipment for cats - cheap and
 *       harmless either way.</li>
 *   <li>A small {@link ItemDisplay} is spawned as a passenger of the cat to
 *       guarantee it's actually visible, positioned to approximate the mouth
 *       rather than the default "on top of the head" passenger seat.</li>
 * </ul>
 */
public final class CatItemManager {

    /** Passenger seats default to the top of the mob's head - this offset pulls
     *  the display down and forward to approximate the mouth instead. Purely a
     *  best guess without a live client to check against - easy to retune. */
    private static final Vector3f MOUTH_OFFSET = new Vector3f(0f, 0.05f, 0.28f);
    private static final Vector3f MOUTH_SCALE = new Vector3f(0.4f, 0.4f, 0.4f);

    private final Plugin plugin;
    private final NamespacedKey heldItemKey;
    private final NamespacedKey displayIdKey;
    private final NamespacedKey displayMarkerKey;

    public CatItemManager(Plugin plugin) {
        this.plugin = plugin;
        this.heldItemKey = new NamespacedKey(plugin, "held_item");
        this.displayIdKey = new NamespacedKey(plugin, "held_item_display");
        this.displayMarkerKey = new NamespacedKey(plugin, "held_item_display_marker");
    }

    /** Returns a copy of whatever the cat is currently holding, or null if nothing. */
    public ItemStack getHeldItem(Cat cat) {
        byte[] bytes = cat.getPersistentDataContainer().get(heldItemKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null) {
            return null;
        }
        try {
            return ItemStack.deserializeBytes(bytes);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to deserialize a cat's held item", e);
            return null;
        }
    }

    public boolean isHoldingSword(Cat cat) {
        ItemStack item = getHeldItem(cat);
        return item != null && isSword(item.getType());
    }

    public static boolean isSword(Material material) {
        return material.name().endsWith("_SWORD");
    }

    public boolean isHoldingAxe(Cat cat) {
        ItemStack item = getHeldItem(cat);
        return item != null && isAxe(item.getType());
    }

    public static boolean isAxe(Material material) {
        return material.name().endsWith("_AXE");
    }

    /** Gives the cat one item from the stack (consuming exactly one from it). */
    public void giveItem(Cat cat, ItemStack fromStack) {
        ItemStack single = fromStack.clone();
        single.setAmount(1);

        cat.getPersistentDataContainer().set(heldItemKey, PersistentDataType.BYTE_ARRAY, single.serializeAsBytes());
        mirrorEquipment(cat, single);
        refreshVisual(cat, single);

        fromStack.setAmount(fromStack.getAmount() - 1);
    }

    /** Removes and returns whatever the cat is holding, or null if it had nothing. */
    public ItemStack takeItem(Cat cat) {
        ItemStack current = getHeldItem(cat);
        if (current == null) {
            return null;
        }
        cat.getPersistentDataContainer().remove(heldItemKey);
        mirrorEquipment(cat, null);
        removeVisual(cat);
        return current;
    }

    /** Re-attaches the visual display after the cat reloads/respawns, if it should have one. */
    public void restoreVisualIfNeeded(Cat cat) {
        ItemStack item = getHeldItem(cat);
        if (item == null) {
            return;
        }
        boolean hasDisplay = false;
        for (Entity passenger : cat.getPassengers()) {
            Boolean marked = passenger.getPersistentDataContainer().get(displayMarkerKey, PersistentDataType.BOOLEAN);
            if (Boolean.TRUE.equals(marked)) {
                hasDisplay = true;
                break;
            }
        }
        if (!hasDisplay) {
            refreshVisual(cat, item);
        }
    }

    private void mirrorEquipment(Cat cat, ItemStack item) {
        EntityEquipment equipment = cat.getEquipment();
        if (equipment != null) {
            equipment.setItemInMainHand(item);
        }
    }

    private void refreshVisual(Cat cat, ItemStack item) {
        removeVisual(cat);

        ItemDisplay display = cat.getWorld().spawn(cat.getLocation(), ItemDisplay.class, entity -> {
            entity.setItemStack(item);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setTransformation(new Transformation(
                    MOUTH_OFFSET,
                    new AxisAngle4f(0f, 0f, 0f, 1f),
                    MOUTH_SCALE,
                    new AxisAngle4f(0f, 0f, 0f, 1f)
            ));
            entity.getPersistentDataContainer().set(displayMarkerKey, PersistentDataType.BOOLEAN, true);
        });

        cat.addPassenger(display);
        cat.getPersistentDataContainer().set(displayIdKey, PersistentDataType.STRING, display.getUniqueId().toString());
    }

    private void removeVisual(Cat cat) {
        String idString = cat.getPersistentDataContainer().get(displayIdKey, PersistentDataType.STRING);
        if (idString == null) {
            return;
        }
        try {
            UUID id = UUID.fromString(idString);
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        } catch (IllegalArgumentException ignored) {
            // malformed UUID somehow - nothing sensible to clean up
        }
        cat.getPersistentDataContainer().remove(displayIdKey);
    }
}
