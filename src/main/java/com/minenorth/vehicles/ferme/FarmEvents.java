package com.minenorth.vehicles.ferme;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Gui;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Récolte / plantation automatique par les véhicules agricoles + pousse accélérée (port de farmble.sk). */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class FarmEvents {
    public record Match(FarmConfig.Profile profile, Entity ridden, Entity matched) {}

    private record Cached(FarmConfig.Profile profile, long expire) {}

    private static final Map<UUID, Cached> CACHE = new HashMap<>();
    private static final Map<UUID, Long> NEXT_MSG = new HashMap<>();

    private FarmEvents() {}

    @SubscribeEvent
    public static void onStarting(ServerStartingEvent e) {
        int n = FarmConfig.load();
        VehiclesMod.LOGGER.info("[Ferme] {} profil(s) de véhicule, {} culture(s).", n, FarmConfig.CROPS.size());
    }

    private static String dimOf(ServerPlayer p) {
        return p.level().dimension().location().toString();
    }

    @SubscribeEvent
    public static void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !FarmConfig.enabled) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long now = server.getTickCount();
        FarmData data = FarmData.get(server);

        if (now % FarmConfig.growthInterval == 0) grow(server, data);
        if (FarmConfig.PROFILES.isEmpty() || now % FarmConfig.baseInterval != 0) return;

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getVehicle() == null) continue;
            if (FarmConfig.requireZone && !data.inZone(dimOf(p), p.getX(), p.getZ())) continue;
            Match m = findProfile(p, (ServerLevel) p.level(), now);
            if (m == null || now % m.profile().interval != 0) continue;
            work(p, (ServerLevel) p.level(), m, data);
        }
    }

    // ------------------------------------------------------------------ détection du véhicule

    public static Match findProfile(ServerPlayer p, ServerLevel level, long now) {
        Entity ridden = MtsBridge.vehicleOf(p);
        if (ridden == null) return null;
        List<Entity> candidates = new ArrayList<>();
        candidates.add(ridden);
        if (FarmConfig.scanRadius > 0) {
            for (Entity v : level.getEntities((Entity) null, p.getBoundingBox().inflate(FarmConfig.scanRadius), MtsBridge::isVehicle)) {
                if (v != ridden) candidates.add(v);
            }
        }
        for (Entity c : candidates) {
            FarmConfig.Profile prof = profileOf(c, now);
            if (prof != null) return new Match(prof, ridden, c);
        }
        return null;
    }

    /** Cherche les mots-clés dans tout le NBT du véhicule (nom, pièces montées...) ; résultat mis en cache 5 s. */
    private static FarmConfig.Profile profileOf(Entity v, long now) {
        Cached c = CACHE.get(v.getUUID());
        if (c != null && now < c.expire()) return c.profile();
        String s = MtsBridge.snapshot(v).toString().toLowerCase(Locale.ROOT);
        FarmConfig.Profile found = null;
        outer:
        for (FarmConfig.Profile pr : FarmConfig.PROFILES) {
            if (!pr.enabled) continue;
            for (String k : pr.keywords) {
                if (s.contains(k)) {
                    found = pr;
                    break outer;
                }
            }
        }
        CACHE.put(v.getUUID(), new Cached(found, now + 100));
        if (CACHE.size() > 500) CACHE.values().removeIf(x -> x.expire() < now);
        return found;
    }

    // ------------------------------------------------------------------ travail autour du véhicule

    private static boolean airOrGrass(BlockState st) {
        return st.isAir() || st.is(Blocks.GRASS) || st.is(Blocks.TALL_GRASS) || st.is(Blocks.FERN);
    }

    private static boolean hasSpace(ServerPlayer p, ItemStack probe) {
        Inventory inv = p.getInventory();
        return inv.getSlotWithRemainingSpace(probe) >= 0 || inv.getFreeSlot() >= 0;
    }

    private static void notifyFull(ServerPlayer p, FarmConfig.Profile prof) {
        long now = p.getServer().getTickCount();
        Long next = NEXT_MSG.get(p.getUUID());
        if (next != null && now < next) return;
        NEXT_MSG.put(p.getUUID(), now + FarmConfig.msgCooldownSeconds * 20L);
        p.sendSystemMessage(Gui.comp(FarmConfig.inventoryFullMessage.replace("%s", prof.name)));
    }

    private static void give(ServerPlayer p, ServerLevel level, FarmConfig.Crop crop, FarmConfig.Profile prof) {
        int base = prof.yieldMin + (prof.yieldMax > prof.yieldMin ? level.random.nextInt(prof.yieldMax - prof.yieldMin + 1) : 0);
        double v = base * prof.multipliers.getOrDefault(crop.id, 1.0);
        int amount = (int) Math.floor(v);
        if (level.random.nextDouble() < v - amount) amount++;
        if (amount > 0) ItemHandlerHelper.giveItemToPlayer(p, new ItemStack(crop.item, amount));
    }

    private static void work(ServerPlayer p, ServerLevel level, Match m, FarmData data) {
        FarmConfig.Profile prof = m.profile();
        String dim = dimOf(p);
        Vec3 c = prof.centerOnVehicle ? m.ridden().position() : p.position();
        double r2 = prof.radius * prof.radius;
        int ir = (int) Math.ceil(prof.radius);
        BlockPos origin = BlockPos.containing(c);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int budget = prof.maxPerPass;

        for (int dx = -ir; dx <= ir && budget > 0; dx++) {
            for (int dy = -ir; dy <= ir && budget > 0; dy++) {
                for (int dz = -ir; dz <= ir && budget > 0; dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (Vec3.atCenterOf(pos).distanceToSqr(c) > r2 || !level.hasChunkAt(pos)) continue;
                    BlockState st = level.getBlockState(pos);
                    FarmConfig.Crop crop = FarmConfig.CROPS.get(st.getBlock());

                    if (crop != null) {
                        // culture mûre : récolte + replantation
                        if (!prof.harvest || st.getValue(crop.age) < crop.maxAge) continue;
                        if (!hasSpace(p, new ItemStack(crop.item))) {
                            notifyFull(p, prof);
                            continue;
                        }
                        BlockPos below = pos.below();
                        if (level.getBlockState(below).is(Blocks.DIRT)) level.setBlock(below, Blocks.FARMLAND.defaultBlockState(), 3);
                        level.setBlock(pos, st.setValue(crop.age, 0), 3);
                        give(p, level, crop, prof);
                        data.track(dim, pos.immutable());
                        budget--;
                    } else if (prof.plant != null && (st.is(Blocks.DIRT) || st.is(Blocks.FARMLAND))) {
                        // terre ou labour sans plante : on laboure et on sème
                        BlockPos above = pos.above();
                        if (!airOrGrass(level.getBlockState(above))) continue;
                        if (st.is(Blocks.DIRT)) level.setBlock(pos.immutable(), Blocks.FARMLAND.defaultBlockState(), 3);
                        BlockState seed = prof.plant.defaultBlockState();
                        if (!seed.canSurvive(level, above)) continue;
                        level.setBlock(above, seed, 3);
                        data.track(dim, above.immutable());
                        budget--;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ pousse accélérée

    private static void grow(MinecraftServer server, FarmData data) {
        boolean active = false;
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (FarmConfig.requireZone) {
            for (ServerPlayer p : players) {
                if (data.inZone(dimOf(p), p.getX(), p.getZ())) {
                    active = true;
                    break;
                }
            }
        } else {
            active = !players.isEmpty();
        }
        if (!active) return;

        for (ServerLevel level : server.getAllLevels()) {
            Set<Long> set = data.tracked.get(level.dimension().location().toString());
            if (set == null || set.isEmpty()) continue;
            Iterator<Long> it = set.iterator();
            while (it.hasNext()) {
                BlockPos pos = BlockPos.of(it.next());
                if (!level.hasChunkAt(pos)) continue;
                BlockState st = level.getBlockState(pos);
                FarmConfig.Crop crop = FarmConfig.CROPS.get(st.getBlock());
                if (crop == null) { // plus une culture : on arrête de la suivre
                    it.remove();
                    data.setDirty();
                    continue;
                }
                int age = st.getValue(crop.age);
                if (age >= crop.maxAge) continue;
                int add = FarmConfig.growthMin + (FarmConfig.growthMax > FarmConfig.growthMin
                        ? level.random.nextInt(FarmConfig.growthMax - FarmConfig.growthMin + 1) : 0);
                level.setBlock(pos, st.setValue(crop.age, Math.min(crop.maxAge, age + add)), 2);
            }
        }
    }
}
