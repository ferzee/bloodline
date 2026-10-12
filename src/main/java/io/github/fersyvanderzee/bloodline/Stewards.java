package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.House;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Steward;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The house steward. Hired at an inn, they wait at the home of the member who hired them (their
 * bed) and keep the family vault: a few slots of storage shared by the whole house, growing with
 * renown.
 */
public final class Stewards {
	public static int hireCost() {
		return BloodlineConfig.get().steward.hireCost;
	}

	public static int moveCost() {
		return BloodlineConfig.get().steward.moveCost;
	}
	private static final String TAG = "bloodline_steward";
	private static final String HOUSE_TAG = "bloodline_house=";
	private static final int NEAR_HOME = 32;
	private static final double MAX_TALK_DISTANCE = 8;
	private static final long RESPAWN_COOLDOWN = 600;

	/** Vault slots by renown (by default 3 to start, 6 at 25 renown, 9 at 50). */
	public static int vaultSlots(int renown) {
		BloodlineConfig.Steward settings = BloodlineConfig.get().steward;
		int slots = 0;
		for (int tier = 0; tier < settings.vaultSlots.length; tier++) {
			if (renown >= settings.vaultRenown[tier]) slots = Math.max(slots, settings.vaultSlots[tier]);
		}
		return Math.max(0, Math.min(Steward.VAULT_SIZE, slots));
	}

	/** The renown at which the next vault slots unlock, or -1 if they all are. */
	public static int nextTier(int renown) {
		BloodlineConfig.Steward settings = BloodlineConfig.get().steward;
		int next = -1;
		for (int tier = 0; tier < settings.vaultRenown.length; tier++) {
			int needed = settings.vaultRenown[tier];
			if (needed > renown && settings.vaultSlots[tier] > vaultSlots(renown) && (next < 0 || needed < next)) next = needed;
		}
		return next;
	}

	private static final Map<UUID, Long> LAST_SPAWN = new HashMap<>();

	private Stewards() {
	}

	public static boolean isSteward(Entity entity) {
		return entity.entityTags().contains(TAG);
	}

	private static Optional<UUID> houseOf(Entity steward) {
		for (String tag : steward.entityTags()) {
			if (tag.startsWith(HOUSE_TAG)) return Optional.of(UUID.fromString(tag.substring(HOUSE_TAG.length())));
		}
		return Optional.empty();
	}

	// --- Hiring ---------------------------------------------------------------------------------

