package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.CryptState;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.fersyvanderzee.bloodline.TestSupport.*;

/** Inns and their jobs, the roadside inn and the barrow crypt, built in the test world. */
public class PlaceGameTests {
	// --- Inn jobs -------------------------------------------------------------------------------------

	@SuppressWarnings("unchecked")
	private static List<Object> offers(BlockPos inn, int day, boolean roadside) {
		return (List<Object>) call(Inns.class, "offers", inn, day, roadside);
	}

	@GameTest
	public void innBoardsPostEveryKindOfJob(GameTestHelper helper) {
		Set<String> kinds = new HashSet<>();
		BlockPos inn = new BlockPos(100, 64, 100);
		for (int day = 1; day <= 60; day++) {
			List<Object> board = offers(inn, day, false);
			check(helper, board.size() == 5, "a board has 5 jobs");
			java.util.Map<String, Integer> perKind = new java.util.HashMap<>();
			for (Object offer : board) {
				String kind = read(offer, "kind").toString();
				kinds.add(kind);
				perKind.merge(kind, 1, Integer::sum);
			}
			check(helper, perKind.values().stream().allMatch(n -> n <= 2), "at most two jobs of a kind on day " + day + ": " + perKind);
		}
		check(helper, kinds.size() == 8, "over 60 days every kind of job is posted, got " + kinds);
		check(helper, !offers(inn, 1, false).equals(offers(inn, 2, false)), "the board changes every day");
		helper.succeed();
	}

	@GameTest
	public void roadsideInnsPayHalfAsMuchAgain(GameTestHelper helper) {
		BlockPos inn = new BlockPos(-300, 64, 40);
		for (int day = 1; day <= 20; day++) {
			List<Object> village = offers(inn, day, false);
			List<Object> roadside = offers(inn, day, true);
			for (int i = 0; i < village.size(); i++) {
				String kind = read(village.get(i), "kind").toString();
				int plain = (int) read(village.get(i), "emeralds");
				int more = (int) read(roadside.get(i), "emeralds");
				int expected = kind.equals("RUMOUR") || plain == 0 ? plain : (int) Math.ceil(plain * 1.5);
				check(helper, more == expected, kind + " pays " + expected + " at a roadside inn, got " + more);
			}
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 2400)
	public void everyInnJobCanBeTakenWithoutTrouble(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		BlockPos inn = helper.absolutePos(BlockPos.ZERO);
		Set<String> tried = new HashSet<>();
		for (int day = 1; day <= 60 && tried.size() < 8; day++) {
			List<Object> board = offers(inn, day, false);
			// The board's items are built with all their text.
			SimpleContainer container = new SimpleContainer(9);
			call(Inns.class, "fill", container, player, lineage, board);
			for (int slot = 0; slot < 9; slot++) {
				ItemStack icon = container.getItem(slot);
				if (icon.isEmpty()) continue;
				checkText(helper, icon.getHoverName(), "the quest board");
				var lore = icon.get(DataComponents.LORE);
				if (lore != null) lore.lines().forEach(line -> checkText(helper, line, "the quest board"));
			}
			for (Object offer : board) {
				String kind = read(offer, "kind").toString();
				if (!tried.add(kind)) continue;
				lineage.ownQuests.removeIf(q -> q.type == Type.INN);
				lineage.house.quests.removeIf(q -> q.type == Type.INN);
				player.getInventory().add(new ItemStack(Items.EMERALD, 64));
				call(Inns.class, "accept", player, lineage, offer);
				// Some jobs need a place nearby (a village, a structure, a biome); in the flat test world there may be none.
				quest(lineage, q -> q.type == Type.INN).ifPresent(q -> {
					checkText(helper, q.title, kind + " job title");
					checkText(helper, q.task, kind + " job task");
				});
			}
		}
		check(helper, tried.size() == 8, "every kind of job was tried: " + tried);
		helper.succeed();
	}

	@GameTest
	public void aHuntIsDoneAfterEnoughKills(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		BlockPos inn = new BlockPos(7, 64, 7);
		Object hunt = null;
		for (int day = 1; hunt == null; day++) {
			for (Object offer : offers(inn, day, false)) if (read(offer, "kind").toString().equals("HUNT")) hunt = offer;
		}
		call(Inns.class, "accept", player, lineage, hunt);
		Quest quest = needQuest(helper, lineage, "the hunt", q -> q.type == Type.INN && q.goal == Goal.KILL);
		var type = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(quest.targets.getFirst()));
		player.awardStat(net.minecraft.stats.Stats.ENTITY_KILLED.get(type), quest.required);
		tick(player);
		check(helper, quest(lineage, q -> q.type == Type.INN).isEmpty(), "the hunt is done");
		check(helper, count(player, Items.EMERALD) >= 4, "and paid in emeralds");
		helper.succeed();
	}

