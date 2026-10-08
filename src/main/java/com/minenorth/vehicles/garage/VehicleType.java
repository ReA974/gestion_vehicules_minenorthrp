package com.minenorth.vehicles.garage;

import com.minenorth.vehicles.shop.Catalog;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

/** Famille d'un véhicule : terrestre, aérien ou maritime. */
public enum VehicleType {
    TERRE("Terrestre", "&6", "terre", "terrestre", "land", "ground", "car", "route"),
    AIR("Aérien", "&b", "air", "aerien", "aérien", "plane", "avion", "heli", "aircraft"),
    MER("Maritime", "&9", "mer", "maritime", "boat", "bateau", "sea", "water");

    public static final String NBT_KEY = "mnrp_type";
    private static final String[] AIR_WORDS = {"plane", "avion", "heli", "jet", "cessna", "airplane", "aircraft", "blimp", "zeppelin", "glider", "drone", "mi8", "uh1", "ah64", "chinook"};
    private static final String[] SEA_WORDS = {"boat", "bateau", "ship", "yacht", "jetski", "jet_ski", "jet-ski", "zodiac", "ferry", "sailboat", "canoe", "kayak", "speedboat", "navire"};

    public final String label, color;
    private final String[] aliases;

    VehicleType(String label, String color, String... aliases) {
        this.label = label;
        this.color = color;
        this.aliases = aliases;
    }

    public String tag() { return color + "&l" + label; }

    public static VehicleType parse(String s) {
        if (s == null) return null;
        String k = s.trim().toLowerCase(Locale.ROOT);
        for (VehicleType t : values()) for (String a : t.aliases) if (a.equals(k)) return t;
        return null;
    }

    /** Catalogue (type du véhicule, sinon de sa catégorie), puis marque posée à l'entrée, puis mots-clés du nom. Terrestre par défaut. */
    public static VehicleType of(String itemId, CompoundTag nbt) {
        Catalog.Vehicle v = Catalog.byItem(itemId);
        if (v != null) {
            VehicleType t = parse(v.type);
            if (t == null) t = parse(Catalog.CATEGORY_TYPE.get(v.category));
            if (t != null) return t;
        }
        VehicleType t = nbt == null ? null : parse(nbt.getCompound("ForgeData").getString(NBT_KEY));
        if (t != null) return t;
        String id = itemId == null ? "" : itemId.toLowerCase(Locale.ROOT);
        for (String w : SEA_WORDS) if (id.contains(w)) return MER;
        for (String w : AIR_WORDS) if (id.contains(w)) return AIR;
        return TERRE;
    }
}
