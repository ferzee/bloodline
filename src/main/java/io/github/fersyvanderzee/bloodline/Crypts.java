package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.CryptState;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Barrow crypts: the resting places of houses that died out. Each crypt's core holds four sarcophagi of the
 * founder's children, four cold braziers on emblem pedestals and the founder's sealed tomb. To open it, the
 * family lights the braziers in the order the children died (their epitaphs tell it) and brings the founder's
 * sigil, which lies in the catacombs' farthest chest. The founder then rises as the Barrow-Lord; whoever lays
 * them to rest takes up the fallen house's last heirloom.
 *
 * Everything about a crypt (the house's name, its people, the answer) is worked out from where it is, so only
 * its progress needs saving ({@link CryptState}).
 */
public final class Crypts {
	public static final ResourceKey<Structure> CRYPT = ResourceKey.create(Registries.STRUCTURE, Bloodline.id("crypt"));
	private static final Identifier CORE_TEMPLATE = Bloodline.id("crypt/entrance");
	/** Champion keys for founders are this prefix plus the crypt's key. */
	public static final String KEY_PREFIX = "crypt_";
	/** The first target of an inn job to lay a crypt's founder to rest. */
	public static final String JOB = "crypt";
	private static final String SIGIL_TAG = "bloodline_sigil";

	// Positions in the core piece's template: the CORE_* values in tools/generate_crypt.py, with FRONT (7) added to z.
	enum Emblem {
		CROWN(7, 2, 31), SWORD(13, 2, 31), HARVEST(7, 2, 34), BONE(13, 2, 34);

		final BlockPos brazier;

		Emblem(int x, int y, int z) {
			brazier = new BlockPos(x, y, z);
		}

		String id() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/** The west end of each child's sarcophagus; each is two blocks long in x and two high. */
	private static final int[][] SARCOPHAGI = {{2, 28}, {2, 36}, {17, 28}, {17, 36}};
	private static final int[] SEAL = {9, 1, 38, 11, 4, 38};
	private static final int[] TOMB = {3, 0, 38, 17, 8, 50};
	private static final BlockPos BOSS = new BlockPos(10, 1, 42);
	private static final BlockPos RELIQUARY = new BlockPos(4, 1, 26);
	private static final BlockPos HALL_CENTRE = new BlockPos(10, 1, 32);
	private static final BlockPos BEFORE_SEAL = new BlockPos(10, 1, 36);

	private static final int INSCRIPTION_RANGE = 6;
	private static final long REMIND_TICKS = 20 * 60 * 5;

	/** The order braziers have been lit in, per crypt. Not saved: a half-lit puzzle simply starts over. */
	private static final Map<String, List<Emblem>> PROGRESS = new HashMap<>();
	/** When each player last heard each crypt's greeting or inscription. */
	private static final Map<String, Long> LAST_TOLD = new HashMap<>();

	private Crypts() {
	}

	// --- Finding a crypt and its core -----------------------------------------------------------------

	/** The core piece of a crypt: everything the puzzle needs is at fixed spots in it. */
	record Core(StructureStart start, PoolElementStructurePiece piece, String key) {
		BlockPos world(BlockPos local) {
			return StructureTemplate.transform(local, Mirror.NONE, piece.getRotation(), BlockPos.ZERO).offset(piece.getPosition());
		}

		BlockPos world(int x, int y, int z) {
			return world(new BlockPos(x, y, z));
		}

		boolean inside(BlockPos pos, int[] box) {
			return BoundingBox.fromCorners(world(box[0], box[1], box[2]), world(box[3], box[4], box[5])).isInside(pos);
		}

		/** Which child's sarcophagus is at {@code pos}, or -1. */
		int sarcophagus(BlockPos pos) {
			for (int i = 0; i < SARCOPHAGI.length; i++) {
				int x = SARCOPHAGI[i][0], z = SARCOPHAGI[i][1];
				if (inside(pos, new int[]{x, 1, z, x + 1, 2, z})) return i;
			}
			return -1;
		}

		boolean isPuzzleBlock(BlockPos pos) {
			if (sarcophagus(pos) >= 0) return true;
			for (Emblem emblem : Emblem.values()) {
				BlockPos brazier = world(emblem.brazier);
				if (pos.equals(brazier) || pos.equals(brazier.below())) return true;
			}
			return false;
		}
	}

	static Optional<Core> coreAt(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server) || level.dimension() != Level.OVERWORLD) return Optional.empty();
		StructureStart start = server.structureManager().getStructureWithPieceAt(pos, holder -> holder.is(CRYPT));
		if (!start.isValid() || start.getPieces().isEmpty()) return Optional.empty();
		StructurePiece first = start.getPieces().getFirst();
		if (!(first instanceof PoolElementStructurePiece piece) || !(piece.getElement() instanceof SinglePoolElement element)
			|| !CORE_TEMPLATE.equals(element.getTemplateLocation())) {
			return Optional.empty();
		}
		return Optional.of(new Core(start, piece, Long.toString(start.getChunkPos().pack())));
	}

	// --- The fallen house: worked out from the crypt's key ---------------------------------------------

	/** The house buried here, its founder, the four children (by sarcophagus) and the order the children died in. */
	record Fallen(String house, String founder, String[] children, Emblem[] emblems, Emblem[] order) {
		/** The child resting in sarcophagus {@code i} died {@code rank}th (0 = first). */
		int rank(int i) {
			for (int r = 0; r < order.length; r++) if (order[r] == emblems[i]) return r;
			return 0;
		}

		int childWith(Emblem emblem) {
			for (int i = 0; i < emblems.length; i++) if (emblems[i] == emblem) return i;
			return 0;
		}
	}

	static Fallen fallen(String key) {
		RandomSource random = RandomSource.create(Long.parseLong(key) * 7919L + 104729L);
		String house = Names.house(random);
		String founder = Names.given(random, "");
		String[] children = new String[4];
		for (int i = 0; i < 4; i++) {
			String name;
			do {
				name = Names.given(random, founder);
			} while (java.util.Arrays.asList(children).contains(name));
			children[i] = name;
		}
		List<Emblem> emblems = new ArrayList<>(List.of(Emblem.values()));
		net.minecraft.util.Util.shuffle(emblems, random);
		List<Emblem> order = new ArrayList<>(List.of(Emblem.values()));
		net.minecraft.util.Util.shuffle(order, random);
		return new Fallen(house, founder, children, emblems.toArray(Emblem[]::new), order.toArray(Emblem[]::new));
	}

	private static MutableComponent emblemName(Emblem emblem) {
		return Text.t("crypt.emblem." + emblem.id());
	}

	// --- Each second, for players inside a crypt ---------------------------------------------------------

	public static void tick(ServerPlayer player) {
		ServerLevel level = player.level();
		Optional<Core> found = coreAt(level, player.blockPosition());
		if (found.isEmpty()) return;
		Core core = found.get();
		CryptState state = CryptState.get(level.getServer());
		CryptState.Crypt crypt = state.get(core.key());
		Fallen fallen = fallen(core.key());

		if (once(player, core.key() + ":greet")) {
			player.sendSystemMessage(Text.t(crypt.cleared ? "crypt.quiet" : "crypt.entered", fallen.house()).withStyle(ChatFormatting.DARK_GRAY));
		}
		if (crypt.cleared) return;
		if (!crypt.sigilHidden) {
			hideSigil(level, core, fallen).ifPresent(chest -> {
				crypt.sigilHidden = true;
				crypt.sigilChest = chest.asLong();
				state.setDirty();
			});
		}
		if (!crypt.opened && player.blockPosition().closerThan(core.world(BEFORE_SEAL), INSCRIPTION_RANGE) && once(player, core.key() + ":seal")) {
			player.sendSystemMessage(Text.t("crypt.inscription", fallen.founder(), fallen.house()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		}
		if (!crypt.flamesLit) checkFlames(level, player, core, crypt, state, fallen);
	}

	/** True at most once every few minutes per player and topic, so messages don't repeat while someone walks around. */
	private static boolean once(ServerPlayer player, String topic) {
		long now = player.level().getGameTime();
		String key = player.getUUID() + ":" + topic;
		Long last = LAST_TOLD.get(key);
		if (last != null && now - last < REMIND_TICKS) return false;
		LAST_TOLD.put(key, now);
		return true;
	}

	// --- The braziers -----------------------------------------------------------------------------------

	private static boolean isLit(ServerLevel level, Core core, Emblem emblem) {
		BlockState state = level.getBlockState(core.world(emblem.brazier));
		return state.hasProperty(CampfireBlock.LIT) && state.getValue(CampfireBlock.LIT);
	}

	private static void checkFlames(ServerLevel level, ServerPlayer player, Core core, CryptState.Crypt crypt, CryptState state, Fallen fallen) {
		List<Emblem> progress = PROGRESS.computeIfAbsent(core.key(), k -> new ArrayList<>());
		progress.removeIf(emblem -> !isLit(level, core, emblem));
		List<Emblem> answer = List.of(fallen.order());
		// Braziers lit since the last check join the order. Two lit in the same second get the benefit of the doubt.
		List<Emblem> newlyLit = new ArrayList<>();
		for (Emblem emblem : Emblem.values()) if (isLit(level, core, emblem) && !progress.contains(emblem)) newlyLit.add(emblem);
		if (newlyLit.isEmpty()) return;
		newlyLit.sort(java.util.Comparator.comparingInt(answer::indexOf));
		progress.addAll(newlyLit);

		BlockPos hall = core.world(HALL_CENTRE);
		boolean right = true;
		for (int i = 0; i < progress.size(); i++) if (progress.get(i) != answer.get(i)) right = false;
		if (!right) {
			// The wrong order: the flames die and the dead rise.
			for (Emblem emblem : Emblem.values()) {
				BlockPos pos = core.world(emblem.brazier);
				BlockState brazier = level.getBlockState(pos);
				if (brazier.hasProperty(CampfireBlock.LIT)) level.setBlockAndUpdate(pos, brazier.setValue(CampfireBlock.LIT, false));
			}
			progress.clear();
			tellNear(level, hall, Text.t("crypt.flames.wrong").withStyle(ChatFormatting.DARK_RED));
			level.playSound(null, hall, SoundEvents.ZOMBIE_VILLAGER_CONVERTED, SoundSource.HOSTILE, 1.5f, 0.6f);
			for (int i = 0; i < 3; i++) raiseDead(level, hall, i == 0 ? EntityTypes.SKELETON : EntityTypes.ZOMBIE, player);
			return;
		}
		for (Emblem emblem : newlyLit) {
			tellNear(level, hall, Text.t("crypt.flames.wakes", emblemName(emblem), fallen.children()[fallen.childWith(emblem)])
				.withStyle(ChatFormatting.AQUA));
		}
		level.playSound(null, hall, SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 2f, 0.8f);
		if (progress.size() == answer.size()) {
			crypt.flamesLit = true;
			state.setDirty();
			PROGRESS.remove(core.key());
			tellNear(level, hall, Text.t("crypt.flames.solved", fallen.founder(), fallen.house()).withStyle(ChatFormatting.GOLD));
			tellNear(level, hall, sigilHint(core, crypt, fallen));
			level.playSound(null, hall, SoundEvents.BEACON_ACTIVATE, SoundSource.BLOCKS, 2f, 0.6f);
		}
	}

	private static void raiseDead(ServerLevel level, BlockPos near, EntityType<? extends Mob> type, ServerPlayer target) {
		Mob mob = type.create(level, EntitySpawnReason.EVENT);
		if (mob == null) return;
		BlockPos spot = Champions.findSpot(level, near);
		mob.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		mob.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.EVENT, null);
		mob.setTarget(target);
		level.addFreshEntity(mob);
	}

	private static void tellNear(ServerLevel level, BlockPos pos, Component message) {
		for (ServerPlayer player : level.players()) {
			if (player.blockPosition().closerThan(pos, 40)) player.sendSystemMessage(message);
		}
	}

	// --- The sigil ----------------------------------------------------------------------------------------

	/**
	 * Hides the founder's sigil in the catacombs' chest farthest from the hall, or in the reliquary by the stairs
	 * when the catacombs have no chest. Returns false (to try again later) if none of them is loaded yet.
	 */
	private static Optional<BlockPos> hideSigil(ServerLevel level, Core core, Fallen fallen) {
		BlockPos hall = core.world(HALL_CENTRE);
		ChestBlockEntity best = null;
		List<StructurePiece> pieces = core.start().getPieces();
		for (StructurePiece piece : pieces.subList(1, pieces.size())) {
			BoundingBox box = piece.getBoundingBox();
			for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
				for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
					LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
					if (chunk == null) continue;
					for (BlockEntity entity : chunk.getBlockEntities().values()) {
						if (entity instanceof ChestBlockEntity chest && box.isInside(entity.getBlockPos())
							&& (best == null || entity.getBlockPos().distSqr(hall) > best.getBlockPos().distSqr(hall))) {
							best = chest;
						}
					}
				}
			}
		}
		if (best == null && level.getBlockEntity(core.world(RELIQUARY)) instanceof ChestBlockEntity reliquary) best = reliquary;
		if (best == null) return Optional.empty();
		ItemStack sigil = sigil(core.key(), fallen);
		RandomSource random = level.getRandom();
		for (int attempt = 0; attempt < 54; attempt++) {
			int slot = random.nextInt(best.getContainerSize());
			if (best.getItem(slot).isEmpty()) {
				best.setItem(slot, sigil);
				return Optional.of(best.getBlockPos());
			}
		}
		best.setItem(0, sigil);
		return Optional.of(best.getBlockPos());
	}

	/** Where to look for the sigil: a direction from the hall, and how to use it. */
	private static Component sigilHint(Core core, CryptState.Crypt crypt, Fallen fallen) {
		BlockPos hall = core.world(HALL_CENTRE);
		if (crypt.sigilChest == 0) return Text.t("crypt.sigil.hint_near", fallen.house()).withStyle(ChatFormatting.YELLOW);
		BlockPos chest = BlockPos.of(crypt.sigilChest);
		if (chest.closerThan(hall, 12)) return Text.t("crypt.sigil.hint_near", fallen.house()).withStyle(ChatFormatting.YELLOW);
		double angle = Math.toDegrees(Math.atan2(chest.getZ() - hall.getZ(), chest.getX() - hall.getX()));
		String[] names = {"east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east"};
		int index = (int) Math.round(((angle + 360) % 360) / 45) % 8;
		return Text.t("crypt.sigil.hint", fallen.house(), Text.t("direction." + names[index])).withStyle(ChatFormatting.YELLOW);
	}

	private static ItemStack sigil(String key, Fallen fallen) {
		ItemStack sigil = new ItemStack(Items.ECHO_SHARD);
		sigil.set(DataComponents.CUSTOM_NAME, Component.empty().append(Text.t("crypt.sigil.name", fallen.house()))
			.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_AQUA)));
		sigil.set(DataComponents.LORE, new ItemLore(List.of(Component.empty().append(Text.t("crypt.sigil.lore", fallen.founder(), fallen.house()))
			.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
		sigil.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		CompoundTag tag = new CompoundTag();
		tag.putString(SIGIL_TAG, key);
		sigil.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return sigil;
	}

	private static ItemStack findSigil(Player player, String key) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			CustomData data = stack.get(DataComponents.CUSTOM_DATA);
			if (data != null && key.equals(data.copyTag().getStringOr(SIGIL_TAG, ""))) return stack;
		}
		return ItemStack.EMPTY;
	}

	// --- Right-clicking epitaphs and the seal --------------------------------------------------------------

	public static InteractionResult onUseBlock(ServerPlayer player, Level level, BlockPos pos) {
		BlockState block = level.getBlockState(pos);
		if (!isCryptStone(block)) return InteractionResult.PASS;
		Optional<Core> found = coreAt(level, pos);
		if (found.isEmpty()) return InteractionResult.PASS;
		Core core = found.get();
		Fallen fallen = fallen(core.key());
		int child = core.sarcophagus(pos);
		if (child >= 0) {
			readEpitaph(player, fallen, child);
			return InteractionResult.SUCCESS;
		}
		if (core.inside(pos, SEAL)) {
			trySeal(player, (ServerLevel) level, core, fallen);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	private static boolean isCryptStone(BlockState state) {
		return state.is(Blocks.POLISHED_DEEPSLATE) || state.is(Blocks.POLISHED_DEEPSLATE_SLAB) || state.is(Blocks.SKELETON_SKULL)
			|| state.is(Blocks.POLISHED_BLACKSTONE_BRICKS) || state.is(Blocks.CHISELED_POLISHED_BLACKSTONE) || state.is(Blocks.GILDED_BLACKSTONE);
	}

	private static void readEpitaph(ServerPlayer player, Fallen fallen, int child) {
		Emblem emblem = fallen.emblems()[child];
		int rank = fallen.rank(child);
		String name = fallen.children()[child];
		MutableComponent clue = switch (rank) {
			case 0 -> Text.t("crypt.clue.first");
			case 1 -> Text.t("crypt.clue.second", fallen.children()[fallen.childWith(fallen.order()[0])]);
			case 2 -> Text.t("crypt.clue.third");
			default -> Text.t("crypt.clue.last");
		};
		player.sendSystemMessage(Text.t("crypt.epitaph", name, fallen.house(), Text.t("crypt.emblem." + emblem.id() + ".deed"))
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		player.sendSystemMessage(Text.t("oath.quoted", clue).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.ITALIC));
		player.level().playSound(null, player.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 0.6f, 1.2f);
	}

	private static void trySeal(ServerPlayer player, ServerLevel level, Core core, Fallen fallen) {
		CryptState state = CryptState.get(level.getServer());
		CryptState.Crypt crypt = state.get(core.key());
		if (crypt.opened) return;
		if (!crypt.flamesLit) {
			player.sendSystemMessage(Text.t("crypt.seal.cold").withStyle(ChatFormatting.GRAY));
			return;
		}
		// On Peaceful the founder would vanish as soon as they rose, taking the reward with them.
		if (!Champions.canAppear(player)) return;
		ItemStack sigil = findSigil(player, core.key());
		if (sigil.isEmpty()) {
			player.sendSystemMessage(Text.t("crypt.seal.no_sigil", fallen.house()).withStyle(ChatFormatting.GRAY));
			player.sendSystemMessage(sigilHint(core, crypt, fallen));
			return;
		}
		sigil.shrink(1);
		crypt.opened = true;
		state.setDirty();
		for (BlockPos pos : BlockPos.betweenClosed(core.world(SEAL[0], SEAL[1], SEAL[2]), core.world(SEAL[3], SEAL[4], SEAL[5]))) {
			BlockState stone = level.getBlockState(pos);
			level.levelEvent(2001, pos, Block.getId(stone));
			level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
		}
		BlockPos tomb = core.world(BOSS);
		level.playSound(null, tomb, SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 1f, 0.7f);
		Component name = Text.t("crypt.lord", fallen.founder(), fallen.house());
		tellNear(level, tomb, Text.t("crypt.seal.opens", name).withStyle(ChatFormatting.DARK_RED));
		Champions.spawnAt(player, Champions.BARROW_LORD, KEY_PREFIX + core.key(), tomb, name);
	}

	// --- Protection ---------------------------------------------------------------------------------------

	/** Until the founder rises, the puzzle and the tomb can't be broken into. */
	public static boolean canBreak(Level level, Player player, BlockPos pos) {
		if (player.isCreative() || level.dimension() != Level.OVERWORLD) return true;
		Optional<Core> found = coreAt(level, pos);
		if (found.isEmpty()) return true;
		Core core = found.get();
		CryptState.Crypt crypt = CryptState.get(level.getServer()).get(core.key());
		boolean held = (!crypt.opened && core.inside(pos, TOMB)) || (!crypt.cleared && core.isPuzzleBlock(pos));
		if (held && player instanceof ServerPlayer serverPlayer) {
			serverPlayer.sendSystemMessage(Text.t("crypt.holds").withStyle(ChatFormatting.DARK_GRAY), true);
		}
		return !held;
	}

	// --- Laying the founder to rest ---------------------------------------------------------------------

	public static void onFounderSlain(MinecraftServer server, LivingEntity lord, String key) {
		CryptState state = CryptState.get(server);
		CryptState.Crypt crypt = state.get(key);
		if (crypt.cleared) return;
		crypt.cleared = true;
		crypt.opened = true;
		state.setDirty();
		Fallen fallen = fallen(key);
		ServerLevel level = (ServerLevel) lord.level();
		ServerPlayer hero = lord.getKillCredit() instanceof ServerPlayer killer ? killer : nearestPlayer(level, lord.blockPosition());

		for (ServerPlayer player : level.players()) {
			if (!player.blockPosition().closerThan(lord.blockPosition(), 64)) continue;
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
			player.connection.send(new ClientboundSetTitleTextPacket(Text.t("crypt.rest.title").withStyle(ChatFormatting.GOLD)));
			player.connection.send(new ClientboundSetSubtitleTextPacket(Text.t("crypt.rest.subtitle", fallen.founder(), fallen.house())
				.withStyle(ChatFormatting.GRAY)));
			finishJobs(player);
		}
		level.playSound(null, lord.blockPosition(), SoundEvents.SOUL_ESCAPE.value(), SoundSource.HOSTILE, 3f, 0.5f);
		if (hero == null) return;

		Lineage lineage = Lineages.get(hero);
		Item item = heirloomItem(RandomSource.create(Long.parseLong(key)), Champions.tierOf(hero));
		ItemStack heirloom = lineage == null ? new ItemStack(item)
			: Heirlooms.fallenHouseGift(hero, lineage, item, fallen.house(), List.of(fallen.founder(), fallen.children()[fallen.childWith(fallen.order()[3])]));
		// Read the name first: adding a stack to the inventory empties the original.
		String heirloomName = Heirlooms.nameOf(heirloom);
		if (!hero.getInventory().add(heirloom)) hero.spawnAtLocation(level, heirloom);
		if (lineage == null) return;
		Lineages.tellHouse(server, lineage.house, Text.t("crypt.heirloom", lineage.current.name(),
			Component.literal(heirloomName).withStyle(ChatFormatting.GOLD), fallen.house()).withStyle(ChatFormatting.GRAY));
		Lineages.record(server, lineage, Text.t("crypt.chronicle", lineage.current.name(), fallen.founder(), fallen.house(), heirloomName));
		Renown.change(hero, lineage, BloodlineConfig.get().renown.cryptCleared, Text.t("crypt.renown", fallen.house()));
	}

	/** An inn job to lay a crypt's founder to rest is done when any founder is laid to rest with the player nearby. */
	private static void finishJobs(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null) return;
		for (Quest quest : lineage.allQuests()) {
			if (quest.type == Quest.Type.INN && !quest.isDone() && !quest.targets.isEmpty() && JOB.equals(quest.targets.getFirst())) {
				quest.progress = quest.required;
				quest.lastContributor = player.getUUID();
				Lineages.markDirty(player.level().getServer());
			}
		}
	}

	private static ServerPlayer nearestPlayer(ServerLevel level, BlockPos pos) {
		ServerPlayer best = null;
		for (ServerPlayer player : level.players()) {
			if (player.isSpectator() || !player.blockPosition().closerThan(pos, 48)) continue;
			if (best == null || player.blockPosition().distSqr(pos) < best.blockPosition().distSqr(pos)) best = player;
		}
		return best;
	}

	/** Iron for young houses, diamond for renowned ones. */
	private static Item heirloomItem(RandomSource random, int tier) {
		boolean diamond = tier >= 2;
		return switch (random.nextInt(4)) {
			case 0 -> diamond ? Items.DIAMOND_SWORD : Items.IRON_SWORD;
			case 1 -> diamond ? Items.DIAMOND_AXE : Items.IRON_AXE;
			case 2 -> diamond ? Items.DIAMOND_CHESTPLATE : Items.IRON_CHESTPLATE;
			default -> diamond ? Items.DIAMOND_HELMET : Items.IRON_HELMET;
		};
	}

	// --- For admins and testers ---------------------------------------------------------------------------

	/** The answer to the crypt the player stands in, for /bloodline debug crypt. */
	static Optional<Component> describe(ServerPlayer player) {
		return coreAt(player.level(), player.blockPosition()).map(core -> {
			Fallen fallen = fallen(core.key());
			CryptState.Crypt crypt = CryptState.get(player.level().getServer()).get(core.key());
			MutableComponent order = Component.empty();
			for (int i = 0; i < fallen.order().length; i++) {
				if (i > 0) order.append(", ");
				order.append(emblemName(fallen.order()[i]));
			}
			return Text.t("command.crypt", fallen.house(), fallen.founder(), order, String.valueOf(crypt.sigilHidden),
				String.valueOf(crypt.flamesLit), String.valueOf(crypt.opened), String.valueOf(crypt.cleared));
		});
	}

	/** Forgets the crypt's progress, relights nothing and closes nothing: for testing the puzzle again in a fresh crypt. */
	static boolean reset(ServerPlayer player) {
		Optional<Core> core = coreAt(player.level(), player.blockPosition());
		core.ifPresent(c -> {
			CryptState.get(player.level().getServer()).forget(c.key());
			PROGRESS.remove(c.key());
		});
		return core.isPresent();
	}
}
