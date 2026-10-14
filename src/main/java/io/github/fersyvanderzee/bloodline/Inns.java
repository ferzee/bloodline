package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Reward;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.StructureTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inns. Every village gets an innkeeper near its bell the first time a player comes by. Talking
 * to them opens the inn's quest board: bounties on named beasts, deliveries and rumours of
 * places nobody has found yet. The board's jobs change every day and are worked out from the
 * inn's position and the day, so nothing about them needs to be saved.
 */
public final class Inns {
	private static final String TAG = "bloodline_innkeeper";
	private static final String INN_TAG = "bloodline_inn=";
	private static final String LINE_PREFIX = "inn:";
	private static final int OFFERS = 5;
	private static final int VILLAGE_RADIUS = 48;
	private static final long RESPAWN_COOLDOWN = 24000;
	private static final double MAX_TALK_DISTANCE = 8;

	private static final List<String> ADJECTIVES = List.of("Sleeping", "Prancing", "Drowned", "Golden", "Crooked", "Laughing", "Wandering",
		"Rusty", "Silver", "Hungry", "Lucky", "Blind", "Merry", "Broken", "Howling", "Green");
	private static final List<String> NOUNS = List.of("Griffin", "Pony", "Lantern", "Goat", "Kettle", "Axe", "Boar", "Anvil", "Raven",
		"Barrel", "Creeper", "Pickaxe", "Mooshroom", "Hearth", "Shepherd", "Wolf");

	private record Delivery(Item item, int count, int emeralds) {
	}

