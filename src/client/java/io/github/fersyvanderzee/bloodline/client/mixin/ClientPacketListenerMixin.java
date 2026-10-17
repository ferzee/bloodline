package io.github.fersyvanderzee.bloodline.client.mixin;

import io.github.fersyvanderzee.bloodline.client.ChronicleScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the family Chronicle as Bloodline Chronicles' illustrated book instead of a plain written book. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	@Inject(method = "handleOpenBook", cancellable = true, at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
		shift = At.Shift.AFTER))
	private void bloodline$openChronicle(ClientboundOpenBookPacket packet, CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null) return;
		ItemStack held = minecraft.player.getItemInHand(packet.hand());
		CustomData data = held.get(DataComponents.CUSTOM_DATA);
		WrittenBookContent content = held.get(DataComponents.WRITTEN_BOOK_CONTENT);
		if (data == null || content == null || !data.copyTag().contains("bloodline_chronicle")) return;
		minecraft.gui.setScreen(new ChronicleScreen(content.getPages(minecraft.isTextFilteringEnabled())));
		ci.cancel();
	}
}
