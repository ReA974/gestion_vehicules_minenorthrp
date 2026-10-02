package com.minenorth.vehicles.fourriere;

import com.minenorth.vehicles.garage.GarageData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Zone de sortie de la fourrière + véhicules en fourrière par propriétaire (NBT complet). */
public class ImpoundData extends SavedData {
    public boolean hasZone;
    public String zoneDim = "";
    public double zx, zy, zz;
    public final Map<UUID, List<GarageData.Stored>> vehicles = new HashMap<>();

    public static ImpoundData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(ImpoundData::load, ImpoundData::new, "minenorth_impound");
    }

    public List<GarageData.Stored> of(UUID id) {
        return vehicles.computeIfAbsent(id, k -> new ArrayList<>());
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putBoolean("hasZone", hasZone);
        tag.putString("zoneDim", zoneDim);
        tag.putDouble("zx", zx);
        tag.putDouble("zy", zy);
        tag.putDouble("zz", zz);
        ListTag gl = new ListTag();
        for (Map.Entry<UUID, List<GarageData.Stored>> en : vehicles.entrySet()) {
            if (en.getValue().isEmpty()) continue;
            CompoundTag g = new CompoundTag();
            g.putUUID("owner", en.getKey());
            ListTag vl = new ListTag();
            for (GarageData.Stored s : en.getValue()) {
                CompoundTag v = new CompoundTag();
                v.putString("label", s.label);
                v.putString("item", s.itemId);
                v.put("nbt", s.nbt);
                vl.add(v);
            }
            g.put("vehicles", vl);
            gl.add(g);
        }
        tag.put("owners", gl);
        return tag;
    }

    public static ImpoundData load(CompoundTag tag) {
        ImpoundData d = new ImpoundData();
        d.hasZone = tag.getBoolean("hasZone");
        d.zoneDim = tag.getString("zoneDim");
        d.zx = tag.getDouble("zx");
        d.zy = tag.getDouble("zy");
        d.zz = tag.getDouble("zz");
        ListTag gl = tag.getList("owners", Tag.TAG_COMPOUND);
        for (int i = 0; i < gl.size(); i++) {
            CompoundTag g = gl.getCompound(i);
            List<GarageData.Stored> list = new ArrayList<>();
            ListTag vl = g.getList("vehicles", Tag.TAG_COMPOUND);
            for (int j = 0; j < vl.size(); j++) {
                CompoundTag v = vl.getCompound(j);
                GarageData.Stored s = new GarageData.Stored();
                s.label = v.getString("label");
                s.itemId = v.getString("item");
                s.nbt = v.getCompound("nbt");
                list.add(s);
            }
            d.vehicles.put(g.getUUID("owner"), list);
        }
        return d;
    }
}
