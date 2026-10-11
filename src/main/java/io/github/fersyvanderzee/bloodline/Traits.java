package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.Trait.Effect;
import io.github.fersyvanderzee.bloodline.Trait.Kind;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
import static net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE;

public final class Traits {
	public static final int MAX_TRAITS = 4;
	private static final double PASS_ON_BOON = 0.5;
	private static final double PASS_ON_FLAW = 0.35;
	private static final double QUIRK_CHANCE = 0.15;
	private static final double FLAW_CHANCE = 0.25;

	private static final Map<String, Trait> ALL = new LinkedHashMap<>();

	static {
		add("stalwart", Kind.BOON, "health", "Stalwart", "Broad-shouldered and hard to kill. +2 hearts.",
			e(Attributes.MAX_HEALTH, 4, ADD_VALUE));
		add("frail", Kind.FLAW, "health", "Frail", "Sickly since birth. -2 hearts.",
			e(Attributes.MAX_HEALTH, -4, ADD_VALUE));
		add("swift", Kind.BOON, "speed", "Swift", "Light on their feet. +8% movement speed.",
			e(Attributes.MOVEMENT_SPEED, 0.08, ADD_MULTIPLIED_BASE));
		add("sluggish", Kind.FLAW, "speed", "Sluggish", "Never in a hurry. -6% movement speed.",
			e(Attributes.MOVEMENT_SPEED, -0.06, ADD_MULTIPLIED_BASE));
		add("mighty", Kind.BOON, "strength", "Mighty", "Strong arms. +1 attack damage.",
			e(Attributes.ATTACK_DAMAGE, 1, ADD_VALUE));
		add("feeble", Kind.FLAW, "strength", "Feeble", "Weak arms. -1 attack damage.",
			e(Attributes.ATTACK_DAMAGE, -1, ADD_VALUE));
		add("quick_handed", Kind.BOON, "attack_speed", "Quick-Handed", "Fast with a blade. +10% attack speed.",
			e(Attributes.ATTACK_SPEED, 0.1, ADD_MULTIPLIED_BASE));
		add("sure_footed", Kind.BOON, "falling", "Sure-Footed", "Lands like a cat. Takes fall damage 2 blocks later.",
			e(Attributes.SAFE_FALL_DISTANCE, 2, ADD_VALUE));
		add("clumsy", Kind.FLAW, "falling", "Clumsy", "Always tripping. +50% fall damage.",
			e(Attributes.FALL_DAMAGE_MULTIPLIER, 0.5, ADD_VALUE));
		add("stonehand", Kind.BOON, "mining", "Stonehand", "A born miner. Breaks blocks 15% faster.",
			e(Attributes.BLOCK_BREAK_SPEED, 0.15, ADD_MULTIPLIED_BASE));
		add("long_armed", Kind.BOON, "reach", "Long-Armed", "Reaches further. +1 block reach.",
			e(Attributes.BLOCK_INTERACTION_RANGE, 1, ADD_VALUE), e(Attributes.ENTITY_INTERACTION_RANGE, 0.5, ADD_VALUE));
		add("lucky", Kind.BOON, "luck", "Lucky", "Fortune smiles on them. +1 luck.",
			e(Attributes.LUCK, 1, ADD_VALUE));
		add("unlucky", Kind.FLAW, "luck", "Ill-Starred", "Born under a bad sign. -1 luck.",
			e(Attributes.LUCK, -1, ADD_VALUE));
		add("thick_skinned", Kind.BOON, "armor", "Thick-Skinned", "Hide like leather. +2 armor.",
			e(Attributes.ARMOR, 2, ADD_VALUE));
		add("unyielding", Kind.BOON, "knockback", "Unyielding", "Hard to push around. +20% knockback resistance.",
			e(Attributes.KNOCKBACK_RESISTANCE, 0.2, ADD_VALUE));
		add("fireborn", Kind.BOON, "fire", "Fireborn", "Fire doesn't cling to them. Burns for half as long.",
			e(Attributes.BURNING_TIME, -0.5, ADD_MULTIPLIED_BASE));
		add("waterborn", Kind.BOON, "water", "Waterborn", "At home in the water. Swims faster and mines faster underwater.",
			e(Attributes.WATER_MOVEMENT_EFFICIENCY, 0.25, ADD_VALUE), e(Attributes.SUBMERGED_MINING_SPEED, 0.5, ADD_VALUE));
		add("deep_lunged", Kind.BOON, "water_breathing", "Deep-Lunged", "Can hold their breath much longer.",
			e(Attributes.OXYGEN_BONUS, 2, ADD_VALUE));
		add("nimble", Kind.BOON, "jump", "Nimble", "Jumps higher than most.",
			e(Attributes.JUMP_STRENGTH, 0.06, ADD_VALUE));
		add("tall", Kind.QUIRK, "size", "Tall", "Stands a head above the rest, with a little extra reach.",
			e(Attributes.SCALE, 0.08, ADD_VALUE), e(Attributes.ENTITY_INTERACTION_RANGE, 0.25, ADD_VALUE));
		add("small", Kind.QUIRK, "size", "Small", "Short, but quick to duck through gaps.",
			e(Attributes.SCALE, -0.1, ADD_VALUE));
	}

