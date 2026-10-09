package com.minenorth.vehicles.depannage;

import com.minenorth.vehicles.config.VehicleConfig;
import com.minenorth.vehicles.garage.Garage;
import com.minenorth.vehicles.miscs.Msg;
import com.minenorth.vehicles.shop.ShopMulti;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Équivalent en item des commandes /garagemenu et /vendeurvehicule (clic droit). Réservé au niveau d'op des commandes. */
public class MenuItem extends Item {
    public enum Kind { GARAGE, SHOP }

    private final Kind kind;

    public MenuItem(Kind kind) {
        super(new Item.Properties().stacksTo(1));
        this.kind = kind;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            if (!sp.hasPermissions(VehicleConfig.COMMAND_LEVEL.get())) {
                Msg.send(sp, "&cCet objet est réservé à l'administration.");
            } else if (kind == Kind.GARAGE) {
                Garage.openMenu(sp);
            } else {
                ShopMulti.open(sp, "default");
            }
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.translatable(getDescriptionId() + ".tooltip").withStyle(ChatFormatting.GRAY));
    }
}
