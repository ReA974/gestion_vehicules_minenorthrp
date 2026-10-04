package com.minenorth.vehicles.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** config/minenorth-vehicles.toml (réglages). Le catalogue du vendeur est dans config/minenorth_vehicles/catalog.json */
public final class VehicleConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> PREFIX, VEHICLE_ENTITY, SEAT_ENTITY, KEY_ITEM, KEY_NAME_PREFIX;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> CURRENCY, ZONE_MESSAGES;
    public static final ForgeConfigSpec.ConfigValue<String> ENTER_DEFAULT, LEAVE_MSG;
    public static final ForgeConfigSpec.IntValue COMMAND_LEVEL, GARAGE_MAX, CHECK_INTERVAL, SEARCH_RADIUS, PLACE_TIMEOUT;
    public static final ForgeConfigSpec.DoubleValue DEFAULT_ZONE_RADIUS, SHOP_PLACE_RADIUS, PROMPT_MAX_MOVE;
    public static final ForgeConfigSpec.BooleanValue AUTO_PROMPT, GIVE_KEY, THEMED_GUI, CUSTOM_GUI;

    public record Denom(Item item, int value) {}

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("general");
        PREFIX = b.define("prefix", "&9[&bVéhicules&9]");
        COMMAND_LEVEL = b.comment("Niveau d'op requis pour les commandes (2 = op). Sous Arclight les permissions Bukkit ne sont pas lues.")
                .defineInRange("commandPermissionLevel", 2, 0, 4);
        b.pop();

        b.comment("Intégration Immersive Vehicles (MTS)").push("mts");
        VEHICLE_ENTITY = b.comment("Entité qui porte un véhicule MTS").define("vehicleEntity", "mts:builder_existing");
        SEAT_ENTITY = b.comment("Entité sur laquelle le joueur est assis dans un véhicule MTS").define("seatEntity", "mts:builder_seat");
        KEY_ITEM = b.comment("Item clé donné avec un véhicule (vérifie l'id avec F3+H)").define("keyItem", "mts:mts.key");
        KEY_NAME_PREFIX = b.comment("Préfixe du nom de la clé (sert à retirer la clé au rangement)").define("keyNamePrefix", "Clé -");
        GIVE_KEY = b.define("giveKey", true);
        THEMED_GUI = b.comment("Habillage des menus avec le logo MineNorth (nécessite le jar ou le resource pack côté client)")
                .define("themedGui", true);
        CUSTOM_GUI = b.comment("Menus personnalisés style jeu vidéo pour les joueurs qui ont le mod (les autres gardent le coffre). false = coffre pour tous")
                .define("customGui", true);
        SEARCH_RADIUS = b.comment("Rayon de recherche du véhicule autour du siège (blocs)").defineInRange("seatSearchRadius", 12, 2, 64);
        b.pop();

        b.comment("Vendeur").push("shop");
        CURRENCY = b.comment("Billets et pièces acceptés en espèces : \"id=valeur\" (valeur en euros entiers).")
                .defineList("currency", Arrays.asList(
                        "minenorth_eurobank:bill_500e=500",
                        "minenorth_eurobank:bill_200e=200",
                        "minenorth_eurobank:bill_100e=100",
                        "minenorth_eurobank:bill_50e=50",
                        "minenorth_eurobank:bill_20e=20",
                        "minenorth_eurobank:bill_10e=10",
                        "minenorth_eurobank:bill_5e=5",
                        "minenorth_eurobank:coin_2e=2",
                        "minenorth_eurobank:coin_1e=1"), o -> o instanceof String);
        SHOP_PLACE_RADIUS = b.comment("Distance max (blocs) entre le centre de la zone de pose et le véhicule posé")
                .defineInRange("placeRadius", 2.5, 0.5, 32.0);
        PLACE_TIMEOUT = b.comment("Secondes avant que la zone de pose soit libérée si le véhicule n'est pas posé")
                .defineInRange("placeTimeoutSeconds", 300, 10, 7200);
        b.pop();

        b.comment("Garage").push("garage");
        GARAGE_MAX = b.comment("Véhicules max par joueur").defineInRange("maxVehicles", 20, 1, 500);
        DEFAULT_ZONE_RADIUS = b.comment("Rayon par défaut d'une zone garage").defineInRange("defaultZoneRadius", 4.0, 0.5, 128.0);
        AUTO_PROMPT = b.comment("Ouvrir le menu de stockage quand on entre dans une zone garage à bord d'un véhicule")
                .define("autoPrompt", true);
        CHECK_INTERVAL = b.comment("Ticks entre deux vérifications (20 = 1 s)").defineInRange("checkIntervalTicks", 40, 5, 400);
        PROMPT_MAX_MOVE = b.comment("Le menu ne s'ouvre que si le véhicule a bougé de moins de X blocs entre deux vérifications")
                .defineInRange("promptMaxMove", 1.5, 0.0, 50.0);
        ENTER_DEFAULT = b.define("enterMessage", "&a&lTu entres dans une zone garage.");
        LEAVE_MSG = b.define("leaveMessage", "&c&lTu quittes la zone garage.");
        ZONE_MESSAGES = b.comment("Messages d'entrée selon le tag de la zone : \"tag=message\"")
                .defineList("zoneMessages", Arrays.asList(
                        "garage.police=&9&lVous êtes dans la zone garage police.",
                        "garage.pompier=&e&lVous êtes dans la zone garage pompier.",
                        "garage.vip=&e&lVous êtes dans la zone garage VIP."), o -> o instanceof String);
        b.pop();

        SPEC = b.build();
    }

    private VehicleConfig() {}

    public static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return Items.AIR;
        Item it = ForgeRegistries.ITEMS.getValue(rl);
        return it == null ? Items.AIR : it;
    }

    public static List<Denom> currency() {
        List<Denom> out = new ArrayList<>();
        for (String s : CURRENCY.get()) {
            int i = s.lastIndexOf('=');
            if (i < 0) continue;
            Item it = VehicleConfig.item(s.substring(0, i).trim());
            int v;
            try {
                v = Integer.parseInt(s.substring(i + 1).trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (it != Items.AIR && v > 0) out.add(new Denom(it, v));
        }
        out.sort((a, c) -> Integer.compare(c.value(), a.value()));
        return out;
    }
}
