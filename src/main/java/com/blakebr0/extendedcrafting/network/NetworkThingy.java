package com.blakebr0.extendedcrafting.network;

import com.blakebr0.extendedcrafting.Tags;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class NetworkThingy {

	public static final SimpleNetworkWrapper THINGY = new SimpleNetworkWrapper(Tags.MODID);

	public static void init() {
		THINGY.registerMessage(EjectModeSwitchPacket.Handler.class, EjectModeSwitchPacket.class, 1, Side.SERVER);
		THINGY.registerMessage(InterfaceRecipeChangePacket.Handler.class, InterfaceRecipeChangePacket.class, 2, Side.SERVER);
		THINGY.registerMessage(InterfaceAutoChangePacket.Handler.class, InterfaceAutoChangePacket.class, 3, Side.SERVER);
		THINGY.registerMessage(InputLimitSwitchPacket.Handler.class, InputLimitSwitchPacket.class, 4, Side.SERVER);
	}
}
