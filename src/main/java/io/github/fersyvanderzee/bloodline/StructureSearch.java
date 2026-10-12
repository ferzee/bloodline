package io.github.fersyvanderzee.bloodline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Finding structures for quests and maps. This runs on the server thread like vanilla /locate,
 * and searching far for something rare can pause the server, so results are cached per area for a
 * while and slow searches are logged with a hint about the config setting that limits them.
 */
final class StructureSearch {
	private static final long CACHE_TICKS = 20 * 60 * 10;
	/** Searches starting within the same 512-block area share a cached result. */
	private static final int AREA_SHIFT = 9;
	private static final long SLOW_MILLIS = 200;

	private record Key(ResourceKey<Level> dimension, String structure, int areaX, int areaZ, int radius) {
	}

	private record Result(Optional<BlockPos> pos, long expires) {
	}

	private static final Map<Key, Result> CACHE = new HashMap<>();

	private StructureSearch() {
	}

	static Optional<BlockPos> find(ServerLevel level, ResourceKey<Structure> structure, BlockPos origin, int radius, boolean skipKnown) {
		var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(structure);
		if (holder.isEmpty()) return Optional.empty();
		return find(level, structure.identifier().toString(), HolderSet.direct(holder.get()), origin, radius, skipKnown);
	}

	static Optional<BlockPos> find(ServerLevel level, TagKey<Structure> tag, BlockPos origin, int radius) {
		var set = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(tag);
		if (set.isEmpty()) return Optional.empty();
		return find(level, "#" + tag.location(), set.get(), origin, radius, false);
	}

	/**
	 * {@code skipKnown} skips structures that maps already point to, like vanilla treasure maps; those
	 * searches change what they find each time, so they aren't cached.
	 */
	private static Optional<BlockPos> find(ServerLevel level, String name, HolderSet<Structure> structures, BlockPos origin, int radius,
										   boolean skipKnown) {
		long now = level.getGameTime();
		Key key = new Key(level.dimension(), name, origin.getX() >> AREA_SHIFT, origin.getZ() >> AREA_SHIFT, radius);
		if (!skipKnown) {
			Result cached = CACHE.get(key);
			if (cached != null && cached.expires() > now) return cached.pos();
		}
		long started = System.nanoTime();
		Optional<BlockPos> found = Optional.ofNullable(level.findNearestMapStructure(structures, origin, radius, skipKnown));
		long millis = (System.nanoTime() - started) / 1_000_000;
		if (millis > SLOW_MILLIS) {
			Bloodline.LOGGER.warn("Finding {} took {} ms (searching {} chunks around {}). If this causes lag, lower "
				+ "structures.searchRadiusChunks or structures.rumourSearchRadiusChunks in config/bloodline.json.", name, millis, radius, origin.toShortString());
		}
		if (!skipKnown) CACHE.put(key, new Result(found, now + CACHE_TICKS));
		return found;
	}
}
