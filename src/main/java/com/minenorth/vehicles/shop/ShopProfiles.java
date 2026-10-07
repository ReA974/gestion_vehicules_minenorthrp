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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** config/minenorth_vehicles/shops.json : les différents vendeurs (camions, ferme, bateaux, aviation...). Rechargé à chaud. */
public final class ShopProfiles {
    public static final class Profile {
        public String id, title = "&9&lVendeur";
        public final List<String> categories = new ArrayList<>();
        public double placeRadius = -1;
        /** Donne 2 plaques (mts:gvp.eu_plate) et inscrit la vente au fichier des immatriculations. null = ancien comportement (deviné d'après le titre). */
        public Boolean plates;

        public boolean givesPlates() {
            if (plates != null) return plates;
            String t = title.toLowerCase(Locale.ROOT);
            return t.contains("voiture") || t.contains("camion") || t.contains("moto");
        }
    }

    private static final Map<String, Profile> PROFILES = new LinkedHashMap<>();
    private static long loadedMtime = -1;

    private static final String SAMPLE = """
            {
              "shops": {
                "default":  { "title": "&9&lVendeur - Catégories", "categories": [], "plates": true },
                "camions":  { "title": "&6&lCamions et Remorques", "categories": ["Camion", "Remorque"], "plates": true },
                "ferme":    { "title": "&a&lVéhicules de ferme", "categories": ["Ferme"] },
                "bateaux":  { "title": "&b&lBateaux", "categories": ["Bateau"], "placeRadius": 5.0 },
                "aviation": { "title": "&d&lAvions et Hélicoptères", "categories": ["Avion", "Hélicoptère"], "placeRadius": 6.0 }
              }
            }
            """;

    private ShopProfiles() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("minenorth_vehicles").resolve("shops.json");
    }

    private static synchronized void ensureLoaded() {
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, SAMPLE, StandardCharsets.UTF_8);
                VehiclesMod.LOGGER.warn("[Vendeurs] shops.json absent : exemple créé dans {}", f);
            }
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m == loadedMtime) return;
            loadedMtime = m;
            PROFILES.clear();
            JsonObject root;
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("shops").entrySet()) {
                JsonObject o = en.getValue().getAsJsonObject();
                Profile p = new Profile();
                p.id = en.getKey().toLowerCase(Locale.ROOT);
                if (o.has("title")) p.title = o.get("title").getAsString();
                if (o.has("categories")) for (JsonElement c : o.getAsJsonArray("categories")) p.categories.add(c.getAsString());
                if (o.has("placeRadius")) p.placeRadius = o.get("placeRadius").getAsDouble();
                if (o.has("plates")) p.plates = o.get("plates").getAsBoolean();
                PROFILES.put(p.id, p);
            }
        } catch (Exception ex) {
            VehiclesMod.LOGGER.error("[Vendeurs] Erreur de lecture de " + f, ex);
        }
    }

    public static Profile get(String id) {
        ensureLoaded();
        return PROFILES.get(id.toLowerCase(Locale.ROOT));
    }

    public static Collection<String> ids() {
        ensureLoaded();
        return new ArrayList<>(PROFILES.keySet());
    }
}
