package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.fersyvanderzee.bloodline.TestSupport.*;

/** Founding goals, death, heirs and the duties a death leaves behind. */
public class QuestGameTests {
	@GameTest
	public void founderGetsHouseGoalsAndChronicle(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		for (String key : List.of("founding.banner", "founding.bed", "founding.name", "hearth.title")) {
			needQuest(helper, lineage, key, q -> keyOf(q.title).equals("bloodline." + key));
		}
		check(helper, count(player, Items.WRITTEN_BOOK) == 1, "the founder carries the Chronicle");
		check(helper, lineage.isHead(player.getUUID()), "the founder heads the house");
		checkTexts(helper, lineage);
		helper.succeed();
	}

	@GameTest
	public void sleepingInABedFulfilsTheRoofGoal(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		int renown = lineage.house.renown;
		player.awardStat(Stats.SLEEP_IN_BED, 1);
		tick(player);
		check(helper, completed(lineage, "founding.bed"), "the bed goal is done: " + describe(lineage.allQuests()));
		check(helper, count(player, Items.BREAD) == 8, "the reward is 8 bread, got " + count(player, Items.BREAD));
		check(helper, lineage.house.renown == renown + BloodlineConfig.get().renown.foundingGoal, "a founding goal earns renown");
		helper.succeed();
	}

