package io.github.fersyvanderzee.bloodline.client;

import io.github.fersyvanderzee.bloodline.Bloodline;
import io.github.fersyvanderzee.bloodline.FlatGround;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the same normal world twice, first with Minecraft's own placement and then with {@link FlatGround}, and
 * measures how well each crypt and roadside inn sits on the land around it. Writes the numbers to the log and a
 * screenshot of each structure. Slow, so it only runs when asked: ./gradlew runClientGametest -Pterrain=true
 */
public class TerrainCheckTest implements FabricClientGameTest {
	private static final String SEED = "bloodline-terrain";
	private static final BlockPos[] SEARCH_FROM = {new BlockPos(0, 0, 0), new BlockPos(2500, 0, 0), new BlockPos(0, 0, 2500), new BlockPos(-2500, 0, -1500)};
	/** The level the structure stands on, measured from the bottom of its first piece: the crypt is sunk 16 blocks into the ground. */
	private static final int CRYPT_GROUND = 16;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean("bloodline.terrain")) return;
		List<String> report = new ArrayList<>();
		for (boolean flat : new boolean[]{false, true}) {
			FlatGround.enabled = flat;
			String mode = flat ? "new" : "old";
			try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(s -> s.setSeed(SEED)).create()) {
				// A spectator can hang in the air for the screenshots without falling.
				world.getServer().runCommand("gamemode spectator @a");
				// Shrines are new, so only the new placement is worth looking at.
				List<String> ids = flat ? List.of("crypt", "roadside_inn", "shrine_temperate", "shrine_cold", "shrine_warm") : List.of("crypt", "roadside_inn");
				for (String id : ids) {
					for (int i = 0; i < SEARCH_FROM.length; i++) {
						BlockPos from = SEARCH_FROM[i];
						Measured m = world.getServer().computeOnServer(server -> measure(server, id, from));
						if (m == null) {
							log(report, mode + " " + id + " #" + i + ": none found");
							continue;
						}
						log(report, String.format("%s %-12s #%d at %6d %6d: ground %+d..%+d around it, %d%% of the edge more than 2 blocks off",
							mode, id, i, m.center.getX(), m.center.getZ(), m.lowest, m.highest, m.offPercent));
						boolean small = id.startsWith("shrine");
						// Shrines are seen from the front, a little to one side, so the altar shows.
						BlockPos eye = small
							? m.center.relative(m.front, 10).relative(m.front.getClockWise(), 3).atY(m.base + 4)
							: m.center.offset(-22, 14, -22);
						world.getServer().runCommand("tp @a " + eye.getX() + " " + eye.getY() + " " + eye.getZ() + " facing "
							+ m.center.getX() + " " + (m.base + (small ? 2 : 0)) + " " + m.center.getZ());
						context.waitTicks(40);
						world.getConnection().waitForChunksRender();
						context.takeScreenshot("terrain_" + mode + "_" + id + "_" + i);
					}
				}
			}
		}
		FlatGround.enabled = true;
		Bloodline.LOGGER.info("[terrain] summary:");
		report.forEach(line -> Bloodline.LOGGER.info("[terrain]   {}", line));
	}

	private static void log(List<String> report, String line) {
		report.add(line);
		Bloodline.LOGGER.info("[terrain] {}", line);
	}

	/** {@code front}: the way the structure's open side faces (its template's south, turned like the structure). */
	private record Measured(BlockPos center, int base, int lowest, int highest, int offPercent, net.minecraft.core.Direction front) {
	}

	/** The structure nearest to {@code from}, and how the land just outside its first piece compares with its ground level. */
	private static Measured measure(MinecraftServer server, String id, BlockPos from) {
		ServerLevel level = server.overworld();
		var structure = server.registryAccess().lookupOrThrow(Registries.STRUCTURE)
			.getOrThrow(ResourceKey.create(Registries.STRUCTURE, Bloodline.id(id)));
		BlockPos found = level.findNearestMapStructure(HolderSet.direct(structure), from, 100, false);
		if (found == null) return null;
		StructureStart start = level.getChunk(found.getX() >> 4, found.getZ() >> 4).getStartForStructure(structure.value());
		if (start == null || !start.isValid()) return null;
		BoundingBox box = start.getPieces().getFirst().getBoundingBox();
		int base = box.minY() + (id.equals("crypt") ? CRYPT_GROUND : 0);
		int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE, off = 0, total = 0;
		// The ring of columns two blocks outside the footprint, every other block.
		for (int x = box.minX() - 2; x <= box.maxX() + 2; x += 2) {
			for (int z = box.minZ() - 2; z <= box.maxZ() + 2; z += 2) {
				boolean edge = x <= box.minX() - 1 || x >= box.maxX() + 1 || z <= box.minZ() - 1 || z >= box.maxZ() + 1;
				if (!edge) continue;
				int ground = level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
				int diff = ground - base;
				lowest = Math.min(lowest, diff);
				highest = Math.max(highest, diff);
				if (Math.abs(diff) > 2) off++;
				total++;
			}
		}
		var first = start.getPieces().getFirst();
		net.minecraft.core.Direction front = first instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece piece
			? piece.getRotation().rotate(net.minecraft.core.Direction.SOUTH) : net.minecraft.core.Direction.SOUTH;
		return new Measured(box.getCenter(), base, lowest, highest, total == 0 ? 0 : off * 100 / total, front);
	}
}
