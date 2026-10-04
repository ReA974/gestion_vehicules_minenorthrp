package com.minenorth.vehicles.handle;

import com.minenorth.vehicles.VehiclesMod;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plein des véhicules MTS (vendeur) (Immersive Vehicles / MTS), par réflexion : pas de dépendance de compilation à MTS.
 * Les véhicules MTS posés depuis un item neuf ont un réservoir vide (sauf si leur JSON a un defaultFuelQty).
 */
public final class MtsFuel {
    private MtsFuel() {}

    public enum Result { DONE, RETRY, IMPOSSIBLE }

    private static boolean init, ok;
    private static Field builderEntity, fuelTank, settings, fuelCfg, fuels;
    private static Method check, fill, fluidLevel, maxLevel;

    private static boolean init() {
        if (init) return ok;
        init = true;
        try {
            Class<?> builder = Class.forName("mcinterface1201.BuilderEntityExisting");
            builderEntity = builder.getDeclaredField("entity");
            builderEntity.setAccessible(true);
            Class<?> powered = Class.forName("minecrafttransportsimulator.entities.instances.AEntityVehicleE_Powered");
            fuelTank = powered.getField("fuelTank");
            check = powered.getMethod("checkFuelTankCompatibility", String.class);
            Class<?> tank = Class.forName("minecrafttransportsimulator.entities.instances.EntityFluidTank");
            fill = tank.getMethod("fill", String.class, String.class, double.class, boolean.class);
            fluidLevel = tank.getMethod("getFluidLevel");
            maxLevel = tank.getMethod("getMaxLevel");
            settings = Class.forName("minecrafttransportsimulator.systems.ConfigSystem").getField("settings");
            fuelCfg = settings.getType().getField("fuel");
            fuels = fuelCfg.getType().getField("fuels");
            ok = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            VehiclesMod.LOGGER.warn("[Vehicules] API MTS introuvable : les véhicules achetés ne seront pas remplis.", e);
        }
        return ok;
    }

    /**
     * Remplit le réservoir. preferred : fluide imposé par la config (vide = le meilleur carburant accepté
     * par les moteurs, d'après la config MTS). RETRY : véhicule pas encore prêt (moteurs pas encore montés).
     */
    @SuppressWarnings("unchecked")
    public static Result fill(Entity e, String preferred) {
        if (e == null || !init()) return e == null ? Result.RETRY : Result.IMPOSSIBLE;
        try {
            if (!builderEntity.getDeclaringClass().isInstance(e)) return Result.IMPOSSIBLE;
            Object vehicle = builderEntity.get(e);
            if (vehicle == null) return Result.RETRY;
            if (!fuelTank.getDeclaringClass().isInstance(vehicle)) return Result.IMPOSSIBLE;   // pas motorisé
            Object tank = fuelTank.get(vehicle);
            double max = ((Number) maxLevel.invoke(tank)).doubleValue();
            double level = ((Number) fluidLevel.invoke(tank)).doubleValue();
            if (max <= 0) return Result.IMPOSSIBLE;
            if (level >= max) return Result.DONE;

            // fluides candidats : celui de la config du test, puis tous ceux de la config MTS, du plus efficace au moins efficace
            Map<String, Double> candidates = new LinkedHashMap<>();
            if (preferred != null && !preferred.isBlank()) candidates.put(preferred.trim(), Double.MAX_VALUE);
            Map<String, Map<String, Double>> all = (Map<String, Map<String, Double>>) fuels.get(fuelCfg.get(settings.get(null)));
            if (all != null) {
                for (Map<String, Double> m : all.values()) {
                    if (m == null) continue;
                    m.forEach((fluid, potency) -> candidates.merge(fluid, potency == null ? 0 : potency, Math::max));
                }
            }
            candidates.putIfAbsent("electricity", 0.0);
            String best = null;
            double bestPotency = -1;
            boolean noEngine = false;
            for (Map.Entry<String, Double> c : candidates.entrySet()) {
                String r = String.valueOf(check.invoke(vehicle, c.getKey()));
                if (r.equals("NOENGINE")) {
                    noEngine = true;
                    break;
                }
                if (r.equals("VALID") && c.getValue() > bestPotency) {
                    best = c.getKey();
                    bestPotency = c.getValue();
                }
            }
            if (noEngine) return Result.RETRY;   // les moteurs sont ajoutés juste après l'apparition
            if (best == null) return Result.IMPOSSIBLE;
            // réservoir vide : mod "" comme MTS ; déjà entamé : "wildcard" pour compléter quel que soit le mod du fluide
            fill.invoke(tank, best, level > 0 ? "wildcard" : "", max - level, true);   // true : synchronisé avec les clients
            return Result.DONE;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            VehiclesMod.LOGGER.warn("[Vehicules] Impossible de faire le plein du véhicule acheté.", ex);
            return Result.IMPOSSIBLE;
        }
    }
}
