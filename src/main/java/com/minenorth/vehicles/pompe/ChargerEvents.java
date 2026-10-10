package com.minenorth.vehicles.pompe;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashSet;
import java.util.Set;

/**
 * Bornes de recharge électriques MTS toujours pleines (option pompe.chargersAlwaysFull) : une fois par seconde, les bornes des
 * chunks autour de chaque joueur reçoivent un stock d'énergie plein. Sans cela elles restent vides tant qu'aucun courant Forge Energy
 * ne les alimente.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class ChargerEvents {
    private ChargerEvents() {}

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.getServer().getTickCount() % 20 != 7) return;
        if (!VehicleConfig.CHARGERS_ALWAYS_FULL.get()) return;
        Set<Long> done = new HashSet<>();
        for (ServerPlayer p : e.getServer().getPlayerList().getPlayers()) {
            ServerLevel level = p.serverLevel();
            ChunkPos c = p.chunkPosition();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int x = c.x + dx, z = c.z + dz;
                    long key = ChunkPos.asLong(x, z) ^ ((long) level.dimension().location().hashCode() << 32);
                    if (!done.add(key)) continue;
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null) continue;
                    for (BlockEntity be : chunk.getBlockEntities().values()) MtsPump.keepChargerFull(be);
                }
            }
        }
    }
}
