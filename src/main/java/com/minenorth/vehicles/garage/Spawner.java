package com.minenorth.vehicles.garage;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.fourriere.ImpoundConfig;
import com.minenorth.vehicles.handle.MtsBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Fait apparaître un véhicule sauvegardé au point voulu.
 * L'origine d'un modèle MTS n'est pas forcément son centre : quelques ticks après l'apparition on compare le centre
 * de la boîte de l'entité à la cible et, si l'écart est notable, on refait apparaître le véhicule décalé d'autant.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class Spawner {
    private record Task(Entity entity, double x, double z, long due) {}

    private static final List<Task> TASKS = new ArrayList<>();

    private Spawner() {}

    public static Entity spawn(ServerLevel level, CompoundTag nbt, double x, double y, double z, UUID owner) {
        Entity e = MtsBridge.restore(level, nbt, x, y, z);
        if (e == null) return null;
        MtsBridge.setOwner(e, owner);
        level.addFreshEntityWithPassengers(e);
        if (ImpoundConfig.CENTER_SPAWN.get()) {
            TASKS.add(new Task(e, x, z, level.getServer().getTickCount() + 4));
        }
        return e;
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent ev) {
        if (ev.phase != TickEvent.Phase.END || TASKS.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        List<Task> due = new ArrayList<>();
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task t = it.next();
            if (t.due() <= now) {
                due.add(t);
                it.remove();
            }
        }
        for (Task t : due) recenter(t);
    }

    private static void recenter(Task t) {
        Entity e = t.entity();
        if (e.isRemoved() || !(e.level() instanceof ServerLevel level)) return;
        Vec3 c = e.getBoundingBox().getCenter();
        double dx = t.x() - c.x, dz = t.z() - c.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        VehiclesMod.LOGGER.info("[Vehicules] spawn : origine ({}, {}), centre de la boîte ({}, {}), écart à la cible {} blocs",
                String.format("%.1f", e.getX()), String.format("%.1f", e.getZ()),
                String.format("%.1f", c.x), String.format("%.1f", c.z), String.format("%.1f", dist));
        if (dist < 0.75 || dist > 12) return;

        CompoundTag snap = MtsBridge.snapshot(e); // le propriétaire (ForgeData) est conservé
        double nx = e.getX() + dx, nz = e.getZ() + dz, y = e.getY();
        e.discard();
        Entity n = MtsBridge.restore(level, snap, nx, y, nz);
        if (n != null) level.addFreshEntityWithPassengers(n);
    }
}
