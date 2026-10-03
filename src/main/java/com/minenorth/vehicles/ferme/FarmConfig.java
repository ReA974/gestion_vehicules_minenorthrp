package com.minenorth.vehicles.ferme;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.minenorth.vehicles.VehiclesMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** config/minenorth_vehicles/farm.json : quels véhicules font de la récolte, et avec quel rendement. */
public final class FarmConfig {
    public static final class Crop {
        public String id;
        public Block block;
        public Item item;
        public IntegerProperty age;
        public int maxAge;
    }

    public static final class Profile {
        public String name = "Récolte";
        public boolean enabled = true;
        public final List<String> keywords = new ArrayList<>();
        public double radius = 3.5;
        public int interval = 2;
        public boolean harvest = true;
        public Block plant;
        public int yieldMin = 1, yieldMax = 1;
        public int maxPerPass = 64;
        public boolean centerOnVehicle = false;
        public final Map<String, Double> multipliers = new HashMap<>();
    }

    public static boolean enabled = true;
    public static boolean requireZone = true;
    public static int baseInterval = 2;
    public static int scanRadius = 8;
    public static int growthInterval = 60;
    public static int growthMin = 1, growthMax = 2;
    public static int msgCooldownSeconds = 3;
    public static String inventoryFullMessage = "&c[%s] Votre inventaire est plein !";

    public static final Map<Block, Crop> CROPS = new HashMap<>();
    public static final List<Profile> PROFILES = new ArrayList<>();

    private static final String SAMPLE = """
            {
              "settings": {
                "enabled": true,
                "requireZone": true,
                "intervalTicks": 2,
                "scanRadius": 8,
                "growthIntervalTicks": 60,
                "growthMin": 1,
                "growthMax": 2,
                "messageCooldownSeconds": 3,
                "inventoryFullMessage": "&c[%s] Votre inventaire est plein !"
              },
              "crops": [
                { "block": "minecraft:wheat", "item": "minecraft:wheat" }
              ],
              "vehicles": [
                {
                  "name": "Moissonneuse",
                  "enabled": true,
                  "keywords": ["harvester", "cutter", "header", "grain", "combine", "agriculture", "don1500"],
                  "radius": 3.5,
                  "intervalTicks": 2,
                  "harvest": true,
                  "plant": "minecraft:wheat",
                  "yieldMin": 1,
                  "yieldMax": 1,
                  "maxPerPass": 64,
                  "center": "player",
                  "multipliers": {}
                },
                {
                  "name": "Tracteur (exemple, désactivé)",
                  "enabled": false,
                  "keywords": ["tractor"],
                  "radius": 2.5,
                  "intervalTicks": 4,
                  "harvest": false,
                  "plant": "minecraft:wheat",
                  "yieldMin": 1,
                  "yieldMax": 1,
                  "maxPerPass": 16,
                  "center": "vehicle",
                  "multipliers": {}
                }
              ]
            }
            """;

    private FarmConfig() {}

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("minenorth_vehicles").resolve("farm.json");
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static double dbl(JsonObject o, String k, double d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsDouble() : d;
    }

    private static int integer(JsonObject o, String k, int d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d;
    }

    private static boolean bool(JsonObject o, String k, boolean d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsBoolean() : d;
    }

    private static Block block(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Blocks.AIR;
        Block b = ForgeRegistries.BLOCKS.getValue(rl);
        return b == null ? Blocks.AIR : b;
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        Item i = ForgeRegistries.ITEMS.getValue(rl);
        return i == null ? Items.AIR : i;
    }

