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
        // voir / retirer / vider la fourrière d'un joueur : panneau /mnadmin (mod minenorth_admin)
    }
}
