package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.phys.AABB;

import java.util.List;

import static io.github.fersyvanderzee.bloodline.TestSupport.*;

/** Shared houses, the Chronicle book, champions and the Sacrifice. */
public class HouseGameTests {
	@GameTest
	public void aPlayerCanJoinAnotherHouseAndShareItsDuties(GameTestHelper helper) {
		ServerPlayer head = join(helper);
		ServerPlayer friend = join(helper);
		Lineage headLine = lineage(helper, head);
		Lineage friendLine = lineage(helper, friend);
		check(helper, headLine.house != friendLine.house, "two new players found two houses");
		Houses.invite(head, friend);
		Houses.accept(friend, true);
		check(helper, friendLine.house == headLine.house, "after accepting, both are in one house");
		check(helper, headLine.house.members.size() == 2, "the house has two members");

		// A shared duty counts both members' progress.
		Quest debt = Quest.of(Type.BLOOD_DEBT, Text.t("debt.title", "Test"), Text.t("debt.slay", "Zombie"), Goal.KILL,
			List.of("minecraft:zombie"), 4, Quest.Reward.levels(1));
		Quests.add(head, headLine, debt);
		Quests.tick(friend, friendLine);
		head.awardStat(Stats.ENTITY_KILLED.get(EntityTypes.ZOMBIE), 2);
		friend.awardStat(Stats.ENTITY_KILLED.get(EntityTypes.ZOMBIE), 1);
		tick(head);
		Quests.tick(friend, friendLine);
		check(helper, debt.progress == 3, "both members' kills count towards a shared debt, got " + debt.progress);
		helper.succeed();
	}

	@GameTest
	public void joiningFreshStartsANewCharacter(GameTestHelper helper) {
		ServerPlayer head = join(helper);
		ServerPlayer friend = join(helper);
		Lineage friendLine = lineage(helper, friend);
		Houses.invite(head, friend);
		Houses.accept(friend, false);
		check(helper, friendLine.house == lineage(helper, head).house, "joined the house");
		check(helper, friendLine.current.generation() == 1 && friendLine.ancestors.isEmpty(), "a fresh newcomer starts at generation 1");
		helper.succeed();
	}

	@GameTest
	public void theChronicleBookHasAllItsText(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		// Give the family some history: an oath, a death and a family event.
		lineage.house.renown = 30;
		Questlines.swear(player, lineage, "deep");
		QuestGameTests.age(player, lineage);
		ServerPlayer heir = QuestGameTests.dieToZombie(helper, player);
		WrittenBookContent mourning = Chronicle.build(heir, lineage);
		for (var page : mourning.pages()) checkText(helper, page.raw(), "the Chronicle while choosing an heir");
		Heirs.choose(heir, 1);
		FamilyEvents.debugStart(heir, lineage, "sickness");
		WrittenBookContent book = Chronicle.build(heir, lineage);
		check(helper, book.pages().size() >= 4, "the Chronicle has several pages, got " + book.pages().size());
		for (var page : book.pages()) checkText(helper, page.raw(), "the Chronicle");
		for (var entry : lineage.house.chronicle) {
			checkText(helper, entry.text(), "a Chronicle entry");
			check(helper, !entry.text().getString().contains("Air"), "no item called Air in the Chronicle: " + entry.text().getString());
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 400)
	public void aBountyChampionAppearsAtItsPlace(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		BlockPos spot = helper.absolutePos(new BlockPos(3, 1, 3));
		Quest bounty = Quest.of(Type.INN, Text.t("inn.bounty", Champions.ROTGUT.name()), Text.t("inn.bounty.task", Champions.ROTGUT.name(), "here"),
				Goal.KILL_CHAMPION, List.of("test_bounty", Champions.ROTGUT.id(), helper.getLevel().dimension().identifier().toString()), 1,
				Quest.Reward.item(3, "emerald", 6))
			.at(net.minecraft.core.GlobalPos.of(helper.getLevel().dimension(), spot), 32);
		Quests.add(player, lineage, bounty);
		player.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		player.tickCount = 1000;
		tick(player);
		List<Mob> champions = helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(spot).inflate(16), Champions::isChampion);
		check(helper, champions.size() == 1, "the champion appears when you arrive, got " + champions.size());
		check(helper, champions.getFirst().hasCustomName(), "the champion has its name");
		tick(player);
		check(helper, helper.getLevel().getEntitiesOfClass(Mob.class, new AABB(spot).inflate(16), Champions::isChampion).size() == 1,
			"only one champion appears");
		champions.getFirst().kill(helper.getLevel());
		check(helper, bounty.isDone(), "slaying the champion finishes the bounty");
		tick(player);
		check(helper, quest(lineage, q -> q == bounty).isEmpty(), "and pays out");
		helper.succeed();
	}

	@GameTest
	public void theSacrificeNeedsAnActFulfilled(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		Sacrifice.Conditions conditions = Sacrifice.check(player, lineage);
		check(helper, !conditions.actDone() && !conditions.met(), "without a fulfilled act there's no sacrifice");
		check(helper, conditions.founder(), "a founder has no graves yet");
		lineage.fulfilledAct = true;
		check(helper, Sacrifice.check(player, lineage).actDone(), "after an act it's allowed (at night, at a full moon, at a grave)");
		Sacrifice.request(player);
		helper.succeed();
	}

	@GameTest
	@SuppressWarnings("unchecked")
	public void theFamilyChoosesAnHeirIfThePlayerDoesnt(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		QuestGameTests.age(player, lineage);
		ServerPlayer heir = QuestGameTests.dieToZombie(helper, player);
		check(helper, lineage.awaitingHeir, "waiting for a choice");
		Heirs.tick(heir, lineage);
		check(helper, lineage.awaitingHeir, "before the deadline nothing is chosen");
		// Pretend the deadline has passed.
		java.util.Map<java.util.UUID, Long> deadlines = field(Heirs.class, "DEADLINES");
		deadlines.put(heir.getUUID(), helper.getLevel().getGameTime() - 1);
		Heirs.tick(heir, lineage);
		check(helper, !lineage.awaitingHeir, "after the deadline the family chooses the first child");
		helper.succeed();
	}

}
