package io.github.fersyvanderzee.bloodline;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.gamerules.GameRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gear remembers which family members carried it. Once it has been carried by two generations
 * it becomes a named heirloom: it grows stronger with each generation and stays with the
 * family when its bearer dies.
 */
public final class Heirlooms {
	private static final String KEY = "bloodline_heirloom";
	private static final int MAX_RANK = 3;
	private static final EquipmentSlot[] SLOTS = {
		EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private static final Identifier WEAPON_BONUS = Bloodline.id("heirloom/weapon");
	private static final Identifier TOOL_BONUS = Bloodline.id("heirloom/tool");
	private static final Identifier ARMOR_BONUS = Bloodline.id("heirloom/armor");

	private static final List<String> NAMES = List.of(
		"Oathkeeper", "Dawnbringer", "Last Light", "Hearthguard", "Thornfang", "Mercy", "Old Faithful", "Stormcaller",
		"Gravesong", "Ironvow", "Duskbane", "Wyrmtooth", "Kinsguard", "Ashen Promise", "Remembrance", "Northwind",
		"Stubborn", "Heartwood", "Birthright", "Ember", "Long Memory", "Grandsire", "Homecoming", "Steadfast"
	);

	record Data(UUID owner, String house, List<Integer> generations, List<String> bearers, Optional<String> name) {
		static final Codec<Data> CODEC = RecordCodecBuilder.create(i -> i.group(
			UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Data::owner),
			Codec.STRING.fieldOf("house").forGetter(Data::house),
			Codec.INT.listOf().fieldOf("generations").forGetter(Data::generations),
			Codec.STRING.listOf().fieldOf("bearers").forGetter(Data::bearers),
			Codec.STRING.optionalFieldOf("name").forGetter(Data::name)
		).apply(i, Data::new));

		boolean isHeirloom() {
			return generations.size() >= 2;
		}

		int rank() {
			return Math.min(generations.size() - 1, MAX_RANK);
		}
	}

	private Heirlooms() {
	}

	static Optional<Data> read(ItemStack stack) {
		CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
		if (customData == null) return Optional.empty();
		return customData.copyTag().read(KEY, Data.CODEC);
	}

