package com.minenorth.vehicles.depannage;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.handle.MtsBridge;
import com.minenorth.vehicles.miscs.Msg;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cric de dépannage. Un véhicule « bloqué » (enfoncé dans un bloc, sous le monde) est replacé sur l'asphalte le plus proche.
 * Le déplacement passe par le NBT complet du véhicule (même technique que le recentrage du Spawner) : pièces, carburant,
 * coffres, propriétaire, assurance, plaque... sont conservés tels quels. Le kit n'est consommé qu'en cas de succès.
 */
@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class Unstuck {
    private static final double REACH = 8.0;
    private static final int VERTICAL = 16;
    private static final Map<UUID, Long> LAST_USE = new HashMap<>();
    private static final Map<Integer, int[][]> OFFSETS = new HashMap<>();

    private Unstuck() {}

    // ------------------------------------------------------------------ entrées

    /** Clic droit sur une entité (véhicule MTS ou siège) avec le kit en main. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract e) {
        if (!(e.getEntity() instanceof ServerPlayer sp) || e.getLevel().isClientSide()) return;
        ItemStack st = sp.getItemInHand(e.getHand());
        if (st.getItem() != ModItems.UNSTUCK_KIT.get()) return;
        e.setCanceled(true);
        e.setCancellationResult(InteractionResult.SUCCESS);
        use(sp, e.getHand(), e.getTarget());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        LAST_USE.remove(e.getEntity().getUUID());
    }

    // ------------------------------------------------------------------ logique

    /** clicked : entité cliquée (null = clic dans le vide, le véhicule visé est alors cherché). */
    public static void use(ServerPlayer p, InteractionHand hand, Entity clicked) {
        ServerLevel level = p.serverLevel();
        long now = level.getServer().getTickCount();
        Long last = LAST_USE.get(p.getUUID());
        if (last != null && now - last < VehicleConfig.UNSTUCK_COOLDOWN.get()) return;
        LAST_USE.put(p.getUUID(), now);

        Entity v = target(p, level, clicked);
        if (v == null) {
            Msg.send(p, "&cAucun véhicule visé. Clique directement sur le véhicule bloqué.");
            return;
        }
        if (VehicleConfig.UNSTUCK_REQUIRE_BUGGED.get() && !isBugged(level, v)) {
            Msg.send(p, "&eCe véhicule ne semble pas bloqué : le cric n'a pas été utilisé.");
            return;
        }
        Vec3 spot = findRoad(level, v);
        if (spot == null) {
            Msg.send(p, "&cAucune route en asphalte à proximité (" + VehicleConfig.UNSTUCK_RADIUS.get() + " blocs) : le cric n'a pas été utilisé.");
            return;
        }
        if (!teleport(level, v, spot)) {
            Msg.send(p, "&cImpossible de déplacer ce véhicule : le cric n'a pas été utilisé.");
            return;
        }
        ItemStack st = p.getItemInHand(hand);
        if (!p.getAbilities().instabuild) st.shrink(1);
        level.playSound(null, spot.x, spot.y, spot.z, SoundEvents.ANVIL_USE, SoundSource.PLAYERS, 0.6f, 1.4f);
        Msg.send(p, "&aVéhicule replacé sur la route (" + (int) spot.x + ", " + (int) spot.y + ", " + (int) spot.z + ").");
        VehiclesMod.LOGGER.info("[Depannage] {} a replacé un véhicule en {} {} {}", p.getGameProfile().getName(),
                (int) spot.x, (int) spot.y, (int) spot.z);
    }

    private static Entity target(ServerPlayer p, ServerLevel level, Entity clicked) {
        if (clicked != null) {
            if (MtsBridge.isVehicle(clicked)) return clicked;
            if (MtsBridge.typeId(clicked).equals(VehicleConfig.SEAT_ENTITY.get()))
                return MtsBridge.nearest(level, clicked.position(), VehicleConfig.SEARCH_RADIUS.get(), null);
        }
        if (p.getVehicle() != null) {
            Entity own = MtsBridge.vehicleOf(p);
            if (own != null) return own;
        }
        Vec3 eye = p.getEyePosition();
        Vec3 end = eye.add(p.getLookAngle().scale(REACH));
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : level.getEntities(p, p.getBoundingBox().expandTowards(end.subtract(eye)).inflate(2), MtsBridge::isVehicle)) {
            AABB box = e.getBoundingBox().inflate(0.3);
            double d = box.contains(eye) ? 0 : box.clip(eye, end).map(eye::distanceToSqr).orElse(-1.0);
            if (d >= 0 && d < bd) {
                bd = d;
                best = e;
            }
        }
        return best != null ? best : MtsBridge.nearest(level, p.position(), 4, null);
    }

    /** Dans un bloc (au-delà de la tolérance), ou sous le monde. */
    static boolean isBugged(ServerLevel level, Entity v) {
        if (v.getY() < level.getMinBuildHeight() + 1) return true;
        double tol = VehicleConfig.UNSTUCK_TOLERANCE.get();
        AABB box = v.getBoundingBox();
        if (box.getXsize() <= 2 * tol || box.getYsize() <= 2 * tol || box.getZsize() <= 2 * tol) return false;
        return !level.noCollision(v, box.deflate(tol));
    }

    private static boolean isRoad(BlockState st, List<? extends String> prefixes) {
        ResourceLocation k = ForgeRegistries.BLOCKS.getKey(st.getBlock());
        if (k == null || k.getPath().contains("stairs")) return false;
        String id = k.toString();
        for (String pre : prefixes) if (id.startsWith(pre)) return true;
        return false;
    }

    /** Positions (dx, dz) triées par distance croissante dans un disque de rayon r. */
    private static int[][] offsets(int r) {
        return OFFSETS.computeIfAbsent(r, rr -> {
            List<int[]> l = new ArrayList<>();
            for (int dx = -rr; dx <= rr; dx++)
                for (int dz = -rr; dz <= rr; dz++)
                    if (dx * dx + dz * dz <= rr * rr) l.add(new int[]{dx, dz});
            l.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
            return l.toArray(new int[0][]);
        });
    }

    /** Position (origine de l'entité) qui pose le véhicule sur l'asphalte libre le plus proche, ou null. */
    static Vec3 findRoad(ServerLevel level, Entity v) {
        List<? extends String> prefixes = VehicleConfig.UNSTUCK_ROADS.get();
        AABB box = v.getBoundingBox();
        Vec3 c = box.getCenter();
        double offX = c.x - v.getX(), offZ = c.z - v.getZ(), offY = box.minY - v.getY();
        int ox = (int) Math.floor(c.x), oz = (int) Math.floor(c.z);
        int oy = Math.max(level.getMinBuildHeight(), (int) Math.floor(c.y));

        Vec3 best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int[] o : offsets(VehicleConfig.UNSTUCK_RADIUS.get())) {
            if (best != null && (double) (o[0] * o[0] + o[1] * o[1]) > bestD) break;
            int x = ox + o[0], z = oz + o[1];
            if (!level.hasChunkAt(pos.set(x, oy, z))) continue;
            int top = Math.min(level.getMaxBuildHeight() - 1, oy + VERTICAL), bottom = Math.max(level.getMinBuildHeight(), oy - VERTICAL);
            for (int y = top; y >= bottom; y--) {
                pos.set(x, y, z);
                BlockState st = level.getBlockState(pos);
                if (!isRoad(st, prefixes)) continue;
                VoxelShape shape = st.getCollisionShape(level, pos);
                if (shape.isEmpty()) continue;
                double surface = y + shape.max(Direction.Axis.Y);
                double nx = x + 0.5 - offX, nz = z + 0.5 - offZ, ny = surface - offY + 0.02;
                AABB moved = box.move(nx - v.getX(), ny - v.getY(), nz - v.getZ());
                if (!level.noCollision(v, moved)) continue;
                double dx = x + 0.5 - c.x, dy = surface - c.y, dz = z + 0.5 - c.z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = new Vec3(nx, ny, nz);
                }
            }
        }
        return best;
    }

    /** Recrée le véhicule à destination depuis son NBT complet (plus rien n'est perdu), et retire l'ancien. */
    private static boolean teleport(ServerLevel level, Entity v, Vec3 to) {
        CompoundTag snap = MtsBridge.snapshot(v);
        CompoundTag origin = MtsBridge.origin(v);
        for (ServerPlayer pl : level.getServer().getPlayerList().getPlayers()) {
            if (pl.getVehicle() != null && MtsBridge.vehicleOf(pl) == v) pl.stopRiding();
        }
        flatten(snap);
        Entity n = MtsBridge.restore(level, snap, to.x, to.y, to.z);
        if (n == null) return false;
        MtsBridge.discard(v);
        if (origin != null) MtsBridge.setOrigin(n, origin);
        level.addFreshEntityWithPassengers(n);
        return true;
    }

    /** Remet à plat un véhicule couché / retourné (angles MTS pitch et roll) quand ces clés existent dans le NBT. */
    private static void flatten(CompoundTag snap) {
        for (String k : new String[]{"anglesx", "anglesz"}) {
            if (snap.contains(k)) snap.putDouble(k, 0);
        }
    }
}
