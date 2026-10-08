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

    /** Véhicule sorti « en main » : l'item est dans l'inventaire, le véhicule attend d'être posé dans la zone. */
    public static final class Pending {
        public Stored vehicle = new Stored();
        public String zone = "";
        /** Heure (ms) limite pour poser le véhicule. */
        public long expireMs;
    }

    /** Joueurs qui veulent récupérer leur véhicule en main (les autres : il apparaît directement). */
    public final java.util.Set<UUID> handMode = new java.util.HashSet<>();
    public final Map<UUID, Pending> pending = new HashMap<>();
    /** Items de véhicule à reprendre à la prochaine connexion (sortie en main expirée pendant l'absence). */
    public final Map<UUID, List<String>> strip = new HashMap<>();

    public final Map<String, Zone> zones = new LinkedHashMap<>();
    public final Map<UUID, List<Stored>> garages = new HashMap<>();

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

        ListTag hm = new ListTag();
        for (UUID id : handMode) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", id);
            hm.add(t);
        }
        tag.put("handMode", hm);
        ListTag pl = new ListTag();
        for (Map.Entry<UUID, Pending> en : pending.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", en.getKey());
            t.putString("zone", en.getValue().zone);
            t.putLong("expire", en.getValue().expireMs);
            t.putString("label", en.getValue().vehicle.label);
            t.putString("item", en.getValue().vehicle.itemId);
            t.put("nbt", en.getValue().vehicle.nbt);
            pl.add(t);
        }
        tag.put("pending", pl);
        ListTag sl = new ListTag();
        for (Map.Entry<UUID, List<String>> en : strip.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", en.getKey());
            ListTag items = new ListTag();
            for (String it : en.getValue()) items.add(net.minecraft.nbt.StringTag.valueOf(it));
            t.put("items", items);
            sl.add(t);
        }
        tag.put("strip", sl);

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
        ListTag hm = tag.getList("handMode", Tag.TAG_COMPOUND);
        for (int i = 0; i < hm.size(); i++) d.handMode.add(hm.getCompound(i).getUUID("id"));
        ListTag pl = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < pl.size(); i++) {
            CompoundTag t = pl.getCompound(i);
            Pending pe = new Pending();
            pe.zone = t.getString("zone");
            pe.expireMs = t.getLong("expire");
            pe.vehicle.label = t.getString("label");
            pe.vehicle.itemId = t.getString("item");
            pe.vehicle.nbt = t.getCompound("nbt");
            d.pending.put(t.getUUID("id"), pe);
        }
        ListTag sl = tag.getList("strip", Tag.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) {
            CompoundTag t = sl.getCompound(i);
            List<String> items = new ArrayList<>();
            ListTag il = t.getList("items", Tag.TAG_STRING);
            for (int j = 0; j < il.size(); j++) items.add(il.getString(j));
            d.strip.put(t.getUUID("id"), items);
        }
        return d;
    }
}
