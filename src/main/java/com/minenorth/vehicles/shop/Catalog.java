package com.minenorth.vehicles.shop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minenorth.vehicles.VehiclesMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Catalogue du vendeur : config/minenorth_vehicles/catalog.json */
public final class Catalog {
    public static final class Vehicle {
        public String category, model, color, item;
        public int price;
    }

    public static final List<Vehicle> VEHICLES = new ArrayList<>();
    public static final List<String> CATEGORIES = new ArrayList<>();
    public static final Map<String, String> CATEGORY_ICON = new HashMap<>();
    public static final Map<String, String> COLOR_ICON = new HashMap<>();

    private static final String SAMPLE = """
            {
              "categories": [
                { "name": "Moto", "icon": "minecraft:book" },
                { "name": "Berline", "icon": "minecraft:book" }
              ],
              "colorIcons": {
                "Noir": "minecraft:black_stained_glass",
                "Rouge": "minecraft:red_stained_glass",
                "Bleu": "minecraft:blue_stained_glass"
              },
              "vehicles": [
                { "category": "Moto", "model": "Minsk", "color": "Défaut", "item": "mts:cmacitizenstransport.minsk", "price": 4000 },
                { "category": "Moto", "model": "Minsk", "color": "Noir", "item": "mts:cmacitizenstransport.minsk_black", "price": 4000 }
              ]
            }
            """;

    private Catalog() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("minenorth_vehicles").resolve("catalog.json");
    }

    /** @return nombre de véhicules chargés, ou -1 en cas d'erreur */
    public static int load() {
        // Config côté serveur uniquement : le client ne crée ni ne lit aucun fichier.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER) return 0;
        VEHICLES.clear();
        CATEGORIES.clear();
        CATEGORY_ICON.clear();
        COLOR_ICON.clear();
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, SAMPLE, StandardCharsets.UTF_8);
                VehiclesMod.LOGGER.warn("[Vehicules] catalog.json absent : exemple créé dans {}", f);
            }
            JsonObject root;
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            if (root.has("categories")) {
                for (JsonElement e : root.getAsJsonArray("categories")) {
                    JsonObject o = e.getAsJsonObject();
                    String n = o.get("name").getAsString();
                    CATEGORIES.add(n);
                    if (o.has("icon")) CATEGORY_ICON.put(n, o.get("icon").getAsString());
                }
            }
            if (root.has("colorIcons")) {
                for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("colorIcons").entrySet()) {
                    COLOR_ICON.put(en.getKey(), en.getValue().getAsString());
                }
            }
            for (JsonElement e : root.getAsJsonArray("vehicles")) {
                JsonObject o = e.getAsJsonObject();
                Vehicle v = new Vehicle();
                v.category = o.get("category").getAsString();
                v.model = o.get("model").getAsString();
                v.color = o.has("color") ? o.get("color").getAsString() : "Défaut";
                v.item = o.get("item").getAsString();
                v.price = o.get("price").getAsInt();
                VEHICLES.add(v);
                if (!CATEGORIES.contains(v.category)) CATEGORIES.add(v.category);
            }
            return VEHICLES.size();
        } catch (Exception ex) {
            VehiclesMod.LOGGER.error("[Vehicules] Erreur de lecture de " + f, ex);
            return -1;
        }
    }

    /** Retrouve l'entrée du catalogue d'après l'id d'item MTS. */
    public static Vehicle byItem(String itemId) {
        for (Vehicle v : VEHICLES) if (v.item.equals(itemId)) return v;
        return null;
    }
}
