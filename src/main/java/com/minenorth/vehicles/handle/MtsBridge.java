package com.minenorth.vehicles.handle;

import com.minenorth.vehicles.config.VehicleConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;
import java.util.UUID;

/**
 * Seul point de contact avec Immersive Vehicles (MTS).
 * Aucune classe MTS n'est utilisée : un véhicule est une entité (mts:builder_existing) dont le NBT contient
 * tout l'état (pièces, inventaires, carburant, dégâts...). On sauvegarde/restaure donc ce NBT tel quel.
 */
public final class MtsBridge {
    public static final String OWNER = "mnrp_owner";

    private MtsBridge() {}

    public static String typeId(Entity e) {
        ResourceLocation k = ForgeRegistries.ENTITY_TYPES.getKey(e.getType());
        return k == null ? "" : k.toString();
    }

    public static boolean isVehicle(Entity e) {
        return typeId(e).equals(VehicleConfig.VEHICLE_ENTITY.get());
    }

    public static UUID ownerOf(Entity e) {
        CompoundTag d = e.getPersistentData();
        return d.hasUUID(OWNER) ? d.getUUID(OWNER) : null;
    }

    public static void setOwner(Entity e, UUID id) {
        e.getPersistentData().putUUID(OWNER, id);
    }

    /** Véhicule dans lequel le joueur est assis, ou null. */
    public static Entity vehicleOf(ServerPlayer p) {
        Entity ride = p.getVehicle();
        if (ride == null) return null;
        for (Entity e = ride; e != null; e = e.getVehicle()) {
            if (isVehicle(e)) return e;
        }
        // Le siège MTS n'est pas lié par un lien vanilla : on prend le véhicule le plus proche du siège
        return nearest((ServerLevel) p.level(), ride.position(), VehicleConfig.SEARCH_RADIUS.get(), null);
    }

    /** Véhicule le plus proche (optionnellement limité à un propriétaire). */
    public static Entity nearest(ServerLevel level, Vec3 pos, double radius, UUID owner) {
        AABB box = new AABB(pos, pos).inflate(radius);
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : level.getEntities((Entity) null, box, MtsBridge::isVehicle)) {
            if (owner != null && !owner.equals(ownerOf(e))) continue;
            double d = e.position().distanceToSqr(pos);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Id d'item MTS correspondant au véhicule (même format que le catalogue : mts:pack.systeme[_variante]). */
    public static String itemId(CompoundTag nbt) {
        String pack = nbt.getString("packID");
        String sys = nbt.getString("systemName");
        if (pack.isEmpty() || sys.isEmpty()) return "";
        return ("mts:" + pack + "." + sys + nbt.getString("subName")).toLowerCase(Locale.ROOT);
    }

    public static CompoundTag snapshot(Entity vehicle) {
        CompoundTag tag = new CompoundTag();
        vehicle.saveWithoutId(tag);
        tag.remove("UUID");
        tag.remove("Passengers");
        return tag;
    }

    /** Recrée l'entité (non ajoutée au monde) depuis un NBT sauvegardé, à la position donnée. */
    public static Entity restore(ServerLevel level, CompoundTag saved, double x, double y, double z) {
        CompoundTag tag = saved.copy();
        tag.putString("id", VehicleConfig.VEHICLE_ENTITY.get());

        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        tag.put("Pos", pos);

        ListTag motion = new ListTag();
        motion.add(DoubleTag.valueOf(0));
        motion.add(DoubleTag.valueOf(0));
        motion.add(DoubleTag.valueOf(0));
        tag.put("Motion", motion);

        // MTS relit sa propre position / vitesse dans ses clés
        tag.putDouble("positionx", x);
        tag.putDouble("positiony", y);
        tag.putDouble("positionz", z);
        for (String k : new String[]{"motionx", "motiony", "motionz"}) {
            if (tag.contains(k)) tag.putDouble(k, 0);
        }
        return EntityType.loadEntityRecursive(tag, level, en -> en);
    }
}
