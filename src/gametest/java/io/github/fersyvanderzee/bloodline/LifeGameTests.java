package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityCombatEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static io.github.fersyvanderzee.bloodline.TestSupport.*;

/** Lives and deaths at the edges: the Sacrifice, the void, strong killers, leaving a house, other mods' fake players and Peaceful. */
public class LifeGameTests {
	private static ServerPlayer respawn(GameTestHelper helper, ServerPlayer player) {
		return helper.getLevel().getServer().getPlayerList().respawn(player, false, Entity.RemovalReason.KILLED);
	}

	private static ServerPlayer dieTo(GameTestHelper helper, ServerPlayer player, EntityType<? extends Mob> killerType) {
		Mob killer = killerType.create(helper.getLevel(), EntitySpawnReason.EVENT);
		killer.snapTo(player.position());
		player.die(helper.getLevel().damageSources().mobAttack(killer));
		return respawn(helper, player);
	}

	@GameTest
	public void aSacrificeKeepsEveryBoonAndOwesNoDebt(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		QuestGameTests.age(player, lineage);
		Member m = lineage.current;
		lineage.current = new Member(m.name(), m.generation(), List.of("stalwart", "swift", "lucky"), m.bornDay(), m.statsAtBirth());
		lineage.fulfilledAct = true;
		int renown = lineage.house.renown;

		Set<UUID> sacrificing = field(Sacrifice.class, "SACRIFICING");
		sacrificing.add(player.getUUID());
		// The ritual kills the player as the game's /kill does. A test player's game never finishes loading, which keeps them
		// invulnerable to that, so the same death is dealt directly.
		player.die(helper.getLevel().damageSources().genericKill());
		check(helper, lineage.parent() != null, "the ritual killed " + m.name());
		respawn(helper, player);

		Ancestor devoted = lineage.parent();
		check(helper, Epithets.DEVOTED.equals(Epithets.id(devoted.epithet())), "a sacrifice is remembered as the Devoted, got " + devoted.epithet());
		check(helper, lineage.house.renown == renown + Sacrifice.renown(), "a sacrifice earns " + Sacrifice.renown() + " renown, got "
			+ (lineage.house.renown - renown));
		check(helper, lineage.house.sacrifices == 1, "the house counts the sacrifice");
		check(helper, quest(lineage, q -> q.type == Type.BLOOD_DEBT).isEmpty(), "nobody owes a blood debt for a sacrifice");
		for (Member child : lineage.candidates) {
			check(helper, child.traits().containsAll(List.of("stalwart", "swift", "lucky")), child.name() + " keeps every boon, got " + child.traits());
		}
		helper.succeed();
	}

	@GameTest
	public void aBodyLostToTheVoidLeavesNoRestingPlace(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		QuestGameTests.age(player, lineage);
		player.snapTo(player.getX(), helper.getLevel().getMinY() - 20, player.getZ());
		player.die(helper.getLevel().damageSources().fellOutOfWorld());
		respawn(helper, player);
		check(helper, lineage.parent().place().isEmpty(), "the void leaves no grave");
		check(helper, quest(lineage, q -> q.type == Type.RESTING_PLACE).isEmpty(),
			"no duty to visit a place nobody can reach; the family has " + describe(lineage.allQuests()));
		helper.succeed();
	}

	@GameTest
	public void strongAndRareKillersOnlyNeedSlayingOnce(GameTestHelper helper) {
		record Case(EntityType<? extends Mob> killer, int required) {
		}
		for (Case c : List.of(new Case(EntityTypes.ELDER_GUARDIAN, 1), new Case(EntityTypes.EVOKER, 1), new Case(EntityTypes.HOGLIN, 2),
			new Case(EntityTypes.ZOMBIE, 5))) {
			ServerPlayer player = join(helper);
			Lineage lineage = lineage(helper, player);
			dieTo(helper, player, c.killer());
			Quest debt = needQuest(helper, lineage, "a blood debt", q -> q.type == Type.BLOOD_DEBT);
			check(helper, debt.required == c.required(), "killed by " + c.killer().getDescription().getString() + ": slay "
				+ c.required() + ", got " + debt.required);
		}
		helper.succeed();
	}

	@GameTest
	public void heirsNeverHaveTooManyTraitsAndAlwaysSomethingNew(GameTestHelper helper) {
		RandomSource random = RandomSource.create(7);
		List<String> parent = List.of("stalwart", "swift", "mighty", "clumsy");
		int max = BloodlineConfig.get().families.maxTraits;
		for (int i = 0; i < 500; i++) {
			List<String> child = Traits.rollHeir(parent, random, i % 5 == 0);
			check(helper, child.size() <= max, "at most " + max + " traits, got " + child);
			check(helper, child.stream().anyMatch(t -> !parent.contains(t)), "every heir brings something new, got " + child);
			check(helper, child.stream().map(Traits::get).map(Trait::group).distinct().count() == child.size(),
				"no two traits of the same kind (such as Stalwart and Frail), got " + child);
		}
		helper.succeed();
	}

	@GameTest
	public void leavingAHouseFoundsANewOne(GameTestHelper helper) {
		ServerPlayer head = join(helper);
		ServerPlayer friend = join(helper);
		Lineage headLine = lineage(helper, head);
		Lineage friendLine = lineage(helper, friend);
		Houses.invite(head, friend);
		Houses.accept(friend, true);
		check(helper, friendLine.house == headLine.house, "the friend joined");
		Houses.leave(friend);
		check(helper, friendLine.house != headLine.house, "after leaving, the friend has a house of their own");
		check(helper, friendLine.isHead(friend.getUUID()), "and heads it");
		check(helper, headLine.house.members.equals(List.of(head.getUUID())), "the old house keeps its head");
		check(helper, BloodlineState.get(helper.getLevel().getServer()).get(friend.getUUID()) == friendLine, "the friend's family is kept");
		helper.succeed();
	}

	@GameTest
	public void aBossKilledByAnotherModsFakePlayerDoesNotCrash(GameTestHelper helper) {
		// Machines from other mods (deployers, turtles) act as players without a family.
		ServerPlayer machine = join(helper);
		BloodlineState.get(helper.getLevel().getServer()).remove(machine.getUUID());
		Mob wither = EntityTypes.WITHER.create(helper.getLevel(), EntitySpawnReason.EVENT);
		ServerEntityCombatEvents.AFTER_KILLED_OTHER_ENTITY.invoker()
			.afterKilledOtherEntity(helper.getLevel(), machine, wither, helper.getLevel().damageSources().playerAttack(machine));
		helper.succeed();
	}

	@GameTest
	public void championsWaitWhileTheWorldIsPeaceful(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		MinecraftServer server = helper.getLevel().getServer();
		Difficulty before = helper.getLevel().getDifficulty();
		boolean appeared;
		try {
			server.setDifficulty(Difficulty.PEACEFUL, true);
			appeared = Champions.spawn(player, Champions.ROTGUT, "peaceful_test", Optional.empty(), List.of());
		} finally {
			server.setDifficulty(before, true);
		}
		check(helper, !appeared, "on Peaceful a champion would vanish at once, so it doesn't appear");
		helper.succeed();
	}
}
