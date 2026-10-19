package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Wayside shrines: small chapels along the way. Lighting the altar candles asks the most recent ancestor for a
 * blessing that matches how they lived (a Slayer's strength, a Delver's haste), once a Minecraft day. Each shrine
 * has its own name, and a house's first visit to it earns a little renown and a line in the Chronicle.
 */
public final class Shrines {
	public static final List<ResourceKey<Structure>> STRUCTURES = List.of(key("shrine_temperate"), key("shrine_cold"), key("shrine_warm"));
	private static final int BLESSING_TICKS = 20 * 60 * 10;
	/** How many shrine names the language file has (bloodline.shrine.name.0 to .N-1). */
	private static final int NAMES = 16;

	/** What each kind of life passes on as a blessing, by epithet. */
	private static final Map<String, List<Holder<MobEffect>>> BLESSINGS = Map.ofEntries(
		Map.entry("slayer", List.of(MobEffects.STRENGTH)), Map.entry("bold", List.of(MobEffects.STRENGTH)), Map.entry("fierce", List.of(MobEffects.STRENGTH)),
		Map.entry("delver", List.of(MobEffects.HASTE)), Map.entry("stonebreaker", List.of(MobEffects.HASTE)), Map.entry("deep_digger", List.of(MobEffects.HASTE)),
		Map.entry("wanderer", List.of(MobEffects.SPEED)), Map.entry("far_walker", List.of(MobEffects.SPEED)), Map.entry("restless", List.of(MobEffects.SPEED)),
		Map.entry("angler", List.of(MobEffects.LUCK)), Map.entry("patient", List.of(MobEffects.LUCK)),
		Map.entry("shepherd", List.of(MobEffects.REGENERATION)), Map.entry("herder", List.of(MobEffects.REGENERATION)),
		Map.entry("merchant", List.of(MobEffects.HERO_OF_THE_VILLAGE)), Map.entry("silver_tongued", List.of(MobEffects.HERO_OF_THE_VILLAGE)),
		Map.entry("scarred", List.of(MobEffects.RESISTANCE)), Map.entry("unbroken", List.of(MobEffects.RESISTANCE)),
		Map.entry("elder", List.of(MobEffects.ABSORPTION)),
		Map.entry("quiet", List.of(MobEffects.NIGHT_VISION)),
		Map.entry("brief", List.of(MobEffects.JUMP_BOOST)),
		Map.entry("devoted", List.of(MobEffects.STRENGTH, MobEffects.RESISTANCE))
	);
	private static final List<Holder<MobEffect>> FOUNDER_BLESSING = List.of(MobEffects.LUCK);

	/** The shrine each player was last told about, so the welcome shows once per visit. Not saved. */
	private static final Map<UUID, String> LAST_GREETED = new HashMap<>();

	private Shrines() {
	}

	private static ResourceKey<Structure> key(String path) {
		return ResourceKey.create(Registries.STRUCTURE, Bloodline.id(path));
	}

	/** The shrine at this position, if there is one. */
	static Optional<StructureStart> shrineAt(Level level, BlockPos pos) {
		if (!(level instanceof ServerLevel server)) return Optional.empty();
		StructureStart start = server.structureManager().getStructureWithPieceAt(pos, holder -> STRUCTURES.stream().anyMatch(holder::is));
		return start.isValid() ? Optional.of(start) : Optional.empty();
	}

	static String keyOf(StructureStart start) {
		return Long.toString(start.getChunkPos().pack());
	}

	/** "the Shrine of the Last Traveller": every shrine keeps the same name, worked out from where it stands. */
	public static MutableComponent name(String key) {
		RandomSource random = RandomSource.create(Long.parseLong(key) * 31L + 977L);
		return Text.t("shrine.name." + random.nextInt(NAMES));
	}

	// --- Arriving ---------------------------------------------------------------------------------------

	/** Runs about once a second: greets a player who steps into a shrine with its name and what it is for. */
	public static void tick(ServerPlayer player) {
		Optional<StructureStart> shrine = shrineAt(player.level(), player.blockPosition());
		if (shrine.isEmpty()) {
			LAST_GREETED.remove(player.getUUID());
			return;
		}
		String key = keyOf(shrine.get());
		if (key.equals(LAST_GREETED.put(player.getUUID(), key))) return;
		player.sendSystemMessage(Text.t("shrine.welcome", name(key).withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY), true);
	}

	// --- Lighting the candles ------------------------------------------------------------------------------

	/**
	 * Called when a player uses a block. Lighting a candle in a shrine (with flint and steel or a fire charge) asks
	 * for a blessing; unlit candles then light as usual. Already-lit candles still count, so someone who came
	 * before you doesn't spoil your prayer, and nothing else catches fire.
	 */
	public static InteractionResult onUseBlock(ServerPlayer player, Level level, BlockPos pos, ItemStack held) {
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof CandleBlock) || !(held.is(Items.FLINT_AND_STEEL) || held.is(Items.FIRE_CHARGE))) {
			return InteractionResult.PASS;
		}
		Optional<StructureStart> shrine = shrineAt(level, pos);
		if (shrine.isEmpty()) return InteractionResult.PASS;
		pray(player, keyOf(shrine.get()));
		return state.getValue(CandleBlock.LIT) ? InteractionResult.SUCCESS : InteractionResult.PASS;
	}

	/** The prayer itself: a blessing from the most recent ancestor (once a day), and the first visit recorded. */
	static void pray(ServerPlayer player, String key) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null) return;
		if (lineage.awaitingHeir) {
			player.sendSystemMessage(Text.t("shrine.mourning").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		var server = player.level().getServer();
		int today = Lineages.today(server);
		MutableComponent shrineName = name(key);

		if (lineage.house.milestones.add("shrine:" + key)) {
			Lineages.record(server, lineage, Text.t("chronicle.shrine", lineage.current.name(), shrineName.copy()));
			Renown.change(player, lineage, BloodlineConfig.get().renown.shrineVisited, Text.t("renown.reason.shrine", shrineName.copy()));
		}

		if (lineage.blessedDay == today) {
			player.sendSystemMessage(Text.t("shrine.already").withStyle(ChatFormatting.GRAY), true);
			return;
		}
		lineage.blessedDay = today;
		Lineages.markDirty(server);

		Ancestor ancestor = lineage.parent();
		List<Holder<MobEffect>> effects = ancestor == null ? FOUNDER_BLESSING
			: BLESSINGS.getOrDefault(Epithets.id(ancestor.epithet()), FOUNDER_BLESSING);
		MutableComponent names = Component.empty();
		for (int i = 0; i < effects.size(); i++) {
			player.addEffect(new MobEffectInstance(effects.get(i), BLESSING_TICKS, 0, true, true, true));
			if (i > 0) names.append(Text.t("shrine.and"));
			names.append(Component.translatable(effects.get(i).value().getDescriptionId()).withStyle(ChatFormatting.AQUA));
		}
		player.sendSystemMessage(ancestor == null
			? Text.t("shrine.blessing.founder", names).withStyle(ChatFormatting.GRAY)
			: Text.t("shrine.blessing", ancestor.fullName(), names).withStyle(ChatFormatting.GRAY));
		player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 0.8f);
		player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 0.4f, 1.4f);
	}
}