	@GameTest
	public void placingABannerRaisesTheHouseBanner(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		player.awardStat(Stats.ITEM_USED.get(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("red_banner"))), 1);
		tick(player);
		check(helper, completed(lineage, "founding.banner"), "placing any banner counts: " + describe(lineage.allQuests()));
		helper.succeed();
	}

	@GameTest
	public void slayingMonstersMakesAName(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		int traits = lineage.current.traits().size();
		int levels = player.experienceLevel;
		player.awardStat(Stats.MOB_KILLS, 14);
		tick(player);
		check(helper, !completed(lineage, "founding.name"), "14 kills isn't enough yet");
		player.awardStat(Stats.MOB_KILLS, 1);
		tick(player);
		check(helper, completed(lineage, "founding.name"), "15 kills makes a name for the house");
		check(helper, lineage.current.traits().size() == traits + 1 || player.experienceLevel >= levels + 5, "the reward is a new trait (or 5 levels)");
		helper.succeed();
	}

	@GameTest
	public void progressCountsOnlyFromWhenTheQuestStarted(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		Quest kills = needQuest(helper, lineage, "founding.name", q -> keyOf(q.title).equals("bloodline.founding.name"));
		tick(player);
		check(helper, kills.progress == 0, "a new quest starts at zero");
		player.awardStat(Stats.MOB_KILLS, 3);
		tick(player);
		check(helper, kills.progress == 3, "three kills count as three, got " + kills.progress);
		helper.succeed();
	}

	// --- Death and heirs ----------------------------------------------------------------------------

	/** Kills the player's character with a zombie and respawns them, as the game does; returns the respawned player. */
	static ServerPlayer dieToZombie(GameTestHelper helper, ServerPlayer player) {
		Zombie zombie = EntityTypes.ZOMBIE.create(helper.getLevel(), EntitySpawnReason.EVENT);
		zombie.snapTo(player.position());
		player.die(helper.getLevel().damageSources().mobAttack(zombie));
		return helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
	}

	/** Pretends the current character has lived a full life, like /bloodline debug age. */
	static void age(ServerPlayer player, Lineage lineage) {
		Map<String, Integer> stats = new HashMap<>(lineage.current.statsAtBirth());
		stats.put("play_time", Epithets.snapshot(player).getOrDefault("play_time", 0) - BloodlineConfig.get().families.briefLifeMinutes * 1200 - 1);
		Member m = lineage.current;
		lineage.current = new Member(m.name(), m.generation(), m.traits(), m.bornDay(), stats);
	}

	@GameTest
	public void deathLeavesDutiesAndHeirsToChooseFrom(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		age(player, lineage);
		String founder = lineage.current.name();
		ServerPlayer heir = dieToZombie(helper, player);
		check(helper, lineage.awaitingHeir, "after a death the family waits for an heir");
		check(helper, lineage.candidates.size() == BloodlineConfig.get().families.heirChoices,
			"there are " + BloodlineConfig.get().families.heirChoices + " children to choose from, got " + lineage.candidates.size());
		Ancestor ancestor = lineage.ancestors.getLast();
		check(helper, ancestor.name().equals(founder), "the founder is now an ancestor");
		check(helper, !ancestor.epithet().equals(Epithets.BRIEF), "an aged character isn't 'the Brief'");

		Quest debt = needQuest(helper, lineage, "a blood debt", q -> q.type == Type.BLOOD_DEBT);
		check(helper, debt.goal == Goal.KILL && debt.targets.contains("minecraft:zombie") && debt.required == 5, "avenge: slay 5 zombies");
		needQuest(helper, lineage, "the resting place", q -> q.type == Type.RESTING_PLACE);
		needQuest(helper, lineage, "unfinished business", q -> q.type == Type.UNFINISHED_BUSINESS);
		checkTexts(helper, lineage);

		Heirs.choose(heir, 2);
		check(helper, !lineage.awaitingHeir, "choosing a child ends the mourning");
		check(helper, lineage.current.generation() == 2, "the heir is generation 2");
		check(helper, lineage.current.name().equals(lineage.ancestors.getLast().name()) == false, "the heir has their own name");
		check(helper, heir.isAlive(), "the heir is alive");
		helper.succeed();
	}

	@GameTest
	public void aBriefLifeLeavesBriefTasks(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		dieToZombie(helper, player);
		check(helper, Epithets.BRIEF.equals(Epithets.id(lineage.ancestors.getLast().epithet())), "a character who died at once is 'the Brief'");
		Quest unfinished = needQuest(helper, lineage, "unfinished business", q -> q.type == Type.UNFINISHED_BUSINESS);
		String key = keyOf(unfinished.title);
		check(helper, List.of("bloodline.unfinished.outlive", "bloodline.unfinished.two_days", "bloodline.unfinished.cake").contains(key),
			"a brief life leaves a brief task, got " + key);
		check(helper, lineage.inheritAll == false, "a natural death doesn't pass every boon on");
		helper.succeed();
	}

	@GameTest
	public void bloodDebtIsSettledBySlayingTheKiller(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		age(player, lineage);
		ServerPlayer heir = dieToZombie(helper, player);
		Heirs.choose(heir, 1);
		int traits = lineage.current.traits().size();
		tick(heir);
		heir.awardStat(Stats.ENTITY_KILLED.get(EntityTypes.ZOMBIE), 4);
		tick(heir);
		Quest debt = needQuest(helper, lineage, "the blood debt", q -> q.type == Type.BLOOD_DEBT);
		check(helper, debt.progress == 4, "four zombies count, got " + debt.progress);
		heir.awardStat(Stats.ENTITY_KILLED.get(EntityTypes.ZOMBIE), 1);
		tick(heir);
		check(helper, lineage.allQuests().stream().noneMatch(q -> q.type == Type.BLOOD_DEBT), "the debt is settled");
		check(helper, lineage.current.traits().size() == traits + 1 || heir.experienceLevel >= 8, "settling a debt gives a trait (or 8 levels)");
		helper.succeed();
	}

	@GameTest
	public void visitingTheRestingPlaceFulfilsIt(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		age(player, lineage);
		var deathSpot = player.position();
		ServerPlayer heir = dieToZombie(helper, player);
		Heirs.choose(heir, 1);
		Quest resting = needQuest(helper, lineage, "the resting place", q -> q.type == Type.RESTING_PLACE);
		heir.snapTo(deathSpot);
		tick(heir);
		check(helper, !resting.isDone(), "standing on the grave straight after respawning doesn't count yet");
		heir.tickCount = 1000;
		tick(heir);
		check(helper, lineage.allQuests().stream().noneMatch(q -> q.type == Type.RESTING_PLACE), "visiting the grave fulfils the duty");
		helper.succeed();
	}

	@GameTest
	public void unpaidDebtsPassDownAndCostRenown(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		lineage.house.renown = 20;
		age(player, lineage);
		ServerPlayer heir = dieToZombie(helper, player);
		Heirs.choose(heir, 1);
		age(heir, lineage);
		ServerPlayer next = dieToZombie(helper, heir);
		long debts = lineage.allQuests().stream().filter(q -> q.type == Type.BLOOD_DEBT).count();
		check(helper, debts == 2, "the old debt stays and a new one is added, got " + debts);
		check(helper, lineage.house.renown == 20 + BloodlineConfig.get().renown.debtInherited, "an unpaid debt costs renown, now " + lineage.house.renown);
		Heirs.choose(next, 1);
		helper.succeed();
	}

	// --- Unfinished business: every task in the game ---------------------------------------------------

	@GameTest
	@SuppressWarnings("unchecked")
	public void everyUnfinishedBusinessTaskIsValid(GameTestHelper helper) {
		List<Object> templates = new java.util.ArrayList<>();
		templates.addAll(field(Quests.class, "MINING"));
		templates.addAll(field(Quests.class, "FIGHTING"));
		templates.addAll(field(Quests.class, "GENERAL"));
		Map<String, List<Object>> byEpithet = field(Quests.class, "BY_EPITHET");
		byEpithet.values().forEach(templates::addAll);
		for (Object template : templates) {
			String key = (String) read(template, "key");
			Goal goal = (Goal) read(template, "goal");
			List<String> targets = (List<String>) read(template, "targets");
			Quest.Reward reward = (Quest.Reward) read(template, "reward");
			check(helper, Text.has("unfinished." + key) && Text.has("unfinished." + key + ".task"), "unfinished." + key + " has its text");
			for (String target : targets) {
				Identifier id = Identifier.parse(target);
				boolean known = switch (goal) {
					case KILL -> BuiltInRegistries.ENTITY_TYPE.containsKey(id);
					case MINE -> BuiltInRegistries.BLOCK.containsKey(id);
					case CRAFT, USE -> BuiltInRegistries.ITEM.containsKey(id);
					case CUSTOM_STAT -> BuiltInRegistries.CUSTOM_STAT.containsKey(id);
					case DIMENSION -> true;
					default -> false;
				};
				check(helper, known, "unfinished." + key + " targets something that exists: " + target + " (" + goal + ")");
			}
			reward.item().ifPresent(item -> check(helper, BuiltInRegistries.ITEM.containsKey(Identifier.parse(item)),
				"unfinished." + key + " rewards a real item: " + item));
		}
		for (String epithet : byEpithet.keySet()) check(helper, Text.has("epithet." + epithet), "epithet." + epithet + " has its text");
		helper.succeed();
	}

	@GameTest
	public void howALifeWasLivedPicksTheEpithet(GameTestHelper helper) {
		var random = helper.getLevel().getRandom();
		int day = BloodlineConfig.get().families.briefLifeMinutes * 1200 + 1;
		Map<String, List<String>> expected = Map.of(
			"kills", List.of("slayer", "bold", "fierce"), "mined", List.of("delver", "stonebreaker", "deep_digger"),
			"distance", List.of("wanderer", "far_walker", "restless"), "fish", List.of("angler", "patient"),
			"bred", List.of("shepherd", "herder"), "traded", List.of("merchant", "silver_tongued"), "damage", List.of("scarred", "unbroken"));
		Map<String, Integer> amounts = Map.of("kills", 50, "mined", 2000, "distance", 600_000, "fish", 20, "bred", 25, "traded", 25, "damage", 3500);
		for (var entry : expected.entrySet()) {
			Map<String, Integer> birth = new HashMap<>(Map.of("play_time", 0));
			Map<String, Integer> death = new HashMap<>(Map.of("play_time", day, entry.getKey(), amounts.get(entry.getKey())));
			String epithet = Epithets.choose(birth, death, random);
			check(helper, entry.getValue().contains(epithet), entry.getKey() + " should give one of " + entry.getValue() + ", got " + epithet);
		}
		check(helper, Epithets.choose(Map.of("play_time", 0), Map.of("play_time", 10), random).equals(Epithets.BRIEF), "a short life is brief");
		check(helper, Epithets.choose(Map.of("play_time", 0), Map.of("play_time", day), random).equals("quiet"), "an uneventful life is quiet");
		helper.succeed();
	}
}