	private static final List<Delivery> DELIVERIES = List.of(
		new Delivery(Items.BREAD, 16, 4), new Delivery(Items.COOKED_BEEF, 12, 5), new Delivery(Items.LEATHER, 10, 4),
		new Delivery(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("white_wool")), 16, 4), new Delivery(Items.TORCH, 48, 3), new Delivery(Items.IRON_INGOT, 8, 6),
		new Delivery(Items.HONEY_BOTTLE, 3, 5), new Delivery(Items.PUMPKIN_PIE, 6, 5), new Delivery(Items.BOOK, 4, 5),
		new Delivery(Items.ARROW, 32, 4), new Delivery(Items.CAKE, 1, 4), new Delivery(Items.PAPER, 24, 3), new Delivery(Items.COAL, 24, 3)
	);

	/** The place's name is in the language file as bloodline.rumour.<key>. */
	private record Rumour(ResourceKey<Structure> structure, String key, int cost) {
		MutableComponent name() {
			return Text.t("rumour." + key);
		}
	}

	private static final List<Rumour> RUMOURS = List.of(
		new Rumour(BuiltinStructures.WOODLAND_MANSION, "mansion", 8),
		new Rumour(BuiltinStructures.JUNGLE_TEMPLE, "jungle_temple", 4),
		new Rumour(BuiltinStructures.DESERT_PYRAMID, "desert_pyramid", 4),
		new Rumour(BuiltinStructures.IGLOO, "igloo", 3),
		new Rumour(BuiltinStructures.SHIPWRECK, "shipwreck", 3),
		new Rumour(BuiltinStructures.TRAIL_RUINS, "trail_ruins", 5),
		new Rumour(BuiltinStructures.TRIAL_CHAMBERS, "trial_chambers", 6),
		new Rumour(BuiltinStructures.OCEAN_MONUMENT, "monument", 6)
	);

	private enum Kind { BOUNTY, DELIVERY, RUMOUR, COURIER, SATCHEL, HUNT, EXPEDITION, CRYPT }

	/** How often each kind of job is posted, relative to the others. */
	private static final Map<Kind, Integer> WEIGHTS = Map.of(Kind.BOUNTY, 2, Kind.DELIVERY, 3, Kind.RUMOUR, 2, Kind.COURIER, 2,
		Kind.SATCHEL, 2, Kind.HUNT, 3, Kind.EXPEDITION, 2, Kind.CRYPT, 2);

	/** A creature to hunt: an entity id, how many, and the pay. */
	private record Hunt(String entity, int count, int emeralds) {
		Component name() {
			return BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(entity)).getDescription();
		}
	}

	private static final List<Hunt> HUNTS = List.of(
		new Hunt("minecraft:spider", 8, 5), new Hunt("minecraft:rabbit", 5, 4), new Hunt("minecraft:zombie", 10, 4),
		new Hunt("minecraft:skeleton", 8, 5), new Hunt("minecraft:creeper", 5, 6), new Hunt("minecraft:drowned", 5, 6),
		new Hunt("minecraft:witch", 2, 7), new Hunt("minecraft:slime", 6, 5), new Hunt("minecraft:phantom", 3, 7),
		new Hunt("minecraft:enderman", 3, 8), new Hunt("minecraft:pillager", 4, 6), new Hunt("minecraft:fox", 2, 5)
	);

	/** A biome to reach, and the pay. Its name comes from Minecraft's own biome names. */
	private record Expedition(String biome, int emeralds) {
		Component name() {
			return Component.translatable("biome." + biome.replace(':', '.'));
		}
	}

	private static final List<Expedition> EXPEDITIONS = List.of(
		new Expedition("minecraft:mushroom_fields", 10), new Expedition("minecraft:cherry_grove", 5), new Expedition("minecraft:badlands", 6),
		new Expedition("minecraft:ice_spikes", 7), new Expedition("minecraft:jungle", 5), new Expedition("minecraft:mangrove_swamp", 5),
		new Expedition("minecraft:flower_forest", 4), new Expedition("minecraft:pale_garden", 6), new Expedition("minecraft:dark_forest", 4),
		new Expedition("minecraft:snowy_slopes", 4), new Expedition("minecraft:savanna_plateau", 4), new Expedition("minecraft:desert", 3)
	);

	private static final String LETTER = "letter";
	private static final String SATCHEL = "satchel";
	private static final String ITEM_TAG = "bloodline_job";

	/** One job on a board. Exactly one of bounty, delivery or rumour is set, depending on kind. */
	private record Offer(String id, Kind kind, Champions.Kind bounty, BlockPos bountyPos, Delivery delivery, Rumour rumour, Hunt hunt,
						 Expedition expedition, int emeralds) {
	}

	/** When an innkeeper was last spawned at each bell, so a missing one isn't replaced every second. */
	private static final Map<Long, Long> LAST_SPAWN = new HashMap<>();

	/** Milestone recorded the first time the family speaks to an innkeeper. */
	private static final String MET_INNKEEPER = "met_innkeeper";
	/** Target of the "speak to an innkeeper" quest. */
	private static final String SPEAK_TARGET = "innkeeper";

	private Inns() {
	}

	/**
	 * Gives families that have never been to an inn a quest pointing them to the nearest village,
	 * with a map. Called when a house is founded and when an older family joins.
	 */
	public static void addIntroQuest(ServerPlayer player, Lineage lineage) {
		if (lineage.house.milestones.contains(MET_INNKEEPER)) return;
		if (lineage.allQuests().stream().anyMatch(q -> q.goal == Goal.MANUAL && q.targets.contains(SPEAK_TARGET))) return;
		MutableComponent task = Text.t("hearth.task");
		ServerLevel overworld = player.level().getServer().overworld();
		BlockPos village = player.level().dimension() == Level.OVERWORLD
			? StructureSearch.find(overworld, StructureTags.VILLAGE, player.blockPosition(), BloodlineConfig.get().structures.searchRadiusChunks).orElse(null)
			: null;
		if (village != null) {
			task = Text.t("hearth.task_nearest", village.getX() + ", " + village.getZ());
			Questlines.giveMap(player, GlobalPos.of(Level.OVERWORLD, village), Text.t("hearth.map"));
		}
		Quests.add(player, lineage, Quest.of(Type.FOUNDING, Text.t("hearth.title"), task, Goal.MANUAL, List.of(SPEAK_TARGET), 1,
			Reward.item(2, "emerald", 5)));
		Lineages.markDirty(player.level().getServer());
	}

	private static void markMetInnkeeper(ServerPlayer player, Lineage lineage, BlockPos inn) {
		for (Quest quest : lineage.allQuests()) {
			if (quest.goal == Goal.MANUAL && quest.targets.contains(SPEAK_TARGET)) quest.progress = quest.required;
		}
		if (lineage.house.milestones.add(MET_INNKEEPER)) {
			Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.first_inn", lineage.current.name(), innName(inn)));
		}
	}

	public static String innName(BlockPos bell) {
		RandomSource random = RandomSource.create(bell.asLong());
		return "The " + ADJECTIVES.get(random.nextInt(ADJECTIVES.size())) + " " + NOUNS.get(random.nextInt(NOUNS.size()));
	}

	public static boolean isInnkeeper(net.minecraft.world.entity.Entity entity) {
		return entity.entityTags().contains(TAG);
	}

	// --- Innkeepers ---------------------------------------------------------------------------

	/** Puts an innkeeper in any village the player is in that doesn't have one. */
	public static void tick(ServerPlayer player) {
		ServerLevel level = player.level();
		Optional<BlockPos> bell = level.getPoiManager().findClosest(type -> type.is(PoiTypes.MEETING), player.blockPosition(),
			VILLAGE_RADIUS, PoiManager.Occupancy.ANY);
		if (bell.isEmpty()) return;
		// Optionally only bells that are part of a generated village attract an innkeeper.
		if (!BloodlineConfig.get().inns.innkeepersAtPlayerBells && !isRoadside(level, bell.get())
			&& !level.structureManager().getStructureWithPieceAt(bell.get(), StructureTags.VILLAGE).isValid()) return;
		boolean hasInnkeeper = !level.getEntitiesOfClass(Villager.class, new AABB(bell.get()).inflate(VILLAGE_RADIUS), Inns::isInnkeeper).isEmpty();
		if (hasInnkeeper) return;
		long now = level.getGameTime();
		Long last = LAST_SPAWN.get(bell.get().asLong());
		if (last != null && now - last < RESPAWN_COOLDOWN) return;
		LAST_SPAWN.put(bell.get().asLong(), now);
		spawnInnkeeper(level, bell.get(), player);
	}

	private static void spawnInnkeeper(ServerLevel level, BlockPos bell, ServerPlayer player) {
		Villager innkeeper = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
		if (innkeeper == null) return;
		// Stand right by the bell (behind the counter at a roadside inn), or somewhere near it.
		BlockPos pos = spotBeside(level, bell).orElseGet(() -> Champions.findSpot(level, bell));
		innkeeper.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		innkeeper.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
		innkeeper.setNoAi(true);
		innkeeper.setPermanentlyInvulnerable(true);
		innkeeper.setPersistenceRequired();
		innkeeper.setCustomName(Text.t("innkeeper.name", Names.given(level.getRandom(), "")).withStyle(ChatFormatting.GOLD));
		innkeeper.setCustomNameVisible(true);
		innkeeper.addTag(TAG);
		innkeeper.addTag(INN_TAG + bell.asLong());
		level.addFreshEntity(innkeeper);
		player.sendSystemMessage(Text.t(isRoadside(level, bell) ? "inn.found_roadside" : "inn.found",
			Component.literal(innName(bell)).withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY));
	}

	private static BlockPos innOf(Villager innkeeper) {
		for (String tag : innkeeper.entityTags()) {
			if (tag.startsWith(INN_TAG)) return BlockPos.of(Long.parseLong(tag.substring(INN_TAG.length())));
		}
		return innkeeper.blockPosition();
	}

	/** Right-clicking an innkeeper hands in deliveries, then opens the quest board. */
	public static void onInteract(ServerPlayer player, Villager innkeeper) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || lineage.awaitingHeir) return;
		handInDeliveries(player, lineage);
		BlockPos inn = innOf(innkeeper);
		handInJobItems(player, lineage, inn);
		markMetInnkeeper(player, lineage, inn);
		int day = Lineages.today(player.level().getServer());
		boolean roadside = isRoadside(player.level(), inn);
		List<Offer> offers = offers(inn, day, roadside);
		SimpleContainer board = new SimpleContainer(9);
		fill(board, player, lineage, offers);
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new BoardMenu(id, inventory, board, player, innkeeper, offers),
			Text.t(roadside ? "inn.board_title_roadside" : "inn.board_title", innName(inn))));
	}

	// --- The quest board ----------------------------------------------------------------------

	/** Roadside inns pay half as much again for every job. */
	private static final double ROADSIDE_PAY = 1.5;
	private static final ResourceKey<Structure> ROADSIDE_INN = ResourceKey.create(net.minecraft.core.registries.Registries.STRUCTURE,
		Bloodline.id("roadside_inn"));

	/** Whether this bell stands in one of the mod's roadside inns rather than a village. */
	static boolean isRoadside(ServerLevel level, BlockPos bell) {
		return level.structureManager().getStructureWithPieceAt(bell, holder -> holder.is(ROADSIDE_INN)).isValid();
	}

	/** The nearest free spot within two blocks of the bell, at or just below its height. */
	private static Optional<BlockPos> spotBeside(ServerLevel level, BlockPos bell) {
		// Behind the bar: the bell faces the guests, so the innkeeper stands on the other side of it.
		BlockState bellState = level.getBlockState(bell);
		if (bellState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
			BlockPos behind = bell.relative(bellState.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite()).below();
			if (level.isEmptyBlock(behind) && level.isEmptyBlock(behind.above()) && !level.isEmptyBlock(behind.below())) return Optional.of(behind);
		}
		BlockPos best = null;
		for (BlockPos pos : BlockPos.betweenClosed(bell.offset(-2, -2, -2), bell.offset(2, 0, 2))) {
			if (pos.equals(bell) || !level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above()) || level.isEmptyBlock(pos.below())) continue;
			if (best == null || pos.distSqr(bell) < best.distSqr(bell)) best = pos.immutable();
		}
		return Optional.ofNullable(best);
	}

	private static List<Offer> offers(BlockPos inn, int day, boolean roadside) {
		RandomSource random = RandomSource.create(inn.asLong() * 31 + day);
		List<Offer> offers = new ArrayList<>();
		Map<Kind, Integer> posted = new java.util.EnumMap<>(Kind.class);
		for (int slot = 0; slot < OFFERS; slot++) {
			String id = inn.asLong() + ":" + day + ":" + slot;
			Kind kind = pickKind(random, posted);
			posted.merge(kind, 1, Integer::sum);
			BlockPos somewhere = awayFrom(inn, random, 100, 250);
			offers.add(switch (kind) {
				case BOUNTY -> new Offer(id, kind, Champions.BOUNTIES.get(random.nextInt(Champions.BOUNTIES.size())), awayFrom(inn, random, 120, 300),
					null, null, null, null, 6 + random.nextInt(6));
				case DELIVERY -> {
					Delivery delivery = DELIVERIES.get(random.nextInt(DELIVERIES.size()));
					yield new Offer(id, kind, null, null, delivery, null, null, null, delivery.emeralds());
				}
				case RUMOUR -> {
					Rumour rumour = RUMOURS.get(random.nextInt(RUMOURS.size()));
					yield new Offer(id, kind, null, null, null, rumour, null, null, rumour.cost());
				}
				case COURIER -> new Offer(id, kind, null, somewhere, null, null, null, null, 0);
				case SATCHEL -> new Offer(id, kind, null, somewhere, null, null, null, null, 5 + random.nextInt(4));
				case HUNT -> {
					Hunt hunt = HUNTS.get(random.nextInt(HUNTS.size()));
					yield new Offer(id, kind, null, null, null, null, hunt, null, hunt.emeralds());
				}
				case EXPEDITION -> {
					Expedition expedition = EXPEDITIONS.get(random.nextInt(EXPEDITIONS.size()));
					yield new Offer(id, kind, null, null, null, null, null, expedition, expedition.emeralds());
				}
				case CRYPT -> new Offer(id, kind, null, null, null, null, null, null, 8 + random.nextInt(5));
			});
		}
		if (!roadside) return offers;
		return offers.stream().map(o -> o.kind() == Kind.RUMOUR || o.emeralds() == 0 ? o
			: new Offer(o.id(), o.kind(), o.bounty(), o.bountyPos(), o.delivery(), o.rumour(), o.hunt(), o.expedition(),
				(int) Math.ceil(o.emeralds() * ROADSIDE_PAY))).toList();
	}

	/** A kind of job by weight, posting at most two of the same kind on one board. */
	private static Kind pickKind(RandomSource random, Map<Kind, Integer> posted) {
		List<Kind> pool = new ArrayList<>();
		WEIGHTS.forEach((kind, weight) -> {
			if (posted.getOrDefault(kind, 0) < 2) for (int i = 0; i < weight; i++) pool.add(kind);
		});
		pool.sort(java.util.Comparator.comparing(Enum::ordinal));
		return pool.get(random.nextInt(pool.size()));
	}

	private static BlockPos awayFrom(BlockPos origin, RandomSource random, int min, int max) {
		double angle = random.nextDouble() * Math.PI * 2;
		int distance = min + random.nextInt(max - min);
		return origin.offset((int) (Math.cos(angle) * distance), 0, (int) (Math.sin(angle) * distance));
	}

	private static boolean taken(Lineage lineage, Offer offer) {
		return lineage.allQuests().stream().anyMatch(q -> q.line.filter((LINE_PREFIX + offer.id())::equals).isPresent());
	}

	private static long activeJobs(Lineage lineage) {
		return lineage.allQuests().stream().filter(q -> q.type == Type.INN).count();
	}

	private static void fill(SimpleContainer board, ServerPlayer player, Lineage lineage, List<Offer> offers) {
		boolean full = activeJobs(lineage) >= BloodlineConfig.get().inns.maxJobsPerPlayer;
		for (int slot = 0; slot < offers.size(); slot++) {
			Offer offer = offers.get(slot);
			boolean taken = taken(lineage, offer);
			ItemStack icon = ItemStack.EMPTY;
			List<Component> lore = new ArrayList<>();
			switch (offer.kind()) {
				case BOUNTY -> {
					icon = new ItemStack(Items.IRON_SWORD);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.bounty", offer.bounty().name()), ChatFormatting.RED));
					lore.add(line(Text.t("inn.bounty.seen", roughDistance(player, offer.bountyPos()), direction(player.blockPosition(), offer.bountyPos())),
						ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.bounty.reward", offer.emeralds(), BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case DELIVERY -> {
					icon = new ItemStack(offer.delivery().item(), Math.min(offer.delivery().count(), offer.delivery().item().getDefaultMaxStackSize()));
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.delivery", offer.delivery().count(), itemName(offer.delivery().item())), ChatFormatting.YELLOW));
					lore.add(line(Text.t("inn.delivery.where"), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.delivery.reward", offer.emeralds(), BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case RUMOUR -> {
					icon = new ItemStack(Items.MAP);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.rumour", offer.rumour().name()), ChatFormatting.AQUA));
					lore.add(line(Text.t("inn.rumour.seen", offer.rumour().name()), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.rumour.cost", offer.rumour().cost()), ChatFormatting.GOLD));
					lore.add(line(Text.t("inn.rumour.reward", BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case COURIER -> {
					icon = new ItemStack(Items.PAPER);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.courier"), ChatFormatting.GOLD));
					lore.add(line(Text.t("inn.courier.line"), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.courier.reward", BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case SATCHEL -> {
					icon = new ItemStack(Items.BUNDLE);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.satchel"), ChatFormatting.GOLD));
					lore.add(line(Text.t("inn.satchel.line", roughDistance(player, offer.bountyPos()), direction(player.blockPosition(), offer.bountyPos())),
						ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.delivery.reward", offer.emeralds(), BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case HUNT -> {
					icon = new ItemStack(Items.BOW);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.hunt", offer.hunt().count(), offer.hunt().name()), ChatFormatting.RED));
					lore.add(line(Text.t("inn.hunt.line"), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.delivery.reward", offer.emeralds(), BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case EXPEDITION -> {
					icon = new ItemStack(Items.COMPASS);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.expedition", offer.expedition().name()), ChatFormatting.AQUA));
					lore.add(line(Text.t("inn.expedition.line", offer.expedition().name()), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.delivery.reward", offer.emeralds(), BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
				case CRYPT -> {
					icon = new ItemStack(Items.SKELETON_SKULL);
					icon.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.crypt"), ChatFormatting.DARK_PURPLE));
					lore.add(line(Text.t("inn.crypt.line"), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.crypt.line2"), ChatFormatting.GRAY));
					lore.add(line(Text.t("inn.delivery.reward", offer.emeralds(), BloodlineConfig.get().renown.innJob), ChatFormatting.GREEN));
				}
			}
			if (taken) {
				lore.add(line(Text.t("inn.accepted"), ChatFormatting.DARK_GREEN));
				icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
			} else if (full) {
				lore.add(line(Text.t("inn.full", BloodlineConfig.get().inns.maxJobsPerPlayer), ChatFormatting.DARK_RED));
			} else {
				lore.add(line(Text.t("inn.click_accept"), ChatFormatting.WHITE));
			}
			icon.set(DataComponents.LORE, new ItemLore(lore));
			board.setItem(slot, icon);
		}
		ItemStack info = new ItemStack(Items.PAPER);
		info.set(DataComponents.CUSTOM_NAME, name(Text.t("book.cover.house", lineage.house.name), ChatFormatting.GOLD));
		info.set(DataComponents.LORE, new ItemLore(List.of(
			line(Text.t("inn.info.jobs", activeJobs(lineage), BloodlineConfig.get().inns.maxJobsPerPlayer), ChatFormatting.GRAY),
			line(Text.t("duties.renown", lineage.house.renown), ChatFormatting.YELLOW),
			line(Text.t("inn.info.daily"), ChatFormatting.DARK_GRAY))));
		board.setItem(8, info);
		board.setItem(STEWARD_SLOT, stewardOffer(lineage));
	}

	private static final int STEWARD_SLOT = 6;

	/** The board's last job: hiring a steward, or sending yours to your current home. */
	private static ItemStack stewardOffer(Lineage lineage) {
		ItemStack icon = new ItemStack(Items.LECTERN);
		List<Component> lore = new ArrayList<>();
		if (lineage.house.steward.isEmpty()) {
			icon.set(DataComponents.CUSTOM_NAME, name(Text.t("steward.hire"), ChatFormatting.GOLD));
			lore.add(line(Text.t("steward.hire.line1"), ChatFormatting.GRAY));
			lore.add(line(Text.t("steward.hire.line2"), ChatFormatting.GRAY));
			lore.add(line(Text.t("steward.hire.vault", Stewards.vaultSlots(lineage.house.renown)), ChatFormatting.YELLOW));
			lore.add(line(Text.t("steward.hire.cost", Stewards.hireCost()), ChatFormatting.GOLD));
		} else {
			String name = lineage.house.steward.get().name;
			icon.set(DataComponents.CUSTOM_NAME, name(Text.t("steward.move", name), ChatFormatting.GOLD));
			lore.add(line(Text.t("steward.move.line"), ChatFormatting.GRAY));
			lore.add(line(Text.t("steward.move.cost", Stewards.moveCost()), ChatFormatting.GOLD));
		}
		lore.add(line(Text.t("inn.click_agree"), ChatFormatting.WHITE));
		icon.set(DataComponents.LORE, new ItemLore(lore));
		return icon;
	}

	/** A one-row chest menu whose items can't be taken; clicking a job accepts it. */
	private static final class BoardMenu extends ChestMenu {
		private final SimpleContainer board;
		private final ServerPlayer viewer;
		private final Villager innkeeper;
		private final List<Offer> offers;

		BoardMenu(int id, Inventory inventory, SimpleContainer board, ServerPlayer viewer, Villager innkeeper, List<Offer> offers) {
			super(MenuType.GENERIC_9x1, id, inventory, board, 1);
			this.board = board;
			this.viewer = viewer;
			this.innkeeper = innkeeper;
			this.offers = offers;
		}

		@Override
		public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
			Lineage lineage = Lineages.get(viewer);
			if (lineage != null && slotIndex >= 0 && slotIndex < offers.size()) {
				accept(viewer, lineage, offers.get(slotIndex));
				fill(board, viewer, lineage, offers);
			} else if (lineage != null && slotIndex == STEWARD_SLOT) {
				Stewards.hire(viewer, lineage);
				fill(board, viewer, lineage, offers);
			}
			// Nothing on the board can be picked up, so always resend the real contents.
			sendAllDataToRemote();
		}

		@Override
		public ItemStack quickMoveStack(Player player, int slotIndex) {
			return ItemStack.EMPTY;
		}

		@Override
		public boolean stillValid(Player player) {
			return innkeeper.isAlive() && player.distanceTo(innkeeper) < MAX_TALK_DISTANCE;
		}
	}

	// --- Accepting and finishing jobs ---------------------------------------------------------

	private static void accept(ServerPlayer player, Lineage lineage, Offer offer) {
		if (taken(lineage, offer)) return;
		if (activeJobs(lineage) >= BloodlineConfig.get().inns.maxJobsPerPlayer) {
			player.sendSystemMessage(Text.t("inn.finish_first").withStyle(ChatFormatting.RED));
			return;
		}
		Quest quest = switch (offer.kind()) {
			case BOUNTY -> {
				GlobalPos place = GlobalPos.of(player.level().dimension(), offer.bountyPos());
				String key = "inn_" + offer.id().replace(':', '_') + "_" + player.getUUID().toString().substring(0, 8);
				Quest bounty = Quest.of(Type.INN, Text.t("inn.bounty", offer.bounty().name()), Text.t("inn.bounty.task", offer.bounty().name(),
						offer.bountyPos().getX() + ", " + offer.bountyPos().getZ()), Goal.KILL_CHAMPION,
					List.of(key, offer.bounty().id(), player.level().dimension().identifier().toString()), 1,
					Reward.item(3, "emerald", offer.emeralds())).at(place, 32);
				Questlines.giveMap(player, place, Text.t("inn.bounty", offer.bounty().name()));
				yield bounty;
			}
			case DELIVERY -> Quest.of(Type.INN, Text.t("inn.delivery", offer.delivery().count(), itemName(offer.delivery().item())),
				Text.t("inn.delivery.task", offer.delivery().count(), itemName(offer.delivery().item())), Goal.MANUAL,
				List.of(BuiltInRegistries.ITEM.getKey(offer.delivery().item()).toString()), offer.delivery().count(),
				Reward.item(2, "emerald", offer.emeralds()));
			case RUMOUR -> rumour(player, offer);
			case COURIER -> courier(player, lineage, offer);
			case SATCHEL -> {
				GlobalPos place = GlobalPos.of(player.level().dimension(), offer.bountyPos());
				String where = offer.bountyPos().getX() + ", " + offer.bountyPos().getZ();
				Questlines.giveMap(player, place, Text.t("inn.satchel"));
				yield Quest.of(Type.INN, Text.t("inn.satchel"), Text.t("inn.satchel.task", where), Goal.MANUAL,
					List.of(SATCHEL, offer.id()), 1, Reward.item(2, "emerald", offer.emeralds())).at(place, 24);
			}
			case HUNT -> Quest.of(Type.INN, Text.t("inn.hunt", offer.hunt().count(), offer.hunt().name()),
				Text.t("inn.hunt.task", offer.hunt().count(), offer.hunt().name()), Goal.KILL, List.of(offer.hunt().entity()), offer.hunt().count(),
				Reward.item(2, "emerald", offer.emeralds()));
			case EXPEDITION -> expedition(player, offer);
			case CRYPT -> crypt(player, offer);
		};
		if (quest == null) return;
		quest.line = Optional.of(LINE_PREFIX + offer.id());
		Quests.add(player, lineage, quest);
		Lineages.markDirty(player.level().getServer());
		player.sendSystemMessage(Text.t("inn.accepted_chat").withStyle(ChatFormatting.GRAY).append(" ").append(Quests.line(quest, true)));
		player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
	}

	private static Quest rumour(ServerPlayer player, Offer offer) {
		Rumour rumour = offer.rumour();
		if (count(player, Items.EMERALD) < rumour.cost()) {
			player.sendSystemMessage(Text.t("inn.rumour.too_poor", rumour.cost()).withStyle(ChatFormatting.RED));
			return null;
		}
		Optional<GlobalPos> place = Questlines.locate(player, Level.OVERWORLD, rumour.structure(), true);
		if (place.isEmpty()) {
			player.sendSystemMessage(Text.t("inn.rumour.none", rumour.name())
				.withStyle(ChatFormatting.GRAY));
			return null;
		}
		take(player, Items.EMERALD, rumour.cost());
		Questlines.giveMap(player, place.get(), Text.t("inn.rumour", rumour.name()));
		return Quest.of(Type.INN, Text.t("inn.rumour", rumour.name()), Text.t("inn.rumour.task", rumour.name(),
			place.get().pos().getX() + ", " + place.get().pos().getZ()), Goal.VISIT, List.of(), 1, Reward.levels(4)).at(place.get(), 32);
	}

	// --- Courier, satchel and expedition jobs --------------------------------------------------

	/** A letter for the innkeeper of another village, found in a random direction from here. */
	private static Quest courier(ServerPlayer player, Lineage lineage, Offer offer) {
		ServerLevel overworld = player.level().getServer().overworld();
		BlockPos here = player.blockPosition();
		RandomSource random = player.getRandom();
		BlockPos target = null;
		for (int attempt = 0; attempt < 3 && target == null; attempt++) {
			BlockPos found = StructureSearch.find(overworld, StructureTags.VILLAGE, awayFrom(here, random, 400, 700), 40).orElse(null);
			if (found != null && found.distSqr(here) > 200 * 200) target = found;
		}
		if (target == null || player.level().dimension() != Level.OVERWORLD) {
			player.sendSystemMessage(Text.t("inn.courier.none").withStyle(ChatFormatting.GRAY));
			return null;
		}
		int distance = (int) Math.sqrt(target.distSqr(here));
		int emeralds = 4 + distance / 100;
		String where = target.getX() + ", " + target.getZ();
		ItemStack letter = new ItemStack(Items.PAPER);
		letter.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.courier.letter"), ChatFormatting.GOLD));
		letter.set(DataComponents.LORE, new ItemLore(List.of(line(Text.t("inn.courier.letter_lore", where), ChatFormatting.GRAY))));
		CompoundTag tag = new CompoundTag();
		tag.putString(ITEM_TAG, offer.id());
		letter.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		if (!player.getInventory().add(letter)) player.spawnAtLocation(player.level(), letter);
		Questlines.giveMap(player, GlobalPos.of(Level.OVERWORLD, target), Text.t("inn.courier"));
		return Quest.of(Type.INN, Text.t("inn.courier"), Text.t("inn.courier.task", where), Goal.MANUAL,
			List.of(LETTER, offer.id(), String.valueOf(target.asLong())), 1, Reward.item(2, "emerald", emeralds));
	}

	/** The nearest patch of the biome, or nothing if there's none within reach. */
	private static Quest expedition(ServerPlayer player, Offer offer) {
		Expedition expedition = offer.expedition();
		ServerLevel overworld = player.level().getServer().overworld();
		if (player.level().dimension() != Level.OVERWORLD) return null;
		var found = overworld.findClosestBiome3d(b -> b.unwrapKey().filter(k -> k.identifier().toString().equals(expedition.biome())).isPresent(),
			player.blockPosition(), 3000, 32, 64);
		if (found == null) {
			player.sendSystemMessage(Text.t("inn.expedition.none", expedition.name()).withStyle(ChatFormatting.GRAY));
			return null;
		}
		BlockPos pos = found.getFirst();
		Questlines.giveMap(player, GlobalPos.of(Level.OVERWORLD, pos), Text.t("inn.expedition", expedition.name()));
		return Quest.of(Type.INN, Text.t("inn.expedition", expedition.name()), Text.t("inn.expedition.task", expedition.name(),
			pos.getX() + ", " + pos.getZ()), Goal.BIOME, List.of(expedition.biome()), 1, Reward.item(2, "emerald", offer.emeralds()));
	}

	/** The nearest barrow crypt nobody has been sent to yet. */
	private static Quest crypt(ServerPlayer player, Offer offer) {
		Optional<GlobalPos> place = Questlines.locate(player, Level.OVERWORLD, Crypts.CRYPT, true);
		if (place.isEmpty() || player.level().dimension() != Level.OVERWORLD) {
			player.sendSystemMessage(Text.t("inn.crypt.none").withStyle(ChatFormatting.GRAY));
			return null;
		}
		String where = place.get().pos().getX() + ", " + place.get().pos().getZ();
		Questlines.giveMap(player, place.get(), Text.t("inn.crypt"));
		return Quest.of(Type.INN, Text.t("inn.crypt"), Text.t("inn.crypt.task", where), Goal.MANUAL, List.of(Crypts.JOB), 1,
			Reward.item(3, "emerald", offer.emeralds())).at(place.get(), 64);
	}

	/** Runs with the quest checks: places the lost satchel's chest once the player comes near it. */
	static void tickJob(ServerPlayer player, Quest quest) {
		if (quest.type != Type.INN || quest.targets.isEmpty() || !SATCHEL.equals(quest.targets.getFirst())) return;
		if (quest.seen.containsKey("placed") || !Quests.isAt(player, quest)) return;
		ServerLevel level = player.level();
		BlockPos spot = quest.place.get().pos();
		int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX(), spot.getZ());
		BlockPos chestPos = new BlockPos(spot.getX(), y, spot.getZ());
		level.setBlockAndUpdate(chestPos, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
		if (level.getBlockEntity(chestPos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest) {
			ItemStack satchel = new ItemStack(Items.BUNDLE);
			satchel.set(DataComponents.CUSTOM_NAME, name(Text.t("inn.satchel.item"), ChatFormatting.GOLD));
			satchel.set(DataComponents.LORE, new ItemLore(List.of(line(Text.t("inn.satchel.item_lore"), ChatFormatting.GRAY))));
			CompoundTag tag = new CompoundTag();
			tag.putString(ITEM_TAG, quest.targets.get(1));
			satchel.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
			chest.setItem(13, satchel);
		}
		quest.seen.put("placed", 1);
		Lineages.markDirty(level.getServer());
		player.sendSystemMessage(Text.t("inn.satchel.spotted", chestPos.getX() + ", " + chestPos.getY() + ", " + chestPos.getZ())
			.withStyle(ChatFormatting.GOLD));
	}

	/** The job item (a letter or satchel) for this quest in the player's inventory, or an empty stack. */
	private static ItemStack jobItem(ServerPlayer player, String id) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			CustomData data = stack.get(DataComponents.CUSTOM_DATA);
			if (data != null && id.equals(data.copyTag().getStringOr(ITEM_TAG, ""))) return stack;
		}
		return ItemStack.EMPTY;
	}

	/** Letters are handed in at the target village's inn; satchels at any inn. */
	private static void handInJobItems(ServerPlayer player, Lineage lineage, BlockPos inn) {
		for (Quest quest : lineage.allQuests()) {
			if (quest.type != Type.INN || quest.goal != Goal.MANUAL || quest.isDone() || quest.targets.size() < 2) continue;
			String kind = quest.targets.getFirst();
			if (!LETTER.equals(kind) && !SATCHEL.equals(kind)) continue;
			ItemStack item = jobItem(player, quest.targets.get(1));
			if (item.isEmpty()) continue;
			if (LETTER.equals(kind)) {
				BlockPos target = BlockPos.of(Long.parseLong(quest.targets.get(2)));
				double dx = target.getX() - inn.getX(), dz = target.getZ() - inn.getZ();
				if (dx * dx + dz * dz > 160 * 160) {
					player.sendSystemMessage(Text.t("inn.courier.wrong_inn").withStyle(ChatFormatting.GRAY));
					continue;
				}
			}
			item.shrink(1);
			quest.progress = quest.required;
			Lineages.markDirty(player.level().getServer());
			player.sendSystemMessage(Text.t(LETTER.equals(kind) ? "inn.courier.delivered" : "inn.satchel.returned").withStyle(ChatFormatting.GRAY));
		}
	}

	private static void handInDeliveries(ServerPlayer player, Lineage lineage) {
		for (Quest quest : lineage.allQuests()) {
			if (quest.type != Type.INN || quest.goal != Goal.MANUAL || quest.isDone()) continue;
			String first = quest.targets.getFirst();
			if (LETTER.equals(first) || SATCHEL.equals(first) || Crypts.JOB.equals(first)) continue;
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(quest.targets.getFirst())).orElse(null);
			if (item == null || count(player, item) < quest.required) continue;
			take(player, item, quest.required);
			quest.progress = quest.required;
			Lineages.markDirty(player.level().getServer());
			player.sendSystemMessage(Text.t("inn.delivered").withStyle(ChatFormatting.GRAY));
		}
	}

	static int count(ServerPlayer player, Item item) {
		int total = 0;
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item) && !stack.has(DataComponents.CUSTOM_DATA)) total += stack.getCount();
		}
		return total;
	}

	static void take(ServerPlayer player, Item item, int amount) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize() && amount > 0; i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item) && !stack.has(DataComponents.CUSTOM_DATA)) {
				int used = Math.min(amount, stack.getCount());
				stack.shrink(used);
				amount -= used;
			}
		}
	}

	// --- Text helpers -------------------------------------------------------------------------

	private static Component name(Component text, ChatFormatting color) {
		return Component.empty().append(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	private static Component line(Component text, ChatFormatting color) {
		return Component.empty().append(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	private static Component itemName(Item item) {
		return item.getName(new ItemStack(item));
	}

	private static int roughDistance(ServerPlayer player, BlockPos target) {
		double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
		return (int) Math.round(Math.sqrt(dx * dx + dz * dz) / 10) * 10;
	}

	private static Component direction(BlockPos from, BlockPos to) {
		double angle = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
		String[] names = {"east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east"};
		int index = (int) Math.round(((angle + 360) % 360) / 45) % 8;
		return Text.t("direction." + names[index]);
	}
}
