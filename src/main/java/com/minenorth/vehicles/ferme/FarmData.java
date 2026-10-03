package com.minenorth.vehicles.ferme;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Zones de ferme (rectangles X/Z, remplacent la région WorldGuard « ferme ») + cultures plantées par les véhicules. */
public class FarmData extends SavedData {
    public static final class Zone {
        public String name, dim;
        public int minX, maxX, minZ, maxZ;
    }

    public final Map<String, Zone> zones = new LinkedHashMap<>();
    /** dimension -> positions des cultures plantées/récoltées par un véhicule (pousse accélérée) */
    public final Map<String, Set<Long>> tracked = new HashMap<>();

    public static FarmData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FarmData::load, FarmData::new, "minenorth_farm");
    }

    public boolean inZone(String dim, double x, double z) {
        for (Zone zn : zones.values()) {
            if (zn.dim.equals(dim) && x >= zn.minX && x < zn.maxX + 1 && z >= zn.minZ && z < zn.maxZ + 1) return true;
        }
        return false;
    }

    public void track(String dim, BlockPos pos) {
        if (tracked.computeIfAbsent(dim, k -> new HashSet<>()).add(pos.asLong())) setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag zl = new ListTag();
        for (Zone z : zones.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("name", z.name);
            t.putString("dim", z.dim);
            t.putInt("minX", z.minX);
            t.putInt("maxX", z.maxX);
            t.putInt("minZ", z.minZ);
            t.putInt("maxZ", z.maxZ);
            zl.add(t);
        }
        tag.put("zones", zl);

        ListTag tl = new ListTag();
        for (Map.Entry<String, Set<Long>> en : tracked.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            CompoundTag t = new CompoundTag();
            t.putString("dim", en.getKey());
            long[] arr = new long[en.getValue().size()];
            int i = 0;
            for (long l : en.getValue()) arr[i++] = l;
            t.put("positions", new LongArrayTag(arr));
            tl.add(t);
        }
        tag.put("tracked", tl);
        return tag;
    }

    public static FarmData load(CompoundTag tag) {
        FarmData d = new FarmData();
        ListTag zl = tag.getList("zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zl.size(); i++) {
            CompoundTag t = zl.getCompound(i);
            Zone z = new Zone();
            z.name = t.getString("name");
            z.dim = t.getString("dim");
            z.minX = t.getInt("minX");
            z.maxX = t.getInt("maxX");
            z.minZ = t.getInt("minZ");
            z.maxZ = t.getInt("maxZ");
            d.zones.put(z.name, z);
        }
        ListTag tl = tag.getList("tracked", Tag.TAG_COMPOUND);
        for (int i = 0; i < tl.size(); i++) {
            CompoundTag t = tl.getCompound(i);
            Set<Long> set = new HashSet<>();
            for (long l : t.getLongArray("positions")) set.add(l);
            d.tracked.put(t.getString("dim"), set);
        }
        return d;
    }
}
