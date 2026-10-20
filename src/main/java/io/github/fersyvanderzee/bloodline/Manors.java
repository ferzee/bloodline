package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.House;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.ManorState;
import io.github.fersyvanderzee.bloodline.data.Seat;
import io.github.fersyvanderzee.bloodline.data.Steward;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Fallen Manor: the overgrown country house of a forgotten family. When a player first steps onto the
 * grounds, the people who moved in confront them, led by the Usurper. Once they are driven out, the family's last
 * will lies torn in four pages (library, study, cellar, garden). Bringing all four to the lectern in the great hall
 * lets the house claim the manor as its family seat: the steward moves in, and every family member who dies is
 * given a gravestone in the manor's garden.
 *
 * <p>The forgotten family's name is worked out from where the manor stands, so only its progress is saved
 * ({@link ManorState}); the claiming house keeps its seat ({@link Seat}).
 */
public final class Manors {
	public static final ResourceKey<Structure> MANOR = ResourceKey.create(Registries.STRUCTURE, Bloodline.id("fallen_manor"));
	private static final Identifier TEMPLATE = Bloodline.id("manor");
	/** Champion keys for usurpers are this prefix plus the manor's key. */
	public static final String KEY_PREFIX = "manor_";
	private static final String PAGE_TAG = "bloodline_will";
	private static final String PAGE_INDEX = "bloodline_will_page";

	// Spots in the manor's template: SPOTS in tools/generate_manor.py.
	static final BlockPos LECTERN = new BlockPos(16, 6, 11);
	static final BlockPos STEWARD = new BlockPos(14, 6, 16);
	static final BlockPos USURPER = new BlockPos(7, 6, 26);
	static final BlockPos GATE_SIGN = new BlockPos(16, 9, 34);
	/** Where the will's four pages are hidden, and what each place is called. */
	static final BlockPos[] PAGES = {new BlockPos(8, 6, 10), new BlockPos(24, 6, 10), new BlockPos(24, 1, 19), new BlockPos(30, 6, 6)};
	private static final String[] PLACES = {"library", "study", "cellar", "garden"};
	/** Headstones in the garden, filled in order: two rows of thirteen, each with its sign in front (z + 1). */
	static final List<BlockPos> GRAVES;

	static {
		List<BlockPos> graves = new ArrayList<>();
		for (int z : new int[]{2, 5}) for (int x = 3; x <= 27; x += 2) graves.add(new BlockPos(x, 6, z));
		GRAVES = List.copyOf(graves);
	}

	private static final long REMIND_TICKS = 20 * 60 * 5;
	private static final double USURPER_RANGE = 80;
	/** When each player last heard each manor's greeting. Not saved. */
	private static final Map<String, Long> LAST_TOLD = new HashMap<>();

	private Manors() {
	}

	// --- Finding a manor -----------------------------------------------------------------------------------

	/** A manor in the world: its spots are at fixed places in its one piece. */
	record Site(StructureStart start, PoolElementStructurePiece piece, String key) {
		BlockPos world(BlockPos local) {
			return StructureTemplate.transform(local, Mirror.NONE, piece.getRotation(), BlockPos.ZERO).offset(piece.getPosition());
		}

		/** The way the gate faces in the world (the template's south). */
		Direction front() {
			return piece.getRotation().rotate(Direction.SOUTH);
		}

		BlockPos centre() {
			return piece.getBoundingBox().getCenter();
		}
	}

