package io.github.fersyvanderzee.bloodline.mixin;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.commands.PlaceCommand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * {@code /place structure} builds a structure's blocks but doesn't tell the world a structure is there, so
 * nothing can find it afterwards. Crypts and roadside inns need to be found (the crypt's puzzle, the inn's
 * higher pay), so a placed structure is recorded in its chunks just like a generated one.
 */
@Mixin(PlaceCommand.class)
abstract class PlaceCommandMixin {
	@ModifyVariable(method = "placeStructure", at = @At("STORE"), ordinal = 0)
	private static StructureStart bloodline$recordPlacedStructure(StructureStart start, CommandSourceStack source, Holder.Reference<Structure> structure,
																	BlockPos pos) {
		if (!start.isValid()) return start;
		ServerLevel level = source.getLevel();
		ChunkPos origin = start.getChunkPos();
		BoundingBox box = start.getBoundingBox();
		for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
			for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
				if (!level.hasChunk(x, z)) continue;
				ChunkAccess chunk = level.getChunk(x, z);
				if (x == origin.x() && z == origin.z()) chunk.setStartForStructure(structure.value(), start);
				chunk.addReferenceForStructure(structure.value(), origin.pack());
			}
		}
		return start;
	}
}
