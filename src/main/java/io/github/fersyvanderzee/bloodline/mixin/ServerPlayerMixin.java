package io.github.fersyvanderzee.bloodline.mixin;

import io.github.fersyvanderzee.bloodline.Lineages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shows the family member's name in the tab list. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerMixin {
	@Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
	private void bloodline$useFamilyName(CallbackInfoReturnable<Component> cir) {
		if (cir.getReturnValue() == null) {
			ServerPlayer self = (ServerPlayer) (Object) this;
			Component name = Lineages.displayName(self, null);
			if (name != null) cir.setReturnValue(name);
		}
	}
}