	static Optional<Site> siteAt(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) return Optional.empty();
		StructureStart start = server.structureManager().getStructureWithPieceAt(pos, holder -> holder.is(MANOR));
		if (!start.isValid() || start.getPieces().isEmpty()) return Optional.empty();
		StructurePiece first = start.getPieces().getFirst();
		if (!(first instanceof PoolElementStructurePiece piece) || !(piece.getElement() instanceof SinglePoolElement element)
			|| !TEMPLATE.equals(element.getTemplateLocation())) {
			return Optional.empty();
		}
		return Optional.of(new Site(start, piece, Long.toString(start.getChunkPos().pack())));
	}

	/** The forgotten family who built the manor: "Ravensworth". */
	static String family(String key) {
		return Names.house(RandomSource.create(Long.parseLong(key) * 6007L + 31L));
	}

	/** "Ravensworth Manor". */
	public static MutableComponent name(String key) {
		return Text.t("manor.name", family(key));
	}

	/** The house that holds this manor as its seat, if any. */
	private static Optional<House> claimant(MinecraftServer server, ManorState.Manor manor) {
		if (manor.claimedBy.isEmpty()) return Optional.empty();
		return Optional.ofNullable(BloodlineState.get(server).house(UUID.fromString(manor.claimedBy)));
	}

	// --- Each second, for players on the grounds --------------------------------------------------------------

	public static void tick(ServerPlayer player) {
		ServerLevel level = player.level();
		Optional<Site> found = siteAt(level, player.blockPosition());
		if (found.isEmpty()) return;
		Site site = found.get();
		MinecraftServer server = level.getServer();
		ManorState state = ManorState.get(server);
		ManorState.Manor manor = state.get(site.key());
		Optional<House> owner = claimant(server, manor);
		if (owner.isEmpty() && !manor.claimedBy.isEmpty()) {
			// The house that held it is gone.
			manor.claimedBy = "";
			state.setDirty();
		}
		Lineage lineage = Lineages.get(player);

		if (owner.isPresent()) {
			boolean ours = lineage != null && lineage.house == owner.get();
			if (once(player, site.key() + ":greet")) {
				player.sendSystemMessage((ours ? Text.t("manor.home", name(site.key())) : Text.t("manor.seat_of", name(site.key()), owner.get().name))
					.withStyle(ChatFormatting.GRAY));
			}
			if (ours) bury(level, site, owner.get());
			return;
		}
		if (once(player, site.key() + ":greet")) {
			writeGateSign(level, site, Component.literal(family(site.key())), Text.t("manor.sign.manor"));
			player.sendSystemMessage(Text.t(manor.cleared ? "manor.found_free" : "manor.found", name(site.key()), family(site.key()))
				.withStyle(ChatFormatting.GRAY));
		}
		if (!manor.cleared) {
			confront(player, level, site, state, manor);
			return;
		}
		if (!manor.pagesHidden && hidePages(level, site)) {
			manor.pagesHidden = true;
			state.setDirty();
		}
	}

	/** True at most once every few minutes per player and topic. */
	private static boolean once(ServerPlayer player, String topic) {
		long now = player.level().getGameTime();
		String key = player.getUUID() + ":" + topic;
		Long last = LAST_TOLD.get(key);
		if (last != null && now - last < REMIND_TICKS) return false;
		LAST_TOLD.put(key, now);
		return true;
	}

	// --- The occupants --------------------------------------------------------------------------------------

	/** The Usurper and their band come out to meet whoever sets foot on the grounds, and come back if they were lost. */
	private static void confront(ServerPlayer player, ServerLevel level, Site site, ManorState state, ManorState.Manor manor) {
		String key = KEY_PREFIX + site.key();
		boolean present = !level.getEntitiesOfClass(Mob.class, new AABB(site.centre()).inflate(USURPER_RANGE),
			m -> m.entityTags().contains("bloodline_key=" + key)).isEmpty();
		if (present) return;
		if (!Champions.spawnAt(player, Champions.USURPER, key, site.world(USURPER), Text.t("manor.usurper", name(site.key())))) return;
		if (!manor.occupied) {
			manor.occupied = true;
			state.setDirty();
			player.sendSystemMessage(Text.t("manor.occupied", name(site.key())).withStyle(ChatFormatting.RED));
		}
	}

	/** The Usurper is dead: the manor is free, and its will can be found. */
	public static void onUsurperSlain(MinecraftServer server, LivingEntity usurper, String key) {
		ManorState state = ManorState.get(server);
		ManorState.Manor manor = state.get(key);
		if (manor.cleared) return;
		manor.cleared = true;
		state.setDirty();
		ServerLevel level = (ServerLevel) usurper.level();
		for (ServerPlayer player : level.players()) {
			if (!player.blockPosition().closerThan(usurper.blockPosition(), 64)) continue;
			title(player, Text.t("manor.freed.title"), Text.t("manor.freed.subtitle", name(key)));
			player.sendSystemMessage(Text.t("manor.freed", family(key)).withStyle(ChatFormatting.GOLD));
			player.sendSystemMessage(placesHint(family(key)).withStyle(ChatFormatting.YELLOW));
		}
		level.playSound(null, usurper.blockPosition(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1f, 0.8f);
		siteAt(level, usurper.blockPosition()).ifPresent(site -> {
			if (!manor.pagesHidden && hidePages(level, site)) {
				manor.pagesHidden = true;
				state.setDirty();
			}
		});
	}

	private static MutableComponent placesHint(String family) {
		return Text.t("manor.pages_hint", family);
	}

	// --- The last will -------------------------------------------------------------------------------------

	/** Puts one page of the will in each hiding place. Returns false, to try again later, if any of them isn't loaded. */
	private static boolean hidePages(ServerLevel level, Site site) {
		List<Container> containers = new ArrayList<>();
		for (BlockPos spot : PAGES) {
			BlockPos pos = site.world(spot);
			if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof Container container)) return false;
			containers.add(container);
		}
		RandomSource random = level.getRandom();
		for (int i = 0; i < containers.size(); i++) {
			Container container = containers.get(i);
			int slot = random.nextInt(container.getContainerSize());
			for (int attempt = 0; attempt < 30 && !container.getItem(slot).isEmpty(); attempt++) slot = random.nextInt(container.getContainerSize());
			container.setItem(slot, page(site.key(), i));
		}
		return true;
	}

	static ItemStack page(String key, int index) {
		ItemStack page = new ItemStack(Items.PAPER);
		page.set(DataComponents.CUSTOM_NAME, Component.empty().append(Text.t("manor.page.name", family(key), index + 1))
			.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
		page.set(DataComponents.LORE, new ItemLore(List.of(
			Component.empty().append(Text.t("manor.will." + index, family(key))).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.GRAY)),
			Component.empty().append(Text.t("manor.page.lore", name(key))).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY)))));
		page.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putString(PAGE_TAG, key);
		tag.putInt(PAGE_INDEX, index);
		page.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return page;
	}

	/** Which of this manor's pages the player carries (0 to 3). */
	private static Set<Integer> pagesHeld(ServerPlayer player, String key) {
		Set<Integer> held = new HashSet<>();
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			CustomData data = inventory.getItem(i).get(DataComponents.CUSTOM_DATA);
			if (data == null) continue;
			CompoundTag tag = data.copyTag();
			if (key.equals(tag.getStringOr(PAGE_TAG, ""))) held.add(tag.getIntOr(PAGE_INDEX, -1));
		}
		held.remove(-1);
		return held;
	}

	private static void takePages(ServerPlayer player, String key) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			CustomData data = inventory.getItem(i).get(DataComponents.CUSTOM_DATA);
			if (data != null && key.equals(data.copyTag().getStringOr(PAGE_TAG, ""))) inventory.setItem(i, ItemStack.EMPTY);
		}
	}

	/** Right-clicking the lectern in the great hall: reads the will, and offers to claim the manor once it is whole. */
	public static InteractionResult onUseBlock(ServerPlayer player, Level level, BlockPos pos) {
		if (!level.getBlockState(pos).is(Blocks.LECTERN)) return InteractionResult.PASS;
		Optional<Site> found = siteAt(level, pos);
		if (found.isEmpty() || !found.get().world(LECTERN).equals(pos)) return InteractionResult.PASS;
		readWill(player, found.get());
		return InteractionResult.SUCCESS;
	}

	private static void readWill(ServerPlayer player, Site site) {
		MinecraftServer server = player.level().getServer();
		ManorState.Manor manor = ManorState.get(server).get(site.key());
		Lineage lineage = Lineages.get(player);
		Optional<House> owner = claimant(server, manor);
		if (owner.isPresent()) {
			boolean ours = lineage != null && lineage.house == owner.get();
			player.sendSystemMessage((ours ? Text.t("manor.home", name(site.key())) : Text.t("manor.seat_of", name(site.key()), owner.get().name))
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!manor.cleared) {
			player.sendSystemMessage(Text.t("manor.drive_out_first", name(site.key())).withStyle(ChatFormatting.GRAY));
			return;
		}
		Set<Integer> held = pagesHeld(player, site.key());
		if (held.size() < PAGES.length) {
			MutableComponent missing = Component.empty();
			for (int i = 0; i < PAGES.length; i++) {
				if (held.contains(i)) continue;
				if (!missing.getSiblings().isEmpty()) missing.append(", ");
				missing.append(Text.t("manor.place." + PLACES[i]));
			}
			player.sendSystemMessage(Text.t("manor.pages_have", held.size(), PAGES.length, missing).withStyle(ChatFormatting.GRAY));
			return;
		}
		if (lineage == null || lineage.awaitingHeir) return;
		String family = family(site.key());
		List<Component> body = new ArrayList<>();
		for (int i = 0; i < PAGES.length; i++) body.add(Text.t("manor.will." + i, family).withStyle(ChatFormatting.ITALIC));
		body.add(Text.t("manor.will.closing", family, lineage.house.name));
		lineage.house.seat.ifPresent(old -> body.add(Text.t("manor.replaces", name(old.manor)).withStyle(ChatFormatting.GRAY)));
		Screens.show(player, Screens.Window.of(new ItemStack(Items.WRITABLE_BOOK), Text.t("manor.will.title", family), body,
			List.of(new Screens.Button(Text.t("button.claim_manor", lineage.house.name), Text.t("manor.claim_hover", name(site.key())),
					"/bloodline manor claim"),
				new Screens.Button(Text.t("button.not_yet"), null, null))));
	}

	// --- Claiming the manor --------------------------------------------------------------------------------

	/** The player's house makes the manor they stand in its family seat. Returns false (with a message) if it can't. */
	public static boolean claim(ServerPlayer player) {
		ServerLevel level = player.level();
		MinecraftServer server = level.getServer();
		Lineage lineage = Lineages.get(player);
		Optional<Site> found = siteAt(level, player.blockPosition());
		if (lineage == null || lineage.awaitingHeir || found.isEmpty()) {
			player.sendSystemMessage(Text.t("manor.claim.not_here").withStyle(ChatFormatting.RED));
			return false;
		}
		Site site = found.get();
		ManorState state = ManorState.get(server);
		ManorState.Manor manor = state.get(site.key());
		if (!manor.cleared || claimant(server, manor).isPresent() || pagesHeld(player, site.key()).size() < PAGES.length) {
			readWill(player, site);
			return false;
		}
		takePages(player, site.key());
		House house = lineage.house;
		house.seat.ifPresent(old -> {
			if (!old.manor.equals(site.key())) state.get(old.manor).claimedBy = "";
		});
		manor.claimedBy = house.id.toString();
		state.setDirty();
		house.seat = Optional.of(new Seat(site.key(), GlobalPos.of(level.dimension(), site.world(LECTERN)), List.of()));

		// The steward moves in, or is taken on, at the great hall.
		GlobalPos hall = GlobalPos.of(level.dimension(), site.world(STEWARD));
		if (house.steward.isPresent()) {
			house.steward.get().home = hall;
		} else {
			house.steward = Optional.of(new Steward(hall, Names.given(player.getRandom(), ""), List.of()));
		}

		writeGateSign(level, site, Text.t("manor.sign.seat"), Text.t("manor.sign.house", house.name));
		Lineages.record(server, lineage, Text.t("chronicle.manor", lineage.current.name(), name(site.key()), house.name));
		server.getPlayerList().broadcastSystemMessage(Text.t("broadcast.manor", house.name, name(site.key())).withStyle(ChatFormatting.GOLD), false);
		for (ServerPlayer member : Lineages.onlineMembers(server, house)) {
			title(member, Text.t("manor.claimed.title"), Text.t("manor.claimed.subtitle", name(site.key()), house.name));
		}
		Lineages.tellHouse(server, house, Text.t("manor.claimed", name(site.key()), house.steward.get().name).withStyle(ChatFormatting.GRAY));
		level.playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 0.9f);
		Renown.change(player, lineage, BloodlineConfig.get().renown.manorClaimed, Text.t("renown.reason.manor", name(site.key())));
		Lineages.markDirty(server);
		bury(level, site, house);
		return true;
	}

	/** Whether the player stands at their house's family seat (for the Sacrifice). */
	public static boolean atSeat(ServerPlayer player, Lineage lineage) {
		if (lineage.house.seat.isEmpty()) return false;
		return siteAt(player.level(), player.blockPosition()).filter(site -> site.key().equals(lineage.house.seat.get().manor)).isPresent();
	}

	// --- The family graveyard ------------------------------------------------------------------------------

	/** Called after a family member dies: gives them a gravestone at the seat now if it is loaded, otherwise later. */
	public static void onAncestor(MinecraftServer server, House house) {
		if (house.seat.isEmpty()) return;
		Seat seat = house.seat.get();
		ServerLevel level = server.getLevel(seat.lectern.dimension());
		if (level == null || !level.isLoaded(seat.lectern.pos())) return;
		siteAt(level, seat.lectern.pos()).filter(site -> site.key().equals(seat.manor)).ifPresent(site -> bury(level, site, house));
	}

	/** Every ancestor of the house, oldest death first, with a key that stays the same: "player:generation:name". */
	private static List<Map.Entry<String, Ancestor>> ancestors(MinecraftServer server, House house) {
		BloodlineState state = BloodlineState.get(server);
		List<Map.Entry<String, Ancestor>> all = new ArrayList<>();
		for (UUID member : house.members) {
			Lineage branch = state.get(member);
			if (branch == null) continue;
			for (Ancestor ancestor : branch.ancestors) all.add(Map.entry(member + ":" + ancestor.generation() + ":" + ancestor.name(), ancestor));
		}
		all.sort(Comparator.comparingInt((Map.Entry<String, Ancestor> e) -> e.getValue().diedDay()).thenComparing(Map.Entry::getKey));
		return all;
	}

	/** Places a gravestone for every ancestor who doesn't have one yet, while there are plots left. */
	static void bury(ServerLevel level, Site site, House house) {
		if (house.seat.isEmpty() || !house.seat.get().manor.equals(site.key())) return;
		Seat seat = house.seat.get();
		boolean changed = false;
		for (Map.Entry<String, Ancestor> entry : ancestors(level.getServer(), house)) {
			if (seat.buried.contains(entry.getKey())) continue;
			if (seat.buried.size() >= GRAVES.size()) break;
			BlockPos plot = GRAVES.get(seat.buried.size());
			if (!level.isLoaded(site.world(plot))) return;
			gravestone(level, site, plot, entry.getValue());
			seat.buried.add(entry.getKey());
			changed = true;
		}
		if (changed) Lineages.markDirty(level.getServer());
	}

	private static void gravestone(ServerLevel level, Site site, BlockPos plot, Ancestor ancestor) {
		BlockPos stone = site.world(plot);
		BlockPos front = site.world(plot.south());
		level.setBlockAndUpdate(stone, Blocks.STONE_BRICKS.defaultBlockState());
		level.setBlockAndUpdate(stone.above(), Blocks.STONE_BRICK_SLAB.defaultBlockState());
		level.setBlockAndUpdate(front, Blocks.SPRUCE_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, site.front()));
		if (level.getBlockEntity(front) instanceof SignBlockEntity sign) {
			// Signs keep their words as written, so they are stored as plain text (readable without the mod).
			Component[] lines = {
				Component.literal(ancestor.name()),
				Component.literal(Epithets.display(ancestor.epithet()).getString()),
				Component.literal(Text.t("manor.grave.generation", Names.numeral(ancestor.generation())).getString()),
				Component.literal(Text.t("manor.grave.days", ancestor.bornDay(), ancestor.diedDay()).getString())};
			Component[] empty = {Component.empty(), Component.empty(), Component.empty(), Component.empty()};
			sign.setText(new SignText(List.of(lines), List.of(empty), DyeColor.BLACK, false), SignTextSlot.FRONT);
			sign.setWaxed(true);
		}
	}

	private static void writeGateSign(ServerLevel level, Site site, Component line2, Component line3) {
		BlockPos pos = site.world(GATE_SIGN);
		if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof SignBlockEntity sign)) return;
		Component[] lines = {Component.empty(), Component.literal(line2.getString()), Component.literal(line3.getString()), Component.empty()};
		Component[] empty = {Component.empty(), Component.empty(), Component.empty(), Component.empty()};
		sign.setText(new SignText(List.of(lines), List.of(empty), DyeColor.BLACK, false), SignTextSlot.FRONT);
		sign.setWaxed(true);
	}

	private static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(title.copy().withStyle(ChatFormatting.GOLD)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle.copy().withStyle(ChatFormatting.GRAY)));
	}

	// --- For admins and testers -------------------------------------------------------------------------------

	/** The state of the manor the player stands in, for /bloodline debug manor. */
	static Optional<Component> describe(ServerPlayer player) {
		return siteAt(player.level(), player.blockPosition()).map(site -> {
			ManorState.Manor manor = ManorState.get(player.level().getServer()).get(site.key());
			String owner = claimant(player.level().getServer(), manor).map(h -> h.name).orElse("-");
			return Text.t("command.manor", name(site.key()), String.valueOf(manor.occupied), String.valueOf(manor.cleared),
				String.valueOf(manor.pagesHidden), owner);
		});
	}

	/** Drives the occupants out at once (removing any usurper), for testing the will. */
	static boolean debugClear(ServerPlayer player) {
		Optional<Site> site = siteAt(player.level(), player.blockPosition());
		site.ifPresent(s -> {
			String key = KEY_PREFIX + s.key();
			List<Mob> usurpers = player.level().getEntitiesOfClass(Mob.class, new AABB(s.centre()).inflate(USURPER_RANGE),
				m -> m.entityTags().contains("bloodline_key=" + key));
			if (usurpers.isEmpty()) {
				Mob stand = net.minecraft.world.entity.EntityTypes.VINDICATOR.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.EVENT);
				if (stand != null) {
					BlockPos at = s.world(USURPER);
					stand.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
					onUsurperSlain(player.level().getServer(), stand, s.key());
				}
			} else {
				for (Mob usurper : usurpers) {
					onUsurperSlain(player.level().getServer(), usurper, s.key());
					usurper.discard();
				}
			}
		});
		return site.isPresent();
	}

	/** Forgets the manor's progress (not its claim), for testing it again. */
	static boolean reset(ServerPlayer player) {
		Optional<Site> site = siteAt(player.level(), player.blockPosition());
		site.ifPresent(s -> {
			ManorState state = ManorState.get(player.level().getServer());
			String claimedBy = state.get(s.key()).claimedBy;
			state.forget(s.key());
			state.get(s.key()).claimedBy = claimedBy;
		});
		return site.isPresent();
	}
}
