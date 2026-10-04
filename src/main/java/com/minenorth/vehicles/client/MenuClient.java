package com.minenorth.vehicles.client;

import com.minenorth.vehicles.menu.OpenMenuPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class MenuClient {
    private MenuClient() {}

    public static void open(OpenMenuPacket m) {
        Minecraft.getInstance().setScreen(new MenuScreen(m));
    }
}
