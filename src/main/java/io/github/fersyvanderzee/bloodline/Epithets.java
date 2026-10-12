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
public final class Epithets {
	private record Deed(String stat, double threshold, List<String> epithets) {
	}

	private static final int TICKS_PER_DAY = 24000;
	/** Epithets are saved as ids ("brief"); their words are in the language file as bloodline.epithet.<id>. */
	public static final String BRIEF = "brief";
	public static final String DEVOTED = "devoted";

	private static final List<Deed> DEEDS = List.of(
		new Deed("kills", 40, List.of("slayer", "bold", "fierce")),
		new Deed("mined", 1500, List.of("delver", "stonebreaker", "deep_digger")),
		new Deed("distance", 500_000, List.of("wanderer", "far_walker", "restless")),
		new Deed("fish", 15, List.of("angler", "patient")),
		new Deed("bred", 20, List.of("shepherd", "herder")),
		new Deed("traded", 20, List.of("merchant", "silver_tongued")),
		new Deed("damage", 3000, List.of("scarred", "unbroken"))
	);

	private Epithets() {
	}

	public static Map<String, Integer> snapshot(ServerPlayer player) {
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
		if (played < BloodlineConfig.get().families.briefLifeMinutes * 1200) {
			return BRIEF;
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
		return played > TICKS_PER_DAY * 10 ? "elder" : "quiet";
	}

	/** The epithet's id; older saves stored the English words ("the Deep-Digger" becomes "deep_digger"). */
	public static String id(String stored) {
		String id = stored.toLowerCase(java.util.Locale.ROOT);
		if (id.startsWith("the ")) id = id.substring(4);
		return id.replace('-', '_').replace(' ', '_');
	}

	/** The epithet as text, e.g. "the Deep-Digger". */
	public static net.minecraft.network.chat.MutableComponent display(String stored) {
		String id = id(stored);
		return Text.has("epithet." + id) ? Text.t("epithet." + id) : net.minecraft.network.chat.Component.literal(stored);
	}

	private static int delta(Map<String, Integer> before, Map<String, Integer> after, String key) {
		return after.getOrDefault(key, 0) - before.getOrDefault(key, 0);
	}
}