    /** @return nombre de profils de véhicules chargés, -1 en cas d'erreur */
    public static int load() {
        CROPS.clear();
        PROFILES.clear();
        Path f = file();
        try {
            if (!Files.exists(f)) {
                Files.createDirectories(f.getParent());
                Files.writeString(f, SAMPLE, StandardCharsets.UTF_8);
                VehiclesMod.LOGGER.warn("[Ferme] farm.json absent : exemple créé dans {}", f);
            }
            JsonObject root;
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                root = JsonParser.parseReader(r).getAsJsonObject();
            }
            JsonObject s = root.has("settings") ? root.getAsJsonObject("settings") : new JsonObject();
            enabled = bool(s, "enabled", true);
            requireZone = bool(s, "requireZone", true);
            scanRadius = Math.max(0, integer(s, "scanRadius", 8));
            growthInterval = Math.max(1, integer(s, "growthIntervalTicks", 60));
            growthMin = Math.max(0, integer(s, "growthMin", 1));
            growthMax = Math.max(growthMin, integer(s, "growthMax", 2));
            msgCooldownSeconds = Math.max(0, integer(s, "messageCooldownSeconds", 3));
            inventoryFullMessage = str(s, "inventoryFullMessage", inventoryFullMessage);

            if (root.has("crops")) {
                for (JsonElement el : root.getAsJsonArray("crops")) {
                    JsonObject o = el.getAsJsonObject();
                    String bid = str(o, "block", "");
                    Block b = block(bid);
                    Item it = item(str(o, "item", bid));
                    if (b == Blocks.AIR || it == Items.AIR) {
                        VehiclesMod.LOGGER.warn("[Ferme] culture ignorée (bloc ou item introuvable) : {}", o);
                        continue;
                    }
                    Property<?> prop = b.getStateDefinition().getProperty("age");
                    if (!(prop instanceof IntegerProperty ip)) {
                        VehiclesMod.LOGGER.warn("[Ferme] {} n'a pas de propriété 'age', ignoré.", bid);
                        continue;
                    }
                    Crop c = new Crop();
                    c.id = bid;
                    c.block = b;
                    c.item = it;
                    c.age = ip;
                    c.maxAge = Collections.max(ip.getPossibleValues());
                    CROPS.put(b, c);
                }
            }

            int base = Integer.MAX_VALUE;
            if (root.has("vehicles")) {
                for (JsonElement el : root.getAsJsonArray("vehicles")) {
                    JsonObject o = el.getAsJsonObject();
                    Profile p = new Profile();
                    p.name = str(o, "name", "Récolte");
                    p.enabled = bool(o, "enabled", true);
                    if (o.has("keywords")) {
                        for (JsonElement k : o.getAsJsonArray("keywords")) p.keywords.add(k.getAsString().toLowerCase(Locale.ROOT));
                    }
                    p.radius = Math.max(0.5, dbl(o, "radius", 3.5));
                    p.interval = Math.max(1, integer(o, "intervalTicks", 2));
                    p.harvest = bool(o, "harvest", true);
                    String plant = str(o, "plant", "");
                    if (!plant.isEmpty()) {
                        Block pb = block(plant);
                        if (pb != Blocks.AIR && CROPS.containsKey(pb)) p.plant = pb;
                        else VehiclesMod.LOGGER.warn("[Ferme] '{}' : culture à planter '{}' absente de la liste \"crops\".", p.name, plant);
                    }
                    p.yieldMin = Math.max(0, integer(o, "yieldMin", integer(o, "yield", 1)));
                    p.yieldMax = Math.max(p.yieldMin, integer(o, "yieldMax", p.yieldMin));
                    p.maxPerPass = Math.max(1, integer(o, "maxPerPass", 64));
                    p.centerOnVehicle = "vehicle".equalsIgnoreCase(str(o, "center", "player"));
                    if (o.has("multipliers")) {
                        for (Map.Entry<String, JsonElement> en : o.getAsJsonObject("multipliers").entrySet()) {
                            p.multipliers.put(en.getKey(), en.getValue().getAsDouble());
                        }
                    }
                    PROFILES.add(p);
                    if (p.enabled) base = Math.min(base, p.interval);
                }
            }
            baseInterval = base == Integer.MAX_VALUE ? 2 : base;
            return PROFILES.size();
        } catch (Exception ex) {
            VehiclesMod.LOGGER.error("[Ferme] Erreur de lecture de " + f, ex);
            return -1;
        }
    }
}
