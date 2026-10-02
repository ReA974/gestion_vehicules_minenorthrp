package com.minenorth.vehicles.fourriere;

import com.minenorth.vehicles.VehiclesMod;
import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.garage.GarageData;
import com.minenorth.vehicles.miscs.Gui;
import com.minenorth.vehicles.miscs.Msg;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = VehiclesMod.MODID)
public final class ImpoundCommands {
    private ImpoundCommands() {}

    private static boolean admin(CommandSourceStack s) {
        return s.hasPermission(VehicleConfig.COMMAND_LEVEL.get());
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("fourrierezone").requires(ImpoundCommands::admin)
                .then(Commands.literal("set").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    ImpoundData d = ImpoundData.get(p.getServer());
                    d.hasZone = true;
                    d.zoneDim = p.level().dimension().location().toString();
                    d.zx = p.getX();
                    d.zy = p.getY();
                    d.zz = p.getZ();
                    d.setDirty();
                    Msg.send(p, "&aPoint de sortie de la fourrière défini à ta position actuelle.");
                    return 1;
                })));

        // PNJ de la fourrière (exécuté par la console / un PNJ)
        e.getDispatcher().register(Commands.literal("fourrieremenu").requires(ImpoundCommands::admin)
                .executes(c -> {
                    Impound.openRecoverMenu(c.getSource().getPlayerOrException());
                    return 1;
                })
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    Impound.openRecoverMenu(EntityArgument.getPlayer(c, "player"));
                    return 1;
                })));

        e.getDispatcher().register(Commands.literal("fourriereadmin").requires(ImpoundCommands::admin)
                .then(Commands.literal("reset").then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(ImpoundCommands::reset)))
                .then(Commands.literal("remove").then(Commands.argument("target", GameProfileArgument.gameProfile())
                        .executes(ImpoundCommands::remove))));
    }

    private static GameProfile target(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return GameProfileArgument.getGameProfiles(c, "target").iterator().next();
    }

    private static int reset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        GameProfile gp = target(c);
        ImpoundData d = ImpoundData.get(c.getSource().getServer());
        d.vehicles.remove(gp.getId());
        d.setDirty();
        c.getSource().sendSuccess(() -> Gui.comp(VehicleConfig.PREFIX.get() + " &aLa fourrière de " + gp.getName() + " a été vidée."), false);
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        ServerPlayer p = c.getSource().getPlayerOrException();
        GameProfile gp = target(c);
        ImpoundData d = ImpoundData.get(p.getServer());
        List<GarageData.Stored> list = d.of(gp.getId());
        if (list.isEmpty()) {
            Msg.send(p, "&cCe joueur n'a aucun véhicule en fourrière.");
            return 0;
        }
        UUID id = gp.getId();
        Gui.open(p, "&cRetirer un véhicule (admin)", Math.min(6, (list.size() + 8) / 9), Garage.listItems(list), slot -> {
            if (slot < list.size()) Gui.later(p, () -> {
                p.closeContainer();
                List<GarageData.Stored> cur = d.of(id);
                if (slot < cur.size()) {
                    cur.remove(slot);
                    d.setDirty();
                    Msg.send(p, "&aVéhicule retiré de la fourrière.");
                }
            });
        });
        return 1;
    }
}
