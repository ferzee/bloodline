package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Reward;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
	private static final int MAX_JOBS = 2;
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

	private record Rumour(ResourceKey<Structure> structure, String name, int cost) {
	}

	private static final List<Rumour> RUMOURS = List.of(
		new Rumour(BuiltinStructures.WOODLAND_MANSION, "a woodland mansion", 8),
		new Rumour(BuiltinStructures.JUNGLE_TEMPLE, "a jungle temple", 4),
		new Rumour(BuiltinStructures.DESERT_PYRAMID, "a desert pyramid", 4),
		new Rumour(BuiltinStructures.IGLOO, "an igloo", 3),
		new Rumour(BuiltinStructures.SHIPWRECK, "a shipwreck", 3),
		new Rumour(BuiltinStructures.TRAIL_RUINS, "buried trail ruins", 5),
		new Rumour(BuiltinStructures.TRIAL_CHAMBERS, "the trial chambers", 6),
		new Rumour(BuiltinStructures.OCEAN_MONUMENT, "an ocean monument", 6)
	);

	private enum Kind { BOUNTY, DELIVERY, RUMOUR }

	/** One job on a board. Exactly one of bounty, delivery or rumour is set, depending on kind. */
	private record Offer(String id, Kind kind, Champions.Kind bounty, BlockPos bountyPos, Delivery delivery, Rumour rumour, int emeralds) {
	}

	/** When an innkeeper was last spawned at each bell, so a missing one isn't replaced every second. */
	private static final Map<Long, Long> LAST_SPAWN = new HashMap<>();

	private Inns() {
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
		BlockPos pos = Champions.findSpot(level, bell);
		innkeeper.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		innkeeper.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
		innkeeper.setNoAi(true);
		innkeeper.setPermanentlyInvulnerable(true);
		innkeeper.setPersistenceRequired();
		innkeeper.setCustomName(Component.literal("Innkeeper " + Names.given(level.getRandom(), "")).withStyle(ChatFormatting.GOLD));
		innkeeper.setCustomNameVisible(true);
		innkeeper.addTag(TAG);
		innkeeper.addTag(INN_TAG + bell.asLong());
		level.addFreshEntity(innkeeper);
		player.sendSystemMessage(Component.literal("You've found ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(innName(bell)).withStyle(ChatFormatting.GOLD))
			.append(Component.literal(". The innkeeper by the bell has work for travellers.").withStyle(ChatFormatting.GRAY)));
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
		int day = Lineages.today(player.level().getServer());
		List<Offer> offers = offers(inn, day);
		SimpleContainer board = new SimpleContainer(9);
		fill(board, player, lineage, offers);
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new BoardMenu(id, inventory, board, player, innkeeper, offers),
			Component.literal(innName(inn) + " - Quest Board")));
	}

	// --- The quest board ----------------------------------------------------------------------

	private static List<Offer> offers(BlockPos inn, int day) {
		RandomSource random = RandomSource.create(inn.asLong() * 31 + day);
		Kind[] kinds = {Kind.BOUNTY, Kind.DELIVERY, Kind.DELIVERY, Kind.RUMOUR, random.nextBoolean() ? Kind.BOUNTY : Kind.RUMOUR};
		List<Offer> offers = new ArrayList<>();
		for (int slot = 0; slot < OFFERS; slot++) {
			String id = inn.asLong() + ":" + day + ":" + slot;
			switch (kinds[slot]) {
				case BOUNTY -> {
					Champions.Kind beast = Champions.BOUNTIES.get(random.nextInt(Champions.BOUNTIES.size()));
					double angle = random.nextDouble() * Math.PI * 2;
					int distance = 120 + random.nextInt(180);
					BlockPos where = inn.offset((int) (Math.cos(angle) * distance), 0, (int) (Math.sin(angle) * distance));
					offers.add(new Offer(id, Kind.BOUNTY, beast, where, null, null, 6 + random.nextInt(6)));
				}
				case DELIVERY -> {
					Delivery delivery = DELIVERIES.get(random.nextInt(DELIVERIES.size()));
					offers.add(new Offer(id, Kind.DELIVERY, null, null, delivery, null, delivery.emeralds()));
				}
				case RUMOUR -> {
					Rumour rumour = RUMOURS.get(random.nextInt(RUMOURS.size()));
					offers.add(new Offer(id, Kind.RUMOUR, null, null, null, rumour, rumour.cost()));
				}
			}
		}
		return offers;
	}

	private static boolean taken(Lineage lineage, Offer offer) {
		return lineage.quests.stream().anyMatch(q -> q.line.filter((LINE_PREFIX + offer.id())::equals).isPresent());
	}

	private static long activeJobs(Lineage lineage) {
		return lineage.quests.stream().filter(q -> q.type == Type.INN).count();
	}

	private static void fill(SimpleContainer board, ServerPlayer player, Lineage lineage, List<Offer> offers) {
		boolean full = activeJobs(lineage) >= MAX_JOBS;
		for (int slot = 0; slot < offers.size(); slot++) {
			Offer offer = offers.get(slot);
			boolean taken = taken(lineage, offer);
			ItemStack icon;
			List<Component> lore = new ArrayList<>();
			switch (offer.kind()) {
				case BOUNTY -> {
					icon = new ItemStack(Items.IRON_SWORD);
					icon.set(DataComponents.CUSTOM_NAME, name("Bounty: " + offer.bounty().name(), ChatFormatting.RED));
					lore.add(line("A dangerous beast was seen about " + roughDistance(player, offer.bountyPos())
						+ " blocks " + direction(player.blockPosition(), offer.bountyPos()) + ".", ChatFormatting.GRAY));
					lore.add(line("Reward: " + offer.emeralds() + " emeralds, 3 levels, 3 Renown", ChatFormatting.GREEN));
				}
				case DELIVERY -> {
					icon = new ItemStack(offer.delivery().item(), Math.min(offer.delivery().count(), offer.delivery().item().getDefaultMaxStackSize()));
					icon.set(DataComponents.CUSTOM_NAME, name("Delivery: " + offer.delivery().count() + " " + itemName(offer.delivery().item()), ChatFormatting.YELLOW));
					lore.add(line("Bring them to any innkeeper.", ChatFormatting.GRAY));
					lore.add(line("Reward: " + offer.emeralds() + " emeralds, 2 levels, 3 Renown", ChatFormatting.GREEN));
				}
				default -> {
					icon = new ItemStack(Items.MAP);
					icon.set(DataComponents.CUSTOM_NAME, name("Rumour: " + offer.rumour().name(), ChatFormatting.AQUA));
					lore.add(line("A traveller swears they saw " + offer.rumour().name() + ".", ChatFormatting.GRAY));
					lore.add(line("Costs " + offer.rumour().cost() + " emeralds for the map.", ChatFormatting.GOLD));
					lore.add(line("Reward for finding it: 4 levels, 3 Renown", ChatFormatting.GREEN));
				}
			}
			if (taken) {
				lore.add(line("✔ Accepted", ChatFormatting.DARK_GREEN));
				icon.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
			} else if (full) {
				lore.add(line("You already have " + MAX_JOBS + " inn jobs.", ChatFormatting.DARK_RED));
			} else {
				lore.add(line("Click to accept", ChatFormatting.WHITE));
			}
			icon.set(DataComponents.LORE, new ItemLore(lore));
			board.setItem(slot, icon);
		}
		ItemStack info = new ItemStack(Items.PAPER);
		info.set(DataComponents.CUSTOM_NAME, name("House " + lineage.house, ChatFormatting.GOLD));
		info.set(DataComponents.LORE, new ItemLore(List.of(
			line("Inn jobs: " + activeJobs(lineage) + " / " + MAX_JOBS, ChatFormatting.GRAY),
			line("Renown: " + lineage.renown, ChatFormatting.YELLOW),
			line("New jobs are posted every morning.", ChatFormatting.DARK_GRAY))));
		board.setItem(8, info);
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
			if (slotIndex >= 0 && slotIndex < offers.size()) {
				Lineage lineage = Lineages.get(viewer);
				if (lineage != null) {
					accept(viewer, lineage, offers.get(slotIndex));
					fill(board, viewer, lineage, offers);
				}
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
		if (activeJobs(lineage) >= MAX_JOBS) {
			player.sendSystemMessage(Component.literal("Finish one of your inn jobs before taking another.").withStyle(ChatFormatting.RED));
			return;
		}
		Quest quest = switch (offer.kind()) {
			case BOUNTY -> {
				GlobalPos place = GlobalPos.of(player.level().dimension(), offer.bountyPos());
				String key = "inn_" + offer.id().replace(':', '_') + "_" + player.getUUID().toString().substring(0, 8);
				Quest bounty = Quest.of(Type.INN, "Bounty: " + offer.bounty().name(), "Slay " + offer.bounty().name() + " near "
						+ offer.bountyPos().getX() + ", " + offer.bountyPos().getZ(), Goal.KILL_CHAMPION,
					List.of(key, offer.bounty().id(), player.level().dimension().identifier().toString()), 1,
					Reward.item(3, "emerald", offer.emeralds())).at(place, 32);
				Questlines.giveMap(player, place, "Bounty: " + offer.bounty().name());
				yield bounty;
			}
			case DELIVERY -> Quest.of(Type.INN, "Delivery: " + offer.delivery().count() + " " + itemName(offer.delivery().item()),
				"Bring " + offer.delivery().count() + " " + itemName(offer.delivery().item()) + " to an innkeeper", Goal.MANUAL,
				List.of(BuiltInRegistries.ITEM.getKey(offer.delivery().item()).toString()), offer.delivery().count(),
				Reward.item(2, "emerald", offer.emeralds()));
			case RUMOUR -> rumour(player, offer);
		};
		if (quest == null) return;
		quest.line = Optional.of(LINE_PREFIX + offer.id());
		Quests.add(player, lineage, quest);
		Lineages.markDirty(player.level().getServer());
		player.sendSystemMessage(Component.literal("Job accepted: ").withStyle(ChatFormatting.GRAY).append(Quests.line(quest, true)));
		player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1f, 1f);
	}

	private static Quest rumour(ServerPlayer player, Offer offer) {
		Rumour rumour = offer.rumour();
		if (count(player, Items.EMERALD) < rumour.cost()) {
			player.sendSystemMessage(Component.literal("The map costs " + rumour.cost() + " emeralds.").withStyle(ChatFormatting.RED));
			return null;
		}
		Optional<GlobalPos> place = Questlines.locate(player, Level.OVERWORLD, rumour.structure(), true);
		if (place.isEmpty()) {
			player.sendSystemMessage(Component.literal("The innkeeper shrugs. Nobody has heard of " + rumour.name() + " anywhere near here.")
				.withStyle(ChatFormatting.GRAY));
			return null;
		}
		take(player, Items.EMERALD, rumour.cost());
		Questlines.giveMap(player, place.get(), "Rumour: " + rumour.name());
		return Quest.of(Type.INN, "Rumour: " + rumour.name(), "Find " + rumour.name() + " near " + place.get().pos().getX() + ", "
			+ place.get().pos().getZ(), Goal.VISIT, List.of(), 1, Reward.levels(4)).at(place.get(), 32);
	}

	private static void handInDeliveries(ServerPlayer player, Lineage lineage) {
		for (Quest quest : lineage.quests) {
			if (quest.type != Type.INN || quest.goal != Goal.MANUAL || quest.isDone()) continue;
			Item item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(quest.targets.getFirst())).orElse(null);
			if (item == null || count(player, item) < quest.required) continue;
			take(player, item, quest.required);
			quest.progress = quest.required;
			Lineages.markDirty(player.level().getServer());
			player.sendSystemMessage(Component.literal("You hand over the goods. \"Much obliged.\"").withStyle(ChatFormatting.GRAY));
		}
	}

	private static int count(ServerPlayer player, Item item) {
		int total = 0;
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item) && !stack.has(DataComponents.CUSTOM_DATA)) total += stack.getCount();
		}
		return total;
	}

	private static void take(ServerPlayer player, Item item, int amount) {
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

	private static Component name(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	private static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	private static String itemName(Item item) {
		return item.getName(new ItemStack(item)).getString();
	}

	private static int roughDistance(ServerPlayer player, BlockPos target) {
		double dx = target.getX() - player.getX(), dz = target.getZ() - player.getZ();
		return (int) Math.round(Math.sqrt(dx * dx + dz * dz) / 10) * 10;
	}

	private static String direction(BlockPos from, BlockPos to) {
		double angle = Math.toDegrees(Math.atan2(to.getZ() - from.getZ(), to.getX() - from.getX()));
		String[] names = {"east", "south-east", "south", "south-west", "west", "north-west", "north", "north-east"};
		int index = (int) Math.round(((angle + 360) % 360) / 45) % 8;
		return names[index];
	}
}
