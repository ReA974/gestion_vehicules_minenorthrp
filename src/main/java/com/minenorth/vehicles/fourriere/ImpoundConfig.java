package com.minenorth.vehicles.fourriere;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/minenorth-impound.toml */
public final class ImpoundConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> POLICE_ITEM, POLICE_TAG, MECHANIC_MESSAGE;
    public static final ForgeConfigSpec.IntValue PRICE, MAX_PER_OWNER;
    public static final ForgeConfigSpec.BooleanValue CENTER_SPAWN;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.push("impound");
        POLICE_ITEM = b.comment("Item à tenir (en sneak + clic droit sur un véhicule) pour ouvrir le menu de mise en fourrière")
                .define("policeItem", "minecraft:blaze_rod");
        POLICE_TAG = b.comment("Tag requis pour la police : /tag <joueur> add police.check (les op passent toujours)")
                .define("policeTag", "police.check");
        PRICE = b.comment("Prix pour récupérer un véhicule en fourrière").defineInRange("price", 3000, 0, 100_000_000);
        MAX_PER_OWNER = b.comment("Véhicules max en fourrière par joueur").defineInRange("maxPerOwner", 50, 1, 500);
        MECHANIC_MESSAGE = b.comment("Message diffusé quand la police appelle le mécano")
                .define("mechanicMessage", "&6&lUn mécano est demandé pour un véhicule mal garé !");
        CENTER_SPAWN = b.comment("Recentre automatiquement les véhicules sortis (garage / fourrière) sur le point cible")
                .define("autoCenter", true);
        b.pop();
        SPEC = b.build();
    }

    private ImpoundConfig() {}
}
