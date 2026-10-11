package io.github.fersyvanderzee.bloodline;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gives a family member their epithet when they die ("Elske the Wanderer"), based on what
 * they did most during their life compared with the stats they were born with.
 */
final class Epithets {
	private record Deed(String stat, double threshold, List<String> epithets) {
	}

	private static final int TICKS_PER_DAY = 24000;

	private static final List<Deed> DEEDS = List.of(
		new Deed("kills", 40, List.of("the Slayer", "the Bold", "the Fierce")),
		new Deed("mined", 1500, List.of("the Delver", "the Stonebreaker", "the Deep-Digger")),
		new Deed("distance", 500_000, List.of("the Wanderer", "the Far-Walker", "the Restless")),
		new Deed("fish", 15, List.of("the Angler", "the Patient")),
		new Deed("bred", 20, List.of("the Shepherd", "the Herder")),
		new Deed("traded", 20, List.of("the Merchant", "the Silver-Tongued")),
		new Deed("damage", 3000, List.of("the Scarred", "the Unbroken"))
	);

	private Epithets() {
	}

	static Map<String, Integer> snapshot(ServerPlayer player) {
		ServerStatsCounter stats = player.getStats();
		Map<String, Integer> out = new HashMap<>();
		out.put("kills", custom(stats, Stats.MOB_KILLS));
		out.put("distance", custom(stats, Stats.WALK_ONE_CM) + custom(stats, Stats.SPRINT_ONE_CM));
		out.put("fish", custom(stats, Stats.FISH_CAUGHT));
		out.put("bred", custom(stats, Stats.ANIMALS_BRED));
		out.put("traded", custom(stats, Stats.TRADED_WITH_VILLAGER));
		out.put("damage", custom(stats, Stats.DAMAGE_TAKEN));
		out.put("play_time", custom(stats, Stats.PLAY_TIME));
		int mined = 0;
		for (Block block : BuiltInRegistries.BLOCK) {
			mined += stats.getValue(Stats.BLOCK_MINED.get(block));
		}
		out.put("mined", mined);
		return out;
	}

	private static int custom(ServerStatsCounter stats, Identifier stat) {
		return stats.getValue(Stats.CUSTOM.get(stat));
	}

	static String choose(Map<String, Integer> atBirth, Map<String, Integer> atDeath, RandomSource random) {
		int played = delta(atBirth, atDeath, "play_time");
		if (played < TICKS_PER_DAY / 2) {
			return "the Brief";
		}
		Deed best = null;
		double bestScore = 1;
		for (Deed deed : DEEDS) {
			double score = delta(atBirth, atDeath, deed.stat()) / deed.threshold();
			if (score >= bestScore) {
				best = deed;
				bestScore = score;
			}
		}
		if (best != null) {
			return best.epithets().get(random.nextInt(best.epithets().size()));
		}
		return played > TICKS_PER_DAY * 10 ? "the Elder" : "the Quiet";
	}

	private static int delta(Map<String, Integer> before, Map<String, Integer> after, String key) {
		return after.getOrDefault(key, 0) - before.getOrDefault(key, 0);
	}
}
