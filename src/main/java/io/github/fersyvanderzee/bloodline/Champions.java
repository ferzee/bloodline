package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Named boss mobs that questlines send the family after. A champion is spawned near the
 * player when they reach the place a quest points to, with bodyguards and a boss bar.
 */
public final class Champions {
	private static final String TAG = "bloodline_champion";
	private static final String OWNER_TAG = "bloodline_owner=";
	private static final String KEY_TAG = "bloodline_key=";
	private static final String KIND_TAG = "bloodline_kind=";
	private static final double BAR_RANGE = 48;

	/** Names and taunts are in the language file as bloodline.champion.<id> and .taunt. */
	public record Kind(String id, EntityType<? extends Mob> type, double health, double extraDamage,
					   Map<EquipmentSlot, Item> gear, EntityType<? extends Mob> guard, int guards, BossEvent.BossBarColor color) {
		public MutableComponent name() {
			return Text.t("champion." + id);
		}

		public MutableComponent taunt() {
			return Text.t("champion." + id + ".taunt");
		}
	}

	public static final Kind FORTRESS_CAPTAIN = new Kind("fortress_captain", EntityTypes.WITHER_SKELETON, 150, 4,
		Map.of(EquipmentSlot.MAINHAND, Items.NETHERITE_SWORD, EquipmentSlot.HEAD, Items.NETHERITE_HELMET, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE),
		EntityTypes.BLAZE, 2, BossEvent.BossBarColor.RED);
	public static final Kind LOST_DELVER = new Kind("lost_delver", EntityTypes.ZOMBIE, 120, 3,
		Map.of(EquipmentSlot.MAINHAND, Items.DIAMOND_PICKAXE, EquipmentSlot.HEAD, Items.IRON_HELMET, EquipmentSlot.CHEST, Items.IRON_CHESTPLATE,
			EquipmentSlot.LEGS, Items.IRON_LEGGINGS),
		EntityTypes.CAVE_SPIDER, 3, BossEvent.BossBarColor.BLUE);
	public static final Kind WARLORD = new Kind("warlord", EntityTypes.VINDICATOR, 150, 3,
		Map.of(EquipmentSlot.MAINHAND, Items.NETHERITE_AXE, EquipmentSlot.HEAD, Items.IRON_HELMET),
		EntityTypes.PILLAGER, 3, BossEvent.BossBarColor.PURPLE);
	public static final Kind THIEF = new Kind("thief", EntityTypes.VINDICATOR, 80, 1,
		Map.of(EquipmentSlot.HEAD, Items.LEATHER_HELMET),
		EntityTypes.PILLAGER, 2, BossEvent.BossBarColor.YELLOW);

	// Bounties posted on inn quest boards.
	public static final Kind ROTGUT = new Kind("rotgut", EntityTypes.ZOMBIE, 60, 2,
		Map.of(EquipmentSlot.MAINHAND, Items.IRON_SWORD, EquipmentSlot.HEAD, Items.IRON_HELMET),
		EntityTypes.ZOMBIE, 2, BossEvent.BossBarColor.GREEN);
	public static final Kind OLD_BONES = new Kind("old_bones", EntityTypes.SKELETON, 50, 0,
		Map.of(EquipmentSlot.MAINHAND, Items.BOW, EquipmentSlot.HEAD, Items.CHAINMAIL_HELMET),
		EntityTypes.SKELETON, 2, BossEvent.BossBarColor.WHITE);
	public static final Kind BROODMOTHER = new Kind("broodmother", EntityTypes.SPIDER, 70, 2,
		Map.of(), EntityTypes.CAVE_SPIDER, 3, BossEvent.BossBarColor.PINK);
	public static final Kind DESERTER = new Kind("deserter", EntityTypes.PILLAGER, 60, 1,
		Map.of(EquipmentSlot.MAINHAND, Items.CROSSBOW, EquipmentSlot.HEAD, Items.LEATHER_HELMET),
		EntityTypes.PILLAGER, 2, BossEvent.BossBarColor.PURPLE);

	public static final List<Kind> ALL = List.of(FORTRESS_CAPTAIN, LOST_DELVER, WARLORD, THIEF, ROTGUT, OLD_BONES, BROODMOTHER, DESERTER);
	public static final List<Kind> BOUNTIES = List.of(ROTGUT, OLD_BONES, BROODMOTHER, DESERTER);

	private static final Map<LivingEntity, ServerBossEvent> BARS = new IdentityHashMap<>();

	private Champions() {
	}

