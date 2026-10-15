package io.github.fersyvanderzee.bloodline.client;

import io.github.fersyvanderzee.bloodline.ScrollPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class BloodlineClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// The server sends choices (heirs, family events, the Sacrifice) as scrolls to players who have the mod.
		ClientPlayNetworking.registerGlobalReceiver(ScrollPayload.TYPE, (scroll, context) -> context.client().gui.setScreen(new ScrollScreen(scroll)));
	}
}
