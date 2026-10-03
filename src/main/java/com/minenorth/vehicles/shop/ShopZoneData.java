package com.minenorth.vehicles.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

/** Une zone de pose par vendeur (id défini dans shops.json). */
public class ShopZoneData extends SavedData {
    public static final class Zone {
        public String dim;
        public BlockPos pos;
    }

    public final Map<String, Zone> zones = new LinkedHashMap<>();

    public static ShopZoneData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ShopZoneData::load, ShopZoneData::new, "minenorth_shopzones");
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag l = new ListTag();
        for (Map.Entry<String, Zone> en : zones.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putString("id", en.getKey());
            t.putString("dim", en.getValue().dim);
            t.putLong("pos", en.getValue().pos.asLong());
            l.add(t);
        }
        tag.put("zones", l);
        return tag;
    }

    public static ShopZoneData load(CompoundTag tag) {
        ShopZoneData d = new ShopZoneData();
        ListTag l = tag.getList("zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) {
            CompoundTag t = l.getCompound(i);
            Zone z = new Zone();
            z.dim = t.getString("dim");
            z.pos = BlockPos.of(t.getLong("pos"));
            d.zones.put(t.getString("id"), z);
        }
        return d;
    }
}