	/**
	 * Spawns a champion near the player. {@code key} ties it to the quest that wants it dead.
	 * {@code mainHand} overrides the weapon (used to show a stolen heirloom).
	 */
	public static void spawn(ServerPlayer player, Kind kind, String key, Optional<ItemStack> mainHand, List<String> preparations) {
		ServerLevel level = player.level();
		BlockPos pos = findSpot(level, player.blockPosition());
		Mob champion = kind.type().create(level, EntitySpawnReason.EVENT);
		if (champion == null) return;
		champion.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		champion.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot.getType() != EquipmentSlot.Type.HAND && slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
			Item item = kind.gear().get(slot);
			champion.setItemSlot(slot, item == null ? ItemStack.EMPTY : new ItemStack(item));
			champion.setDropChance(slot, 0);
		}
		mainHand.ifPresent(stack -> champion.setItemSlot(EquipmentSlot.MAINHAND, stack.copy()));
		double health = preparations.contains(Questlines.WEAKENED) ? kind.health() * 0.7 : kind.health();
		champion.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
		champion.setHealth((float) health);
		if (preparations.contains(Questlines.EXPOSED)) {
			champion.addEffect(new MobEffectInstance(MobEffects.GLOWING, MobEffectInstance.INFINITE_DURATION, 0));
			champion.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, MobEffectInstance.INFINITE_DURATION, 0));
		}
		var damage = champion.getAttribute(Attributes.ATTACK_DAMAGE);
		if (damage != null) damage.setBaseValue(damage.getBaseValue() + kind.extraDamage());
		champion.setCustomName(kind.name().withStyle(ChatFormatting.GOLD));
		champion.addTag(KIND_TAG + kind.id());
		champion.setCustomNameVisible(true);
		champion.setPersistenceRequired();
		champion.addTag(TAG);
		champion.addTag(OWNER_TAG + player.getUUID());
		champion.addTag(KEY_TAG + key);
		champion.setTarget(player);
		level.addFreshEntity(champion);

		int guards = preparations.contains(Questlines.STANDS_ALONE) ? 0 : kind.guards();
		for (int i = 0; i < guards; i++) {
			Mob guard = kind.guard().create(level, EntitySpawnReason.EVENT);
			if (guard == null) continue;
			BlockPos guardPos = findSpot(level, pos);
			guard.snapTo(guardPos.getX() + 0.5, guardPos.getY(), guardPos.getZ() + 0.5);
			guard.finalizeSpawn(level, level.getCurrentDifficultyAt(guardPos), EntitySpawnReason.EVENT, null);
			guard.setPersistenceRequired();
			guard.setTarget(player);
			level.addFreshEntity(guard);
		}

		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 60, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(kind.name().withStyle(ChatFormatting.DARK_RED)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Text.t("oath.quoted", kind.taunt()).withStyle(ChatFormatting.GRAY)));
		level.playSound(null, pos, SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 2f, 1f);
	}

	/** Somewhere a few blocks from {@code origin} with room to stand, or {@code origin} itself. */
	static BlockPos findSpot(ServerLevel level, BlockPos origin) {
		var random = level.getRandom();
		for (int attempt = 0; attempt < 20; attempt++) {
			int dx = random.nextIntBetweenInclusive(-8, 8);
			int dz = random.nextIntBetweenInclusive(-8, 8);
			if (Math.abs(dx) < 4 && Math.abs(dz) < 4) continue;
			for (int dy = 3; dy >= -3; dy--) {
				BlockPos feet = origin.offset(dx, dy, dz);
				if (level.isEmptyBlock(feet) && level.isEmptyBlock(feet.above()) && !level.isEmptyBlock(feet.below())) {
					return feet;
				}
			}
		}
		return origin;
	}

	public static boolean isChampion(Entity entity) {
		return entity.entityTags().contains(TAG);
	}

	/** When a champion dies, the quest that wanted it dead is marked done (even if its owner is offline). */
	public static void onDeath(MinecraftServer server, LivingEntity champion) {
		ServerBossEvent bar = BARS.remove(champion);
		if (bar != null) bar.removeAllPlayers();
		UUID owner = null;
		String key = null;
		for (String tag : champion.entityTags()) {
			if (tag.startsWith(OWNER_TAG)) owner = UUID.fromString(tag.substring(OWNER_TAG.length()));
			if (tag.startsWith(KEY_TAG)) key = tag.substring(KEY_TAG.length());
		}
		if (owner == null || key == null) return;
		Lineage lineage = BloodlineState.get(server).get(owner);
		if (lineage == null) return;
		for (Quest quest : lineage.allQuests()) {
			if (quest.goal == Quest.Goal.KILL_CHAMPION && quest.targets.contains(key)) {
				quest.progress = quest.required;
				// Whoever landed the killing blow on behalf of the house collects the reward.
				if (champion.getKillCredit() instanceof ServerPlayer killer) quest.lastContributor = killer.getUUID();
				Lineages.markDirty(server);
			}
		}
	}

	public static void onLoad(Entity entity) {
		if (entity instanceof LivingEntity living && isChampion(entity) && !BARS.containsKey(living)) {
			ServerBossEvent bar = new ServerBossEvent(UUID.randomUUID(), living.getDisplayName(), colorOf(living), BossEvent.BossBarOverlay.NOTCHED_10);
			BARS.put(living, bar);
		}
	}

	public static void onUnload(Entity entity) {
		if (entity instanceof LivingEntity living) {
			ServerBossEvent bar = BARS.remove(living);
			if (bar != null) bar.removeAllPlayers();
		}
	}

	private static BossEvent.BossBarColor colorOf(LivingEntity entity) {
		for (Kind kind : ALL) {
			if (entity.entityTags().contains(KIND_TAG + kind.id())) return kind.color();
		}
		return BossEvent.BossBarColor.YELLOW;
	}

	/** Keeps boss bars in step with champion health and shows them to nearby players. */
	public static void tick(MinecraftServer server) {
		for (Map.Entry<LivingEntity, ServerBossEvent> entry : BARS.entrySet()) {
			LivingEntity champion = entry.getKey();
			ServerBossEvent bar = entry.getValue();
			bar.setProgress(champion.getHealth() / champion.getMaxHealth());
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				boolean near = player.level() == champion.level() && player.distanceTo(champion) < BAR_RANGE;
				if (near && !bar.getPlayers().contains(player)) bar.addPlayer(player);
				else if (!near && bar.getPlayers().contains(player)) bar.removePlayer(player);
			}
		}
	}
}