	/** Hires a steward for the house, or sends the existing one to the player's current home. */
	public static void hire(ServerPlayer player, Lineage lineage) {
		var respawn = player.getRespawnConfig();
		if (respawn == null) {
			player.sendSystemMessage(Text.t("steward.need_home").withStyle(ChatFormatting.RED));
			return;
		}
		GlobalPos home = respawn.respawnData().globalPos();
		House house = lineage.house;
		int cost = house.steward.isPresent() ? moveCost() : hireCost();
		if (Inns.count(player, Items.EMERALD) < cost) {
			player.sendSystemMessage(Text.t("steward.costs", cost).withStyle(ChatFormatting.RED));
			return;
		}
		Inns.take(player, Items.EMERALD, cost);
		if (house.steward.isPresent()) {
			house.steward.get().home = home;
			player.sendSystemMessage(Text.t("steward.moving", house.steward.get().name, describe(home))
				.withStyle(ChatFormatting.GOLD));
		} else {
			Steward steward = new Steward(home, Names.given(player.getRandom(), ""), List.of());
			house.steward = Optional.of(steward);
			Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.steward_hired", lineage.current.name(), steward.name, house.name));
			Lineages.tellHouse(player.level().getServer(), house, Text.t("steward.hired", steward.name, house.name, describe(home)).withStyle(ChatFormatting.GOLD));
		}
		Lineages.markDirty(player.level().getServer());
		player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 1f, 1f);
	}

	private static String describe(GlobalPos pos) {
		return pos.pos().getX() + ", " + pos.pos().getY() + ", " + pos.pos().getZ();
	}

	// --- The steward at home ------------------------------------------------------------------

	/**
	 * Keeps the house's steward standing at home whenever a member is nearby, and dismisses any
	 * steward left behind at an old home.
	 */
	public static void tick(ServerPlayer player, Lineage lineage) {
		ServerLevel level = player.level();
		BloodlineState state = BloodlineState.get(level.getServer());
		for (Villager villager : level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(48), Stewards::isSteward)) {
			House house = houseOf(villager).map(state::house).orElse(null);
			boolean stale = house == null || house.steward.isEmpty()
				|| house.steward.get().home.dimension() != level.dimension()
				|| !house.steward.get().home.pos().closerToCenterThan(villager.position(), 8);
			if (stale) villager.discard();
		}

		if (lineage.house.steward.isEmpty()) return;
		Steward steward = lineage.house.steward.get();
		if (steward.home.dimension() != level.dimension() || !steward.home.pos().closerToCenterThan(player.position(), NEAR_HOME)) return;
		boolean present = !level.getEntitiesOfClass(Villager.class, new AABB(steward.home.pos()).inflate(16),
			v -> isSteward(v) && houseOf(v).filter(lineage.house.id::equals).isPresent()).isEmpty();
		if (present) return;
		long now = level.getGameTime();
		Long last = LAST_SPAWN.get(lineage.house.id);
		if (last != null && now - last < RESPAWN_COOLDOWN) return;
		LAST_SPAWN.put(lineage.house.id, now);

		Villager villager = EntityTypes.VILLAGER.create(level, EntitySpawnReason.EVENT);
		if (villager == null) return;
		BlockPos pos = Champions.findSpot(level, steward.home.pos());
		villager.snapTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
		villager.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), EntitySpawnReason.EVENT, null);
		villager.setNoAi(true);
		villager.setPermanentlyInvulnerable(true);
		villager.setPersistenceRequired();
		villager.setCustomName(Text.t("steward.name", steward.name).withStyle(ChatFormatting.GOLD));
		villager.setCustomNameVisible(true);
		villager.addTag(TAG);
		villager.addTag(HOUSE_TAG + lineage.house.id);
		level.addFreshEntity(villager);
	}

	public static void onInteract(ServerPlayer player, Villager villager) {
		Lineage lineage = Lineages.get(player);
		BloodlineState state = BloodlineState.get(player.level().getServer());
		House house = houseOf(villager).map(state::house).orElse(null);
		if (lineage == null || house == null || house.steward.isEmpty()) return;
		if (lineage.house != house) {
			player.sendSystemMessage(Text.t("steward.not_yours", house.name).withStyle(ChatFormatting.GRAY));
			return;
		}
		Steward steward = house.steward.get();
		player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new VaultMenu(id, inventory, new VaultView(house, steward), villager),
			Text.t("steward.vault_title", house.name)));
	}

	// --- The family vault ---------------------------------------------------------------------

	/** Slots the vault shows: everything unlocked by renown, plus any slot that still holds items. */
	private static int unlocked(House house, Steward steward) {
		int slots = vaultSlots(house.renown);
		for (int slot = Steward.VAULT_SIZE - 1; slot >= slots; slot--) {
			if (!steward.vault.getItem(slot).isEmpty()) return slot + 1;
		}
		return slots;
	}

	private static ItemStack lockedSlot(House house) {
		ItemStack pane = new ItemStack(Items.BARRIER);
		int next = nextTier(house.renown);
		pane.set(DataComponents.CUSTOM_NAME, Text.t("steward.locked").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.DARK_GRAY)));
		pane.set(DataComponents.LORE, new ItemLore(List.of((next > 0 ? Text.t("steward.locked.needs", next, house.renown) : Component.empty())
			.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)))));
		// Marked so a player's own barrier blocks can never stack into a locked slot.
		CompoundTag tag = new CompoundTag();
		tag.putBoolean("bloodline_locked", true);
		pane.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		return pane;
	}

	/** Shows the shared vault with the locked slots filled by placeholders that never reach the vault itself. */
	private static final class VaultView implements Container {
		private final House house;
		private final Steward steward;

		VaultView(House house, Steward steward) {
			this.house = house;
			this.steward = steward;
		}

		private boolean open(int slot) {
			return slot < unlocked(house, steward);
		}

		@Override
		public int getContainerSize() {
			return Steward.VAULT_SIZE;
		}

		@Override
		public boolean isEmpty() {
			return steward.vault.isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return open(slot) ? steward.vault.getItem(slot) : lockedSlot(house);
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			return open(slot) ? steward.vault.removeItem(slot, count) : ItemStack.EMPTY;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			return open(slot) ? steward.vault.removeItemNoUpdate(slot) : ItemStack.EMPTY;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			if (open(slot)) steward.vault.setItem(slot, stack);
		}

		@Override
		public boolean canPlaceItem(int slot, ItemStack stack) {
			return open(slot);
		}

		@Override
		public void setChanged() {
			steward.vault.setChanged();
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void clearContent() {
		}
	}

	private static final class VaultMenu extends ChestMenu {
		private final Villager steward;

		VaultMenu(int id, Inventory inventory, Container vault, Villager steward) {
			super(MenuType.GENERIC_9x1, id, inventory, vault, 1);
			this.steward = steward;
		}

		@Override
		public void clicked(int slotIndex, int buttonNum, ContainerInput input, Player player) {
			if (slotIndex >= 0 && slotIndex < Steward.VAULT_SIZE && !getContainer().canPlaceItem(slotIndex, ItemStack.EMPTY)) {
				sendAllDataToRemote();
				return;
			}
			super.clicked(slotIndex, buttonNum, input, player);
			if (player instanceof ServerPlayer serverPlayer) Lineages.markDirty(serverPlayer.level().getServer());
		}

		@Override
		public boolean stillValid(Player player) {
			return steward.isAlive() && player.distanceTo(steward) < MAX_TALK_DISTANCE;
		}
	}
}