	@GameTest
	public void aDeliveryIsHandedInToAnInnkeeper(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		BlockPos inn = new BlockPos(9, 64, 3);
		Object delivery = null;
		for (int day = 1; delivery == null; day++) {
			for (Object offer : offers(inn, day, false)) if (read(offer, "kind").toString().equals("DELIVERY")) delivery = offer;
		}
		call(Inns.class, "accept", player, lineage, delivery);
		Quest quest = needQuest(helper, lineage, "the delivery", q -> q.type == Type.INN && q.goal == Goal.MANUAL);
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(quest.targets.getFirst()));
		int goods = quest.required;
		while (goods > 0) {
			int n = Math.min(goods, item.getDefaultMaxStackSize());
			player.getInventory().add(new ItemStack(item, n));
			goods -= n;
		}
		call(Inns.class, "handInDeliveries", player, lineage);
		tick(player);
		check(helper, quest(lineage, q -> q.type == Type.INN).isEmpty(), "the delivery is handed in");
		check(helper, count(player, item) == 0, "and the goods are gone");
		helper.succeed();
	}

	@GameTest
	public void aLostSatchelAppearsWhenYouArrive(GameTestHelper helper) {
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		BlockPos inn = helper.absolutePos(BlockPos.ZERO);
		Object satchel = null;
		for (int day = 1; satchel == null; day++) {
			for (Object offer : offers(inn, day, false)) if (read(offer, "kind").toString().equals("SATCHEL")) satchel = offer;
		}
		call(Inns.class, "accept", player, lineage, satchel);
		Quest quest = needQuest(helper, lineage, "the satchel", q -> q.type == Type.INN && q.goal == Goal.MANUAL);
		BlockPos spot = quest.place.orElseThrow().pos();
		helper.getLevel().getChunk(spot);
		player.snapTo(spot.getX() + 0.5, helper.getLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, spot.getX(), spot.getZ()), spot.getZ() + 0.5);
		Inns.tickJob(player, quest);
		check(helper, quest.seen.containsKey("placed"), "the satchel's chest appears when the player arrives");
		helper.succeed();
	}

	// --- The roadside inn -------------------------------------------------------------------------------

	private static BlockPos place(GameTestHelper helper, String structure, BlockPos at) {
		ServerLevel level = helper.getLevel();
		for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) level.getChunk((at.getX() >> 4) + x, (at.getZ() >> 4) + z);
		var server = level.getServer();
		var source = server.createCommandSourceStack().withLevel(level).withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
		server.getCommands().performPrefixedCommand(source, "place structure " + structure + " " + at.getX() + " " + at.getY() + " " + at.getZ());
		return at;
	}

	@GameTest(maxTicks = 400)
	public void aRoadsideInnGetsAnInnkeeperBehindTheBar(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos at = place(helper, "bloodline:roadside_inn", new BlockPos(2000, -60, 2000));
		BlockPos bell = null;
		for (BlockPos pos : BlockPos.betweenClosed(at.getX() - 40, -64, at.getZ() - 40, at.getX() + 40, 200, at.getZ() + 40)) {
			if (level.getBlockState(pos).is(Blocks.BELL)) {
				bell = pos.immutable();
				break;
			}
		}
		check(helper, bell != null, "the roadside inn has a bell");
		check(helper, Inns.isRoadside(level, bell), "a placed roadside inn is recognised as one");
		ServerPlayer player = join(helper);
		player.snapTo(bell.getX() + 0.5, bell.getY() - 1, bell.getZ() - 2.5);
		Inns.tick(player);
		List<Villager> keepers = level.getEntitiesOfClass(Villager.class, new AABB(bell).inflate(8), Inns::isInnkeeper);
		check(helper, keepers.size() == 1, "one innkeeper appears, got " + keepers.size());
		BlockState bellState = level.getBlockState(bell);
		BlockPos behind = bell.relative(bellState.getValue(BellBlock.FACING).getOpposite()).below();
		check(helper, keepers.getFirst().blockPosition().equals(behind), "the innkeeper stands behind the bar at " + behind + ", not " + keepers.getFirst().blockPosition());
		helper.succeed();
	}

	// --- The barrow crypt ---------------------------------------------------------------------------

	@GameTest(maxTicks = 600)
	public void aCryptCanBeSolvedOpenedAndLaidToRest(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos at = place(helper, "bloodline:crypt", new BlockPos(-2000, -60, -2000));
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		lineage.house.renown = 0;

		// Find the crypt's core from somewhere inside it: the stairs are under the mausoleum.
		Crypts.Core core = null;
		for (BlockPos pos : BlockPos.betweenClosed(at.getX() - 48, -64, at.getZ() - 48, at.getX() + 48, 200, at.getZ() + 48)) {
			if (level.getBlockState(pos).is(Blocks.SOUL_CAMPFIRE)) {
				var found = Crypts.coreAt(level, pos);
				if (found.isPresent()) {
					core = found.get();
					break;
				}
			}
		}
		check(helper, core != null, "a placed crypt is recognised, with its core");
		Crypts.Fallen fallen = Crypts.fallen(core.key());
		BlockPos hall = core.world(10, 1, 32);
		player.snapTo(hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5);
		player.tickCount = 1000;

		// The sigil is hidden in the catacombs (or the reliquary) the first time someone comes in.
		Crypts.tick(player);
		CryptState.Crypt crypt = CryptState.get(level.getServer()).get(core.key());
		check(helper, crypt.sigilHidden, "the sigil is hidden on the first visit");

		// The seal and the puzzle are protected in survival.
		var survivor = helper.makeMockPlayer(GameType.SURVIVAL);
		check(helper, !Crypts.canBreak(level, survivor, core.world(10, 2, 38)), "the seal can't be broken");
		check(helper, !Crypts.canBreak(level, survivor, core.world(7, 2, 31)), "a brazier can't be broken");

		// Lighting the braziers in the wrong order puts them out.
		List<BlockPos> braziers = new ArrayList<>();
		for (Crypts.Emblem emblem : fallen.order()) braziers.add(core.world(emblem.brazier));
		light(level, braziers.get(1));
		Crypts.tick(player);
		check(helper, !isLit(level, braziers.get(1)), "a flame lit out of turn goes out again");
		check(helper, !crypt.flamesLit, "and the puzzle isn't solved");

		// The right order solves it.
		for (BlockPos brazier : braziers) {
			light(level, brazier);
			Crypts.tick(player);
		}
		check(helper, crypt.flamesLit, "lighting the flames in the order the children died solves the puzzle");

		// Without the sigil the seal won't open; with it, it does and the founder rises.
		BlockPos seal = core.world(10, 2, 38);
		Crypts.onUseBlock(player, level, seal);
		check(helper, !crypt.opened, "without the sigil the seal stays shut");
		ItemStack sigil = takeSigil(level, core);
		if (sigil.isEmpty()) {
			BlockPos where = BlockPos.of(crypt.sigilChest);
			String contents = level.getBlockEntity(where) instanceof ChestBlockEntity chest
				? java.util.stream.IntStream.range(0, chest.getContainerSize()).mapToObj(chest::getItem).filter(st -> !st.isEmpty()).map(ItemStack::toString).toList().toString()
				: String.valueOf(level.getBlockState(where));
			check(helper, false, "the sigil is in a chest in the crypt; it was hidden at " + where + ", which holds " + contents
				+ "; pieces " + core.start().getPieces().size());
		}
		player.getInventory().add(sigil);
		Crypts.onUseBlock(player, level, seal);
		check(helper, crypt.opened, "the sigil opens the seal");
		check(helper, level.getBlockState(seal).isAir(), "the seal's stones are gone");
		List<Mob> lords = level.getEntitiesOfClass(Mob.class, new AABB(core.world(10, 1, 42)).inflate(12), Champions::isChampion);
		check(helper, lords.size() == 1, "the Barrow-Lord rises, got " + lords.size());

		// Laying the founder to rest gives the heirloom, renown and a Chronicle entry.
		int renown = lineage.house.renown;
		lords.getFirst().kill(level);
		check(helper, crypt.cleared, "the crypt is laid to rest");
		check(helper, lineage.house.renown == renown + BloodlineConfig.get().renown.cryptCleared, "laying the founder to rest earns renown");
		boolean heirloom = false;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (Heirlooms.lostOwner(player.getInventory().getItem(i)).isPresent()) heirloom = true;
		}
		check(helper, heirloom, "the fallen house's heirloom is in the inventory");
		String entry = lineage.house.chronicle.getLast().text().getString();
		check(helper, !entry.contains("Air"), "the Chronicle names the heirloom: " + entry);
		helper.succeed();
	}

	private static void light(ServerLevel level, BlockPos pos) {
		level.setBlockAndUpdate(pos, level.getBlockState(pos).setValue(CampfireBlock.LIT, true));
	}

	private static boolean isLit(ServerLevel level, BlockPos pos) {
		return level.getBlockState(pos).getValue(CampfireBlock.LIT);
	}

	private static ItemStack takeSigil(ServerLevel level, Crypts.Core core) {
		for (var piece : core.start().getPieces()) {
			for (BlockPos pos : BlockPos.betweenClosed(piece.getBoundingBox().minX(), piece.getBoundingBox().minY(), piece.getBoundingBox().minZ(),
				piece.getBoundingBox().maxX(), piece.getBoundingBox().maxY(), piece.getBoundingBox().maxZ())) {
				if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity chest)) continue;
				for (int slot = 0; slot < chest.getContainerSize(); slot++) {
					ItemStack stack = chest.getItem(slot);
					var data = stack.get(DataComponents.CUSTOM_DATA);
					if (data != null && data.copyTag().contains("bloodline_sigil")) return chest.removeItemNoUpdate(slot);
				}
			}
		}
		return ItemStack.EMPTY;
	}

	// --- The wayside shrine -----------------------------------------------------------------------------

	@GameTest(maxTicks = 400)
	public void aShrineBlessesOnceADayAndCountsAFirstVisit(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos at = place(helper, "bloodline:shrine_temperate", new BlockPos(4000, -60, 4000));
		List<BlockPos> candles = new ArrayList<>();
		BlockPos barrel = null;
		for (BlockPos pos : BlockPos.betweenClosed(at.getX() - 12, -64, at.getZ() - 12, at.getX() + 12, 200, at.getZ() + 12)) {
			BlockState state = level.getBlockState(pos);
			if (state.getBlock() instanceof net.minecraft.world.level.block.CandleBlock) candles.add(pos.immutable());
			if (state.is(Blocks.BARREL)) barrel = pos.immutable();
		}
		check(helper, candles.size() == 3, "the altar has three candles, got " + candles.size());
		check(helper, barrel != null, "the shrine has a traveller's barrel");
		check(helper, Shrines.shrineAt(level, candles.getFirst()).isPresent(), "a placed shrine is recognised as one");

		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		int renown = lineage.house.renown;
		ItemStack flint = new ItemStack(Items.FLINT_AND_STEEL);
		var result = Shrines.onUseBlock(player, level, candles.getFirst(), flint);
		check(helper, result == net.minecraft.world.InteractionResult.PASS, "an unlit candle still lights as usual");
		check(helper, player.hasEffect(net.minecraft.world.effect.MobEffects.LUCK), "a founder is blessed with luck");
		check(helper, lineage.house.renown == renown + BloodlineConfig.get().renown.shrineVisited, "a first visit earns renown, got "
			+ (lineage.house.renown - renown));
		check(helper, lineage.house.chronicle.getLast().text().getString().contains("prayed at"), "the Chronicle records the first visit");

		// Once a day; a second visit earns nothing more.
		player.removeAllEffects();
		Shrines.onUseBlock(player, level, candles.get(1), flint);
		check(helper, !player.hasEffect(net.minecraft.world.effect.MobEffects.LUCK), "only one blessing a day");
		check(helper, lineage.house.renown == renown + BloodlineConfig.get().renown.shrineVisited, "no renown for a second visit");

		// The next day, an ancestor who lived as a Slayer lends their strength; a lit candle counts too.
		lineage.blessedDay = 0;
		lineage.ancestors.add(new io.github.fersyvanderzee.bloodline.data.Ancestor("Edric", "slayer", 1, List.of(), 1, 2,
			net.minecraft.network.chat.Component.literal("Edric fell"), java.util.Optional.empty()));
		level.setBlockAndUpdate(candles.get(2), level.getBlockState(candles.get(2)).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
		result = Shrines.onUseBlock(player, level, candles.get(2), flint);
		check(helper, player.hasEffect(net.minecraft.world.effect.MobEffects.STRENGTH), "a Slayer's blessing is strength");
		check(helper, result == net.minecraft.world.InteractionResult.SUCCESS, "a lit candle isn't set on fire again");
		helper.succeed();
	}

	// --- The Fallen Manor -------------------------------------------------------------------------------

	@GameTest(maxTicks = 600)
	public void aManorCanBeFreedClaimedAndKeepsTheFamilyGraves(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos at = place(helper, "bloodline:fallen_manor", new BlockPos(-4000, -60, 4000));
		BlockPos lectern = null;
		for (BlockPos pos : BlockPos.betweenClosed(at.getX() - 40, -64, at.getZ() - 40, at.getX() + 40, 200, at.getZ() + 40)) {
			if (level.getBlockState(pos).is(Blocks.LECTERN)) {
				lectern = pos.immutable();
				break;
			}
		}
		check(helper, lectern != null, "the manor has a lectern");
		Manors.Site site = Manors.siteAt(level, lectern).orElse(null);
		check(helper, site != null && site.world(Manors.LECTERN).equals(lectern), "a placed manor is recognised, with its lectern where expected");

		// A family with one ancestor already, so the graveyard has someone to bury.
		ServerPlayer player = join(helper);
		Lineage lineage = lineage(helper, player);
		QuestGameTests.age(player, lineage);
		String founder = lineage.current.name();
		player = QuestGameTests.dieToZombie(helper, player);
		Heirs.choose(player, 1);
		int renown = lineage.house.renown;

		// Stepping onto the grounds brings out the Usurper.
		BlockPos courtyard = site.world(Manors.USURPER);
		player.snapTo(courtyard.getX() + 0.5, courtyard.getY(), courtyard.getZ() + 2.5);
		Manors.tick(player);
		List<Mob> usurpers = level.getEntitiesOfClass(Mob.class, new AABB(courtyard).inflate(16),
			m -> m.entityTags().contains("bloodline_key=" + Manors.KEY_PREFIX + site.key()));
		check(helper, usurpers.size() == 1, "the Usurper comes out, got " + usurpers.size());
		Manors.onUseBlock(player, level, lectern);
		check(helper, lineage.house.seat.isEmpty(), "the manor can't be claimed while they hold it");

		// Driving them out frees the manor and hides the will's four pages.
		Champions.onDeath(level.getServer(), usurpers.getFirst());
		usurpers.getFirst().discard();
		var manor = io.github.fersyvanderzee.bloodline.data.ManorState.get(level.getServer()).get(site.key());
		check(helper, manor.cleared && manor.pagesHidden, "the manor is free and the pages are hidden");
		for (BlockPos spot : Manors.PAGES) {
			var container = (net.minecraft.world.Container) level.getBlockEntity(site.world(spot));
			boolean found = false;
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack stack = container.getItem(i);
				if (stack.is(Items.PAPER)) {
					player.getInventory().add(stack.copy());
					container.setItem(i, ItemStack.EMPTY);
					found = true;
				}
			}
			check(helper, found, "a page lies at " + spot);
		}

		// With the whole will, the house claims the manor.
		player.snapTo(lectern.getX() + 0.5, lectern.getY(), lectern.getZ() + 1.5);
		check(helper, Manors.claim(player), "the claim succeeds");
		check(helper, lineage.house.seat.isPresent() && lineage.house.seat.get().manor.equals(site.key()), "the manor is the house's seat");
		check(helper, count(player, Items.PAPER) == 0, "the pages are used up");
		check(helper, lineage.house.renown == renown + BloodlineConfig.get().renown.manorClaimed, "claiming earns renown, got "
			+ (lineage.house.renown - renown));
		check(helper, lineage.house.steward.isPresent()
			&& lineage.house.steward.get().home.pos().equals(site.world(Manors.STEWARD)), "the steward moves into the great hall");
		check(helper, Manors.atSeat(player, lineage), "the seat counts as a place for the Sacrifice");
		check(helper, graveName(level, site, 0).contains(founder), "the founder's gravestone stands in the garden, got " + graveName(level, site, 0));

		// The next death adds a gravestone.
		QuestGameTests.age(player, lineage);
		String heir = lineage.current.name();
		player = QuestGameTests.dieToZombie(helper, player);
		check(helper, graveName(level, site, 1).contains(heir), "a new death adds a gravestone, got " + graveName(level, site, 1));
		helper.succeed();
	}

	private static String graveName(ServerLevel level, Manors.Site site, int plot) {
		if (!(level.getBlockEntity(site.world(Manors.GRAVES.get(plot).south())) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) {
			return "(no sign)";
		}
		return sign.getText(net.minecraft.world.level.block.entity.SignTextSlot.FRONT).getMessages(false).getFirst().getString();
	}
}
