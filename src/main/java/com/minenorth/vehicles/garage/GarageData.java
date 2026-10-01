package com.minenorth.vehicles.garage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Zones de garage, zone de pose du vendeur, et garages des joueurs (NBT complet des véhicules). */
public class GarageData extends SavedData {

    public static final class Zone {
        public String name, dim, tag = "";
        public double x, y, z, radius;
    }

    public static final class Stored {
        public String label = "", itemId = "";
        public CompoundTag nbt = new CompoundTag();
    }

    public final Map<String, Zone> zones = new LinkedHashMap<>();
    public final Map<UUID, List<Stored>> garages = new HashMap<>();
    public String shopDim;
    public BlockPos shopPos;

    public static GarageData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(GarageData::load, GarageData::new, "minenorth_garage");
    }

    public List<Stored> of(UUID id) {
        return garages.computeIfAbsent(id, k -> new ArrayList<>());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag zl = new ListTag();
        for (Zone z : zones.values()) {
            CompoundTag t = new CompoundTag();
            t.putString("name", z.name);
            t.putString("dim", z.dim);
            t.putString("tag", z.tag);
            t.putDouble("x", z.x);
            t.putDouble("y", z.y);
            t.putDouble("z", z.z);
            t.putDouble("radius", z.radius);
            zl.add(t);
        }
        tag.put("zones", zl);

        ListTag gl = new ListTag();
        for (Map.Entry<UUID, List<Stored>> en : garages.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            CompoundTag g = new CompoundTag();
            g.putUUID("owner", en.getKey());
            ListTag vl = new ListTag();
            for (Stored s : en.getValue()) {
                CompoundTag v = new CompoundTag();
                v.putString("label", s.label);
                v.putString("item", s.itemId);
                v.put("nbt", s.nbt);
                vl.add(v);
            }
            g.put("vehicles", vl);
            gl.add(g);
        }
        tag.put("garages", gl);

        if (shopPos != null && shopDim != null) {
            tag.putString("shopDim", shopDim);
            tag.putLong("shopPos", shopPos.asLong());
        }
        return tag;
    }

    public static GarageData load(CompoundTag tag) {
        GarageData d = new GarageData();
        ListTag zl = tag.getList("zones", Tag.TAG_COMPOUND);
        for (int i = 0; i < zl.size(); i++) {
            CompoundTag t = zl.getCompound(i);
            Zone z = new Zone();
            z.name = t.getString("name");
            z.dim = t.getString("dim");
            z.tag = t.getString("tag");
            z.x = t.getDouble("x");
            z.y = t.getDouble("y");
            z.z = t.getDouble("z");
            z.radius = t.getDouble("radius");
            d.zones.put(z.name, z);
        }
        ListTag gl = tag.getList("garages", Tag.TAG_COMPOUND);
        for (int i = 0; i < gl.size(); i++) {
            CompoundTag g = gl.getCompound(i);
            List<Stored> list = new ArrayList<>();
            ListTag vl = g.getList("vehicles", Tag.TAG_COMPOUND);
            for (int j = 0; j < vl.size(); j++) {
                CompoundTag v = vl.getCompound(j);
                Stored s = new Stored();
                s.label = v.getString("label");
                s.itemId = v.getString("item");
                s.nbt = v.getCompound("nbt");
                list.add(s);
            }
            d.garages.put(g.getUUID("owner"), list);
        }
        if (tag.contains("shopPos")) {
            d.shopDim = tag.getString("shopDim");
            d.shopPos = BlockPos.of(tag.getLong("shopPos"));
        }
        return d;
    }
}
