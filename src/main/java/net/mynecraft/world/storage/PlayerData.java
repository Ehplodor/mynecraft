package net.mynecraft.world.storage;

import net.mynecraft.world.Blocks;
import net.mynecraft.world.Inventory;
import net.mynecraft.world.Survival;

import java.util.Map;

/** Player state stored in level.dat's Data.Player compound, with vanilla tag names. */
public final class PlayerData {
    public double[] pos;
    public int health = Survival.MAX_HEALTH, air = Survival.MAX_AIR, selectedSlot;
    public boolean creative;
    public final Inventory inventory = new Inventory();

    static PlayerData read(Map<String, Object> p) {
        PlayerData d = new PlayerData();
        var pos = Nbt.getList(p, "Pos");
        if (pos.size() == 3) d.pos = new double[] {num(pos.get(0)), num(pos.get(1)), num(pos.get(2))};
        if (p.get("Health") instanceof Number n) d.health = Math.max(0, Math.min(Survival.MAX_HEALTH, (int) Math.ceil(n.doubleValue())));
        if (p.get("Air") instanceof Number n) d.air = Math.max(0, Math.min(Survival.MAX_AIR, n.intValue()));
        d.creative = Nbt.getInt(p, "playerGameType", 0) == 1;
        d.selectedSlot = Math.max(0, Math.min(Inventory.HOTBAR - 1, Nbt.getInt(p, "SelectedItemSlot", 0)));
        for (Object o : Nbt.getList(p, "Inventory")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) o;
            int slot = Nbt.getInt(item, "Slot", -1);
            String id = String.valueOf(item.get("id"));
            int count = item.containsKey("count") ? Nbt.getInt(item, "count", 1) : Nbt.getInt(item, "Count", 1);
            short block = net.mynecraft.world.Items.fromName(id);
            if (slot < 0 || slot >= Inventory.SIZE || block <= 0 || count <= 0) continue; // unknown items are skipped
            d.inventory.ids[slot] = block;
            d.inventory.counts[slot] = Math.min(count, Inventory.MAX_STACK);
        }
        return d;
    }

    void write(Map<String, Object> p) {
        if (pos != null) {
            Nbt.NbtList list = new Nbt.NbtList(Nbt.DOUBLE);
            for (double v : pos) list.values().add(v);
            p.put("Pos", list);
        }
        p.put("Health", (float) health);
        p.put("Air", (short) air);
        p.put("playerGameType", creative ? 1 : 0);
        p.put("SelectedItemSlot", selectedSlot);
        Nbt.NbtList items = new Nbt.NbtList(Nbt.COMPOUND);
        for (int i = 0; i < Inventory.SIZE; i++) {
            if (inventory.counts[i] <= 0) continue;
            Map<String, Object> item = Nbt.compound();
            item.put("Slot", (byte) i);
            item.put("id", "minecraft:" + net.mynecraft.world.Items.name(inventory.ids[i]));
            item.put("count", inventory.counts[i]);
            items.values().add(item);
        }
        p.put("Inventory", items);
    }

    private static double num(Object o) {
        return ((Number) o).doubleValue();
    }

}
