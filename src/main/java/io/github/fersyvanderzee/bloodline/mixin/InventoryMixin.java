package io.github.fersyvanderzee.bloodline.mixin;

import io.github.fersyvanderzee.bloodline.Heirlooms;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Heirlooms and the family chronicle aren't dropped on death; the heir receives them on respawn. */
@Mixin(Inventory.class)
abstract class InventoryMixin {
	@Shadow
	@Final
	public Player player;

	@Unique
	private ItemStack[] bloodline$kept;

	@Inject(method = "dropAll", at = @At("HEAD"))
	private void bloodline$holdBackHeirlooms(CallbackInfo ci) {
		Inventory self = (Inventory) (Object) this;
		bloodline$kept = new ItemStack[self.getContainerSize()];
		for (int i = 0; i < bloodline$kept.length; i++) {
			ItemStack stack = self.getItem(i);
			if (!stack.isEmpty() && Heirlooms.keptOnDeath(player, stack)) {
				bloodline$kept[i] = stack;
				self.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	@Inject(method = "dropAll", at = @At("RETURN"))
	private void bloodline$restoreHeirlooms(CallbackInfo ci) {
		Inventory self = (Inventory) (Object) this;
		for (int i = 0; i < bloodline$kept.length; i++) {
			if (bloodline$kept[i] != null) self.setItem(i, bloodline$kept[i]);
		}
		bloodline$kept = null;
	}
}
