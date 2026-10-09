package com.minenorth.vehicles;

import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.fourriere.ImpoundConfig;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(VehiclesMod.MODID)
public class VehiclesMod {
    public static final String MODID = "minenorth_rp_vehicles";
    public static final Logger LOGGER = LogUtils.getLogger();

    public VehiclesMod() {
        com.minenorth.vehicles.depannage.ModItems.register(net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        // Config côté serveur uniquement : le client ne crée aucun fichier (les valeurs par défaut s'appliquent).
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.DEDICATED_SERVER) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, VehicleConfig.SPEC, "minenorth-garage.toml");
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ImpoundConfig.SPEC, "minenorth-fourriere.toml");
            ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, com.minenorth.vehicles.assurance.InsuranceConfig.SPEC, "minenorth-assurance.toml");
        }
    }
}
