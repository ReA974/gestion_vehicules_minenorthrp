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
    /** Drapeau « véhicule assuré » : vit dans les données persistantes de l'entité, donc dans le NBT sauvegardé au garage. */
    public static final String INSURED = "mnrp_insured";

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

    /** État du véhicule au moment où il est sorti du garage (assuré seulement) : c'est celui qu'on lui rend après une destruction. */
    public static final String ORIGIN = "mnrp_origin";

    public static void setOrigin(Entity e, CompoundTag state) { e.getPersistentData().put(ORIGIN, state.copy()); }

    public static CompoundTag origin(Entity e) {
        CompoundTag d = e.getPersistentData();
        return d.contains(ORIGIN, 10) ? d.getCompound(ORIGIN).copy() : null;
    }

    /** Retrait voulu par ce mod (rangement, fourrière, recentrage...) : l'assurance ne doit PAS jouer. */
    public static final String HANDLED = "mnrp_handled";

    public static void discard(Entity e) {
        e.getPersistentData().putBoolean(HANDLED, true);
        e.discard();
    }

    public static boolean isInsured(Entity e) { return e.getPersistentData().getBoolean(INSURED); }

    public static boolean isInsured(CompoundTag saved) { return saved.getCompound("ForgeData").getBoolean(INSURED); }

    public static void setInsured(CompoundTag saved, boolean insured) {
        CompoundTag fd = saved.getCompound("ForgeData");
        if (insured) fd.putBoolean(INSURED, true); else fd.remove(INSURED);
        saved.put("ForgeData", fd);
    }

    /** MTS a marqué le véhicule comme détruit (dégâts >= points de vie du modèle). */
    public static boolean outOfHealth(Entity e) {
        return field(field(e, "entity"), "outOfHealth") instanceof Boolean b && b;
    }

    /** Points de vie du modèle (définition MTS : general.health), 0 si illisible. */
    public static double health(Entity e) {
        return field(field(field(field(e, "entity"), "definition"), "general"), "health") instanceof Number n ? n.doubleValue() : 0;
    }

    /** Plus grande valeur numérique de clé « damage » dans le NBT (dégâts du véhicule, où qu'ils soient rangés), 0 si absente. */
    public static double damageIn(CompoundTag t) {
        double max = 0;
        for (String k : t.getAllKeys()) {
            net.minecraft.nbt.Tag v = t.get(k);
            if (v instanceof CompoundTag c) max = Math.max(max, damageIn(c));
            else if (k.equals("damage") && v instanceof net.minecraft.nbt.NumericTag n) max = Math.max(max, n.getAsDouble());
        }
        return max;
    }

    /** Remet à zéro les dégâts d'un NBT de véhicule sauvegardé (variable « damage », où qu'elle soit rangée). */
    public static void repair(CompoundTag t) {
        for (String k : new java.util.ArrayList<>(t.getAllKeys())) {
            net.minecraft.nbt.Tag v = t.get(k);
            if (v instanceof CompoundTag c) repair(c);
            else if (k.equals("damage") && v instanceof net.minecraft.nbt.NumericTag) t.putDouble(k, 0);
        }
    }

    // ------------------------------------------------------------------ plaque posée (lue dans MTS par réflexion)

    private static Object field(Object o, String name) {
        if (o == null) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static final String[] PLATE_FIELDS = {"Code", "License Plate", "Plate", "Plaque", "Immatriculation"};

    private static String plateIn(Object holder) {
        if (!(field(holder, "text") instanceof java.util.Map<?, ?> m)) return "";
        for (java.util.Map.Entry<?, ?> en : m.entrySet()) {
            if (field(en.getKey(), "fieldName") instanceof String n && en.getValue() instanceof String v && !v.isBlank())
                for (String c : PLATE_FIELDS) if (c.equalsIgnoreCase(n.trim())) return v.trim();
        }
        return "";
    }

    /** Texte de la plaque posée sur le véhicule (pièce mts:gvp.eu_plate), ou "" s'il n'en a pas ou si MTS est illisible. */
    public static String plate(Entity e) {
        try {
            Object in = field(e, "entity");
            if (in == null) return "";
            String found = plateIn(in);
            if (!found.isEmpty()) return found;
            if (field(in, "allParts") instanceof Iterable<?> parts) {
                for (Object part : parts) {
                    found = plateIn(part);
                    if (!found.isEmpty()) return found;
                }
                for (Object part : parts) {
                    if (field(field(part, "definition"), "systemName") instanceof String s && s.toLowerCase(Locale.ROOT).contains("plate")
                            && field(part, "text") instanceof java.util.Map<?, ?> m)
                        for (Object v : m.values()) if (v instanceof String str && !str.isBlank()) return str.trim();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** Définition MTS : motorized.isAircraft / isBlimp (lue par réflexion, false si illisible). */
    public static boolean isAircraft(Entity e) {
        try {
            Object mo = field(field(field(e, "entity"), "definition"), "motorized");
            return field(mo, "isAircraft") instanceof Boolean a && a || field(mo, "isBlimp") instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }

    public static CompoundTag snapshot(Entity vehicle) {
        CompoundTag tag = new CompoundTag();
        vehicle.saveWithoutId(tag);
        tag.remove("UUID");
        tag.remove("Passengers");
        if (isAircraft(vehicle)) { // le type aérien suit le véhicule dans son NBT
            CompoundTag fd = tag.getCompound("ForgeData");
            fd.putString(com.minenorth.vehicles.garage.VehicleType.NBT_KEY, "air");
            tag.put("ForgeData", fd);
        }
        if (tag.contains("ForgeData", 10)) tag.getCompound("ForgeData").remove(ORIGIN); // la photo d'origine ne suit pas le véhicule au garage
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
