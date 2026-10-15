package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.fersyvanderzee.bloodline.TestSupport.*;

/** The three oaths act by act, preparations, the Stolen Heirloom and family events. */
public class OathGameTests {
	private static final Map<String, String> LEGACIES = Map.of("sword", "oathbound", "deep", "deepborn", "hearth", "beloved");

	private static boolean isOathStep(Quest quest) {
		return quest.line.filter("oath"::equals).isPresent();
	}

	/**
	 * Swears the oath and plays it to the end: every step is finished as if its goal were met, and between acts a
	 * new generation takes over. Checks each step has its text and that the oath ends with its legendary trait.
	 */
	private static void walkOath(GameTestHelper helper, String oath, int expectedSteps) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		lineage.house.renown = 1000;
		check(helper, Questlines.swear(player, lineage, oath), "the head can swear the " + oath + " oath");
		List<String> steps = new ArrayList<>();
		for (int guard = 0; guard < 20 && !lineage.house.legacies.contains(LEGACIES.get(oath)); guard++) {
			var step = quest(lineage, OathGameTests::isOathStep);
			if (step.isEmpty()) {
				check(helper, lineage.house.actPending, "between acts the oath waits for the next generation; quests: " + describe(lineage.allQuests()));
				// A new generation: the act may begin.
				lineage.house.actPending = false;
				Questlines.resume(player, lineage);
				continue;
			}
			Quest quest = step.get();
			checkText(helper, quest.title, oath + " step title");
			checkText(helper, quest.task, oath + " step task");
			steps.add(quest.act + "." + quest.step + " " + quest.goal);
			if (quest.goal == Goal.KILL_CHAMPION) {
				long preparations = lineage.allQuests().stream().filter(q -> q.type == Type.PREPARATION).count();
				check(helper, preparations == 3, "a champion fight comes with 3 preparations, got " + preparations);
				lineage.allQuests().stream().filter(q -> q.type == Type.PREPARATION).forEach(q -> checkText(helper, q.title, "a preparation"));
			}
			quest.progress = quest.required;
			quest.lastContributor = player.getUUID();
			tick(player);
			check(helper, !lineage.allQuests().contains(quest), "a finished step is done: " + keyOf(quest.task));
		}
		check(helper, steps.size() == expectedSteps, oath + " has " + expectedSteps + " steps, walked " + steps);
		check(helper, lineage.house.legacies.contains(LEGACIES.get(oath)), "fulfilling the oath gives the legendary trait; steps " + steps);
		check(helper, lineage.house.fulfilledOaths.contains(oath), "the oath is recorded as fulfilled");
		check(helper, Questlines.canSwear(lineage.house), "after one oath the house may swear another");
	}

	@GameTest(maxTicks = 2400)
	public void oathOfTheSwordFromStartToEnd(GameTestHelper helper) {
		walkOath(helper, "sword", 6);
		helper.succeed();
	}

	@GameTest(maxTicks = 2400)
	public void oathOfTheDeepFromStartToEnd(GameTestHelper helper) {
		walkOath(helper, "deep", 7);
		helper.succeed();
	}

	@GameTest(maxTicks = 2400)
	public void oathOfTheHearthFromStartToEnd(GameTestHelper helper) {
		walkOath(helper, "hearth", 6);
		helper.succeed();
	}

	@GameTest
	public void theSwordOathForgesTheOathblade(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		Questlines.swear(player, lineage, "sword");
		Quest first = needQuest(helper, lineage, "act I step 1", OathGameTests::isOathStep);
		check(helper, first.goal == Goal.CUSTOM_STAT && first.required == 30, "first: slay 30 monsters");
		player.awardStat(net.minecraft.stats.Stats.MOB_KILLS, 30);
		tick(player);
		Quest sword = needQuest(helper, lineage, "act I step 2", OathGameTests::isOathStep);
		check(helper, sword.goal == Goal.CRAFT, "second: craft an iron sword");
		player.awardStat(net.minecraft.stats.Stats.ITEM_CRAFTED.get(Items.IRON_SWORD), 1);
		tick(player);
		boolean hasBlade = false;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (Heirlooms.isOathblade(player, player.getInventory().getItem(i))) hasBlade = true;
		}
		check(helper, hasBlade, "the Oathblade is forged");
		check(helper, lineage.house.actPending && lineage.fulfilledAct, "act I is finished and waits for the next generation");
		helper.succeed();
	}

	@GameTest
	public void theNextActWaitsForEnoughRenown(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		Questlines.swear(player, lineage, "hearth");
		for (int i = 0; i < 2; i++) {
			Quest step = needQuest(helper, lineage, "act I", OathGameTests::isOathStep);
			step.progress = step.required;
			tick(player);
		}
		lineage.house.actPending = false;
		lineage.house.renown = 5;
		Questlines.resume(player, lineage);
		check(helper, quest(lineage, OathGameTests::isOathStep).isEmpty(), "act II needs 10 renown; 5 isn't enough");
		Renown.change(player, lineage, 5, Text.t("renown.reason.debug"));
		check(helper, quest(lineage, OathGameTests::isOathStep).isPresent(), "reaching 10 renown starts act II by itself");
		helper.succeed();
	}

	@GameTest
	public void finishingAPreparationWeakensTheChampion(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		lineage.house.renown = 1000;
		Questlines.swear(player, lineage, "hearth");
		for (int guard = 0; guard < 10 && quest(lineage, q -> q.type == Type.PREPARATION).isEmpty(); guard++) {
			var step = quest(lineage, OathGameTests::isOathStep);
			if (step.isEmpty()) {
				lineage.house.actPending = false;
				Questlines.resume(player, lineage);
				continue;
			}
			step.get().progress = step.get().required;
			tick(player);
		}
		Quest prep = needQuest(helper, lineage, "a preparation", q -> q.type == Type.PREPARATION);
		prep.progress = prep.required;
		tick(player);
		check(helper, lineage.house.preparations.size() == 1, "the finished preparation is remembered: " + lineage.house.preparations);
		helper.succeed();
	}

	// --- The Stolen Heirloom ---------------------------------------------------------------------------

	@GameTest(maxTicks = 1200)
	public void aDestroyedHeirloomCanBeTakenBack(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		ItemStack heirloom = Heirlooms.cousinGift(player, lineage, Items.IRON_SWORD, "Wren");
		String name = Heirlooms.nameOf(heirloom);
		Questlines.onHeirloomLost(helper.getLevel().getServer(), heirloom);
		check(helper, lineage.house.lostHeirlooms.size() == 1, "the lost heirloom is remembered");
		Questlines.tickReclaim(player, lineage);
		Quest reclaim = needQuest(helper, lineage, "the Stolen Heirloom", q -> q.line.filter("reclaim"::equals).isPresent());
		checkText(helper, reclaim.title, "the reclaim quest");
		check(helper, reclaim.goal == Goal.KILL_CHAMPION, "the thief must be defeated");
		reclaim.progress = reclaim.required;
		tick(player);
		boolean back = false;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(Items.IRON_SWORD) && Heirlooms.nameOf(stack).equals(name)) back = true;
		}
		check(helper, back, "the heirloom is back in the inventory");
		check(helper, lineage.house.lostHeirlooms.isEmpty(), "and no longer lost");
		helper.succeed();
	}

	@GameTest
	public void gearCarriedByTwoGenerationsBecomesAnHeirloom(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		QuestGameTests.age(player, lineage);
		ItemStack sword = new ItemStack(Items.IRON_SWORD);
		player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, sword);
		Heirlooms.tick(player);
		check(helper, Heirlooms.lostOwner(player.getMainHandItem()).isEmpty(), "carried by one generation it isn't an heirloom yet");
		ServerPlayer heir = QuestGameTests.dieToZombie(helper, player);
		ItemStack kept = ItemStack.EMPTY;
		check(helper, true, "");
		// Gear isn't kept on death until it is an heirloom, so the heir picks the sword up again.
		Heirs.choose(heir, 1);
		heir.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, sword);
		Heirlooms.tick(heir);
		kept = heir.getMainHandItem();
		check(helper, Heirlooms.lostOwner(kept).isPresent(), "carried by a second generation it becomes an heirloom");
		check(helper, kept.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME), "and gets a name");
		helper.succeed();
	}

	// --- Family events ----------------------------------------------------------------------------------

	@GameTest
	public void sicknessIsCuredByAGoldenApple(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		check(helper, FamilyEvents.debugStart(player, lineage, "sickness"), "the sickness starts");
		check(helper, lineage.sick, "the character is sick");
		Quest cure = needQuest(helper, lineage, "the cure", q -> q.type == Type.EVENT);
		checkText(helper, cure.title, "the cure");
		player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(Items.GOLDEN_APPLE), 1);
		tick(player);
		check(helper, !lineage.sick, "eating a golden apple cures them");
		helper.succeed();
	}

	@GameTest
	public void aFeudAcceptedSendsTheRivalChampion(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		FamilyEvents.debugStart(player, lineage, "feud");
		check(helper, lineage.house.pendingEvent.isPresent(), "the feud waits for an answer");
		FamilyEvents.answer(player, true);
		Quest feud = needQuest(helper, lineage, "the feud", q -> q.type == Type.EVENT && q.goal == Goal.KILL_CHAMPION);
		check(helper, feud.targets.contains("night"), "the rival comes at night");
		checkText(helper, feud.title, "the feud");
		helper.succeed();
	}

	@GameTest
	public void aFeudBoughtOffCostsRenown(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		lineage.house.renown = 10;
		player.getInventory().add(new ItemStack(Items.EMERALD, 8));
		FamilyEvents.debugStart(player, lineage, "feud");
		FamilyEvents.answer(player, false);
		check(helper, count(player, Items.EMERALD) == 0, "the tribute is 8 emeralds");
		check(helper, lineage.house.renown == 7, "tribute costs 3 renown, now " + lineage.house.renown);
		check(helper, quest(lineage, q -> q.type == Type.EVENT).isEmpty(), "peace: no feud");
		helper.succeed();
	}

	@GameTest
	public void aTributeYouCantPayStartsTheFeud(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		FamilyEvents.debugStart(player, lineage, "feud");
		FamilyEvents.answer(player, false);
		needQuest(helper, lineage, "the feud", q -> q.type == Type.EVENT && q.goal == Goal.KILL_CHAMPION);
		helper.succeed();
	}

	@GameTest
	public void aWeddingBlessesTheNextChildren(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		player.getInventory().add(new ItemStack(Items.EMERALD, 5));
		FamilyEvents.debugStart(player, lineage, "wedding");
		FamilyEvents.answer(player, true);
		check(helper, lineage.blessedMatch, "the match is blessed");
		check(helper, count(player, Items.EMERALD) == 0, "the dowry is 5 emeralds");
		helper.succeed();
	}

	@GameTest
	public void aCousinBringsAnHeirloom(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		FamilyEvents.debugStart(player, lineage, "cousin");
		FamilyEvents.answer(player, true);
		boolean gift = false;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (Heirlooms.lostOwner(player.getInventory().getItem(i)).isPresent()) gift = true;
		}
		check(helper, gift, "the cousin's gift is an heirloom from the start");
		helper.succeed();
	}

	@GameTest
	public void aRestlessSpiritHauntsAnAncestorsGrave(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		check(helper, !FamilyEvents.debugStart(player, lineage, "restless"), "with no ancestor there is no grave to haunt");
		QuestGameTests.age(player, lineage);
		ServerPlayer heir = QuestGameTests.dieToZombie(helper, player);
		Heirs.choose(heir, 1);
		check(helper, FamilyEvents.debugStart(heir, lineage, "restless"), "an ancestor's grave can be haunted");
		Quest shade = needQuest(helper, lineage, "the shade", q -> q.type == Type.EVENT && q.goal == Goal.KILL_CHAMPION);
		check(helper, shade.place.isPresent(), "the shade walks the grave");
		checkText(helper, shade.task, "the restless spirit");
		helper.succeed();
	}
}
