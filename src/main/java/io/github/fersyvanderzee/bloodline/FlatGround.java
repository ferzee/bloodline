package io.github.fersyvanderzee.bloodline;

import com.mojang.datafixers.util.Either;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Keeps the mod's surface structures off mountainsides and out of lakes.
 *
 * <p>Minecraft places a jigsaw structure at the height of one point and builds it level from there, so on a slope
 * one side is buried and the other stands on a block of dirt in mid-air. Here the ground under the structure's
 * actual footprint (its first piece: the inn, or the crypt's graveyard) is measured before anything is built. If it
 * is too uneven or mostly water, a few spots nearby are tried and the first good one is used; if none is good, no
 * structure appears there. The chosen structure is then set at the middle height of its ground instead of at the
 * height of a single point.
 */
public final class FlatGround {
	/** How uneven the ground under each structure may be, in blocks (ignoring the highest and lowest tenth). */
	private static final Map<String, Integer> MAX_UNEVENNESS = Map.of(
		"crypt/entrance", 4,
		"roadside_inn/start", 5,
		"shrine/temperate", 3,
		"shrine/cold", 3,
		"shrine/warm", 3);
	/** Most of the footprint that may be water. */
	private static final double MAX_WATER = 0.1;
	private static final int STEP = 4;
	/** Other spots to try, in chunks from the original one, in this order. */
	private static final int[][] OFFSETS = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};

	/** Set while the check runs (it asks Minecraft's own placement again), and during /place, which puts a structure exactly where asked. */
	public static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> false);
	/** Off only in the terrain check, which builds the same world with and without this to compare them. */
	public static volatile boolean enabled = true;

	private FlatGround() {
	}

	/** The rule for a start pool of this mod ("crypt/entrance"), if it has one. */
	public static Optional<Integer> maxUnevenness(String startPoolPath) {
		return Optional.ofNullable(MAX_UNEVENNESS.get(startPoolPath));
	}

	/**
	 * Finds a good spot for the structure. {@code place} is Minecraft's own placement for a given context; it is
	 * asked again with the chunk moved for each spot tried.
	 */
	public static Optional<Structure.GenerationStub> find(Structure.GenerationContext context, int maxUnevenness,
														  Function<Structure.GenerationContext, Optional<Structure.GenerationStub>> place) {
		List<ChunkPos> spots = new ArrayList<>();
		spots.add(context.chunkPos());
		for (int[] offset : OFFSETS) spots.add(new ChunkPos(context.chunkPos().x() + offset[0], context.chunkPos().z() + offset[1]));
		for (ChunkPos spot : spots) {
			Structure.GenerationContext moved = spot.equals(context.chunkPos()) ? context : new Structure.GenerationContext(
				context.registryAccess(), context.chunkGenerator(), context.biomeSource(), context.climateSampler(), context.biomeResolver(),
				context.randomState(), context.structureTemplateManager(), context.random(), context.seed(), spot, context.heightAccessor(),
				context.validBiome());
			Optional<Structure.GenerationStub> stub = place.apply(moved);
			if (stub.isEmpty() || !context.isValidBiome(stub.get())) continue;
			Optional<Structure.GenerationStub> settled = settle(context, stub.get(), maxUnevenness);
			if (settled.isPresent()) return settled;
		}
		return Optional.empty();
	}

	/**
	 * Measures the ground under the structure's first piece. Returns the structure moved to the middle height of
	 * that ground, or nothing if the ground is too uneven or too wet.
	 */
	private static Optional<Structure.GenerationStub> settle(Structure.GenerationContext context, Structure.GenerationStub stub, int maxUnevenness) {
		StructurePiecesBuilder builder = stub.getPiecesBuilder();
		List<StructurePiece> pieces = builder.build().pieces();
		if (pieces.isEmpty()) return Optional.empty();
		BoundingBox footprint = pieces.getFirst().getBoundingBox();

		List<Integer> heights = new ArrayList<>();
		int water = 0;
		for (int x = footprint.minX() + 1; x <= footprint.maxX() - 1; x += STEP) {
			for (int z = footprint.minZ() + 1; z <= footprint.maxZ() - 1; z += STEP) {
				int surface = height(context, x, z, Heightmap.Types.WORLD_SURFACE_WG);
				int floor = height(context, x, z, Heightmap.Types.OCEAN_FLOOR_WG);
				if (surface > floor) water++;
				heights.add(surface);
			}
		}
		if (heights.isEmpty() || water > heights.size() * MAX_WATER) return Optional.empty();
		int[] sorted = heights.stream().mapToInt(Integer::intValue).sorted().toArray();
		int low = sorted[sorted.length / 10];
		int high = sorted[sorted.length - 1 - sorted.length / 10];
		if (high - low > maxUnevenness) return Optional.empty();

		// Minecraft set the structure at the height of the footprint's centre; move it to the middle of its ground.
		int middle = sorted[sorted.length / 2];
		int centre = height(context, footprint.getCenter().getX(), footprint.getCenter().getZ(), Heightmap.Types.WORLD_SURFACE_WG);
		if (middle != centre) builder.offsetPiecesVertically(middle - centre);
		return Optional.of(new Structure.GenerationStub(stub.position().above(middle - centre), Either.right(builder)));
	}

	private static int height(Structure.GenerationContext context, int x, int z, Heightmap.Types type) {
		return context.chunkGenerator().getFirstFreeHeight(x, z, type, context.heightAccessor(), context.randomState());
	}
}