	private Traits() {
	}

	private static void add(String id, Kind kind, String group, String name, String description, Effect... effects) {
		ALL.put(id, new Trait(id, kind, group, name, description, List.of(effects)));
	}

	private static Effect e(Holder<Attribute> attribute, double amount, Operation operation) {
		return new Effect(attribute, amount, operation);
	}

	public static Trait get(String id) {
		return ALL.get(id);
	}

	/** Traits for the founder of a new family: two traits, sometimes including a flaw. */
	public static List<String> rollFounder(RandomSource random) {
		List<String> traits = new ArrayList<>();
		addRandom(traits, random, Kind.BOON);
		addRandom(traits, random, random.nextFloat() < 0.2 ? Kind.FLAW : Kind.BOON);
		return traits;
	}

	/** Traits for an heir: some of the parent's traits pass on, plus one new mutation. */
	public static List<String> rollHeir(List<String> parentTraits, RandomSource random) {
		List<String> traits = new ArrayList<>();
		for (String id : parentTraits) {
			Trait trait = get(id);
			if (trait == null) continue;
			double chance = trait.kind() == Kind.FLAW ? PASS_ON_FLAW : PASS_ON_BOON;
			if (random.nextDouble() < chance && traits.size() < MAX_TRAITS - 1) {
				traits.add(id);
			}
		}
		float roll = random.nextFloat();
		Kind kind = roll < QUIRK_CHANCE ? Kind.QUIRK : roll < QUIRK_CHANCE + FLAW_CHANCE ? Kind.FLAW : Kind.BOON;
		// The mutation is always something the parent didn't have, so every heir brings something new.
		if (!addRandom(traits, random, kind, parentTraits)) {
			addRandom(traits, random, Kind.BOON, parentTraits);
		}
		return traits;
	}

	/** A boon earned during life, e.g. from settling a blood debt. Empty if there's no room for another trait. */
	public static Optional<String> rollExtraBoon(List<String> current, RandomSource random) {
		if (current.size() >= MAX_TRAITS) return Optional.empty();
		List<String> traits = new ArrayList<>(current);
		return addRandom(traits, random, Kind.BOON, List.of()) ? Optional.of(traits.getLast()) : Optional.empty();
	}

	private static void addRandom(List<String> traits, RandomSource random, Kind kind) {
		addRandom(traits, random, kind, List.of());
	}

	private static boolean addRandom(List<String> traits, RandomSource random, Kind kind, List<String> exclude) {
		List<Trait> options = ALL.values().stream()
			.filter(t -> t.kind() == kind)
			.filter(t -> !exclude.contains(t.id()))
			.filter(t -> traits.stream().map(Traits::get).noneMatch(o -> o.group().equals(t.group())))
			.toList();
		if (options.isEmpty()) return false;
		traits.add(options.get(random.nextInt(options.size())).id());
		return true;
	}

	/** Replaces any trait modifiers on the player with the ones for these traits. */
	public static void apply(ServerPlayer player, List<String> traitIds) {
		for (Trait trait : ALL.values()) {
			for (int i = 0; i < trait.effects().size(); i++) {
				AttributeInstance instance = player.getAttribute(trait.effects().get(i).attribute());
				if (instance != null) instance.removeModifier(modifierId(trait, i));
			}
		}
		for (String id : traitIds) {
			Trait trait = get(id);
			if (trait == null) continue;
			for (int i = 0; i < trait.effects().size(); i++) {
				Effect effect = trait.effects().get(i);
				AttributeInstance instance = player.getAttribute(effect.attribute());
				if (instance != null) {
					instance.addOrUpdateTransientModifier(new AttributeModifier(modifierId(trait, i), effect.amount(), effect.operation()));
				}
			}
		}
		if (player.getHealth() > player.getMaxHealth()) {
			player.setHealth(player.getMaxHealth());
		}
	}

	private static Identifier modifierId(Trait trait, int index) {
		return Bloodline.id("trait/" + trait.id() + "/" + index);
	}
}
