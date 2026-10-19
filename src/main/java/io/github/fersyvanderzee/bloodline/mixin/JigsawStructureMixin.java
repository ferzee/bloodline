package io.github.fersyvanderzee.bloodline.mixin;

import io.github.fersyvanderzee.bloodline.Bloodline;
import io.github.fersyvanderzee.bloodline.FlatGround;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/** Crypts and roadside inns look for level, dry ground before they are built (see {@link FlatGround}). */
@Mixin(JigsawStructure.class)
abstract class JigsawStructureMixin {
	@Shadow
	@Final
	private Holder<StructureTemplatePool> startPool;

	@Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
	private void bloodline$findLevelGround(Structure.GenerationContext context, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
		if (FlatGround.BYPASS.get() || !FlatGround.enabled) return;
		var key = startPool.unwrapKey();
		if (key.isEmpty() || !key.get().identifier().getNamespace().equals(Bloodline.MOD_ID)) return;
		var rule = FlatGround.maxUnevenness(key.get().identifier().getPath());
		if (rule.isEmpty()) return;
		JigsawStructure self = (JigsawStructure) (Object) this;
		FlatGround.BYPASS.set(true);
		try {
			cir.setReturnValue(FlatGround.find(context, rule.get(), self::findGenerationPoint));
		} finally {
			FlatGround.BYPASS.set(false);
		}
	}
}