	private static void write(ItemStack stack, Data data) {
		CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.store(KEY, Data.CODEC, data));
		stack.set(DataComponents.LORE, new ItemLore(lore(stack, data)));
	}

	public static boolean keptOnDeath(Player player, ItemStack stack) {
		if (Chronicle.isChronicle(stack)) return true;
		return read(stack).filter(d -> d.isHeirloom() && d.owner().equals(player.getUUID())).isPresent();
	}

	/** Moves heirlooms that were kept back on death over to the respawned heir. */
	public static void passOn(ServerPlayer oldPlayer, ServerPlayer newPlayer) {
		if (oldPlayer.level().getGameRules().get(GameRules.KEEP_INVENTORY)) return;
		Inventory from = oldPlayer.getInventory();
		Inventory to = newPlayer.getInventory();
		for (int i = 0; i < from.getContainerSize(); i++) {
			ItemStack stack = from.getItem(i);
			if (!stack.isEmpty()) {
				to.setItem(i, stack);
				from.setItem(i, ItemStack.EMPTY);
			}
		}
	}

	/** Runs a few times a second for every player: records bearers and keeps heirloom bonuses up to date. */
	public static void tick(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || lineage.awaitingHeir) return;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (stack.isDamageableItem()) recordBearer(player, lineage, stack);
		}
		updateBonuses(player);
	}

	private static void recordBearer(ServerPlayer player, Lineage lineage, ItemStack stack) {
		int generation = lineage.current.generation();
		Optional<Data> existing = read(stack);
		if (existing.isEmpty()) {
			write(stack, new Data(player.getUUID(), lineage.house, List.of(generation), List.of(lineage.current.name()), Optional.empty()));
			return;
		}
		Data data = existing.get();
		if (!data.owner().equals(player.getUUID()) || data.generations().contains(generation)) return;

		boolean wasHeirloom = data.isHeirloom();
		List<Integer> generations = new ArrayList<>(data.generations());
		generations.add(generation);
		List<String> bearers = new ArrayList<>(data.bearers());
		bearers.add(lineage.current.name());
		String previousBearer = data.bearers().getLast();
		Optional<String> name = data.name();
		if (!wasHeirloom) {
			name = Optional.of(NAMES.get(player.getRandom().nextInt(NAMES.size())));
		}
		Data updated = new Data(data.owner(), data.house(), generations, bearers, name);
		Component itemName = stack.getItemName();
		if (!wasHeirloom) {
			if (!stack.has(DataComponents.CUSTOM_NAME)) {
				stack.set(DataComponents.CUSTOM_NAME, Component.literal(name.get()).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
			}
			stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		}
		write(stack, updated);

		MutableComponent message = !wasHeirloom
			? Component.literal(previousBearer + "'s ").append(itemName).append(" has passed to " + lineage.current.name()
				+ ". It is now an heirloom of House " + data.house() + ": ").append(Component.literal(name.get()).withStyle(ChatFormatting.GOLD))
			: Component.literal(name.get()).withStyle(ChatFormatting.GOLD).append(Component.literal(" passes to "
				+ lineage.current.name() + ", its " + ordinal(generations.size()) + " bearer.").withStyle(ChatFormatting.WHITE));
		player.sendSystemMessage(message);
		Lineages.record(player.level().getServer(), lineage, message);
		player.level().playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1f, 0.8f);
	}

	private static List<Component> lore(ItemStack stack, Data data) {
		List<Component> lines = new ArrayList<>();
		if (data.isHeirloom()) {
			lines.add(line("Heirloom of House " + data.house(), ChatFormatting.GOLD));
			lines.add(line("Carried by " + String.join(", ", data.bearers()), ChatFormatting.GRAY));
			String bonus = bonusDescription(stack, data.rank());
			if (bonus != null) lines.add(line(bonus, ChatFormatting.BLUE));
			lines.add(line("Stays with the family on death", ChatFormatting.DARK_GRAY));
		} else {
			lines.add(line("Carried by " + data.bearers().getFirst() + " " + data.house(), ChatFormatting.DARK_GRAY));
		}
		return lines;
	}

	private static Component line(String text, ChatFormatting color) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(color));
	}

	private static boolean isWeapon(ItemStack stack) {
		return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS)
			|| stack.is(ItemTags.MACE_ENCHANTABLE) || stack.is(ItemTags.TRIDENT_ENCHANTABLE);
	}

	private static boolean isTool(ItemStack stack) {
		return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES);
	}

	private static boolean isArmor(ItemStack stack) {
		var equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR;
	}

	private static String bonusDescription(ItemStack stack, int rank) {
		List<String> parts = new ArrayList<>();
		if (isWeapon(stack)) parts.add("+" + rank + " attack damage");
		if (isTool(stack)) parts.add("+" + rank * 2 + " mining efficiency");
		if (isArmor(stack)) parts.add("+" + rank * 0.5 + " armor toughness");
		return parts.isEmpty() ? null : String.join(", ", parts);
	}

	private static void updateBonuses(ServerPlayer player) {
		ItemStack mainHand = player.getMainHandItem();
		int handRank = read(mainHand).filter(d -> d.isHeirloom() && d.owner().equals(player.getUUID())).map(Data::rank).orElse(0);
		setBonus(player, Attributes.ATTACK_DAMAGE, WEAPON_BONUS, isWeapon(mainHand) ? handRank : 0);
		setBonus(player, Attributes.MINING_EFFICIENCY, TOOL_BONUS, isTool(mainHand) ? handRank * 2 : 0);

		double toughness = 0;
		for (EquipmentSlot slot : ARMOR) {
			ItemStack stack = player.getItemBySlot(slot);
			toughness += read(stack).filter(d -> d.isHeirloom() && d.owner().equals(player.getUUID())).map(Data::rank).orElse(0) * 0.5;
		}
		setBonus(player, Attributes.ARMOR_TOUGHNESS, ARMOR_BONUS, toughness);
	}

	private static void setBonus(ServerPlayer player, Holder<Attribute> attribute, Identifier id, double amount) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) return;
		AttributeModifier current = instance.getModifier(id);
		if (amount == 0) {
			if (current != null) instance.removeModifier(id);
		} else if (current == null || current.amount() != amount) {
			instance.addOrUpdateTransientModifier(new AttributeModifier(id, amount, AttributeModifier.Operation.ADD_VALUE));
		}
	}

	private static String ordinal(int n) {
		return switch (n) {
			case 2 -> "second";
			case 3 -> "third";
			case 4 -> "fourth";
			case 5 -> "fifth";
			case 6 -> "sixth";
			case 7 -> "seventh";
			default -> n + "th";
		};
	}
}
