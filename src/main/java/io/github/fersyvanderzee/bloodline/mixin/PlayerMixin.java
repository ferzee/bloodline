package io.github.fersyvanderzee.bloodline.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.fersyvanderzee.bloodline.Lineages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Shows the family member's name ("Bram Ashford") in chat and death messages instead of the account name. */
@Mixin(Player.class)
abstract class PlayerMixin {
	@WrapOperation(method = "getDisplayName", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;getName()Lnet/minecraft/network/chat/Component;"))
	private Component bloodline$useFamilyName(Player player, Operation<Component> original) {
		Component name = original.call(player);
		return player instanceof ServerPlayer serverPlayer ? Lineages.displayName(serverPlayer, name) : name;
	}
}
