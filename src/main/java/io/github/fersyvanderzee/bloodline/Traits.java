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
	private static BloodlineConfig.Families settings() {
		return BloodlineConfig.get().families;
	}

	private static final Map<String, Trait> ALL = new LinkedHashMap<>();

	static {
		add("stalwart", Kind.BOON, "health",
			e(Attributes.MAX_HEALTH, 4, ADD_VALUE));
		add("frail", Kind.FLAW, "health",
			e(Attributes.MAX_HEALTH, -4, ADD_VALUE));
		add("swift", Kind.BOON, "speed",
			e(Attributes.MOVEMENT_SPEED, 0.08, ADD_MULTIPLIED_BASE));
		add("sluggish", Kind.FLAW, "speed",
			e(Attributes.MOVEMENT_SPEED, -0.06, ADD_MULTIPLIED_BASE));
		add("mighty", Kind.BOON, "strength",
			e(Attributes.ATTACK_DAMAGE, 1, ADD_VALUE));
		add("feeble", Kind.FLAW, "strength",
			e(Attributes.ATTACK_DAMAGE, -1, ADD_VALUE));
		add("quick_handed", Kind.BOON, "attack_speed",
			e(Attributes.ATTACK_SPEED, 0.1, ADD_MULTIPLIED_BASE));
		add("sure_footed", Kind.BOON, "falling",
			e(Attributes.SAFE_FALL_DISTANCE, 2, ADD_VALUE));
		add("clumsy", Kind.FLAW, "falling",
			e(Attributes.FALL_DAMAGE_MULTIPLIER, 0.5, ADD_VALUE));
		add("stonehand", Kind.BOON, "mining",
			e(Attributes.BLOCK_BREAK_SPEED, 0.15, ADD_MULTIPLIED_BASE));
		add("long_armed", Kind.BOON, "reach",
			e(Attributes.BLOCK_INTERACTION_RANGE, 1, ADD_VALUE), e(Attributes.ENTITY_INTERACTION_RANGE, 0.5, ADD_VALUE));
		add("lucky", Kind.BOON, "luck",
			e(Attributes.LUCK, 1, ADD_VALUE));
		add("unlucky", Kind.FLAW, "luck",
			e(Attributes.LUCK, -1, ADD_VALUE));
		add("thick_skinned", Kind.BOON, "armor",
			e(Attributes.ARMOR, 2, ADD_VALUE));
		add("unyielding", Kind.BOON, "knockback",
			e(Attributes.KNOCKBACK_RESISTANCE, 0.2, ADD_VALUE));
		add("fireborn", Kind.BOON, "fire",
			e(Attributes.BURNING_TIME, -0.5, ADD_MULTIPLIED_BASE));
		add("waterborn", Kind.BOON, "water",
			e(Attributes.WATER_MOVEMENT_EFFICIENCY, 0.25, ADD_VALUE), e(Attributes.SUBMERGED_MINING_SPEED, 0.5, ADD_VALUE));
		add("deep_lunged", Kind.BOON, "water_breathing",
			e(Attributes.OXYGEN_BONUS, 2, ADD_VALUE));
		add("nimble", Kind.BOON, "jump",
			e(Attributes.JUMP_STRENGTH, 0.06, ADD_VALUE));
		add("tall", Kind.QUIRK, "size",
			e(Attributes.SCALE, 0.08, ADD_VALUE), e(Attributes.ENTITY_INTERACTION_RANGE, 0.25, ADD_VALUE));
		add("small", Kind.QUIRK, "size",
			e(Attributes.SCALE, -0.1, ADD_VALUE));

		add("oathbound", Kind.LEGENDARY, "legacy_oathbound",
			e(Attributes.ATTACK_DAMAGE, 2, ADD_VALUE), e(Attributes.MAX_HEALTH, 4, ADD_VALUE));
		add("deepborn", Kind.LEGENDARY, "legacy_deepborn",
			e(Attributes.BLOCK_BREAK_SPEED, 0.25, ADD_MULTIPLIED_BASE), e(Attributes.LUCK, 2, ADD_VALUE));
		add("blood_sworn", Kind.LEGENDARY, "legacy_blood_sworn",
			e(Attributes.ATTACK_DAMAGE, 1, ADD_VALUE), e(Attributes.ARMOR, 2, ADD_VALUE), e(Attributes.MAX_HEALTH, 2, ADD_VALUE));
		add("beloved", Kind.LEGENDARY, "legacy_beloved",
			e(Attributes.MAX_HEALTH, 4, ADD_VALUE), e(Attributes.LUCK, 2, ADD_VALUE), e(Attributes.MOVEMENT_SPEED, 0.05, ADD_MULTIPLIED_BASE));
	}

	private Traits() {
	}

	/** Names and descriptions are in the language file, as bloodline.trait.<id> and bloodline.trait.<id>.desc. */
	private static void add(String id, Kind kind, String group, Effect... effects) {
		ALL.put(id, new Trait(id, kind, group, List.of(effects)));
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
		return rollHeir(parentTraits, random, false);
	}

	/** With {@code allBoons} (after a sacrifice) every boon passes on; flaws and quirks still roll as usual. */
	public static List<String> rollHeir(List<String> parentTraits, RandomSource random, boolean allBoons) {
		List<String> traits = new ArrayList<>();
		for (String id : parentTraits) {
			Trait trait = get(id);
			if (trait == null) continue;
			if (allBoons && trait.kind() == Kind.BOON) {
				traits.add(id);
				continue;
			}
			double chance = trait.kind() == Kind.FLAW ? settings().flawPassChance : settings().boonPassChance;
			if (random.nextDouble() < chance && traits.size() < settings().maxTraits - 1) {
				traits.add(id);
			}
		}
		float roll = random.nextFloat();
		double quirk = settings().newTraitQuirkChance, flaw = settings().newTraitFlawChance;
		Kind kind = roll < quirk ? Kind.QUIRK : roll < quirk + flaw ? Kind.FLAW : Kind.BOON;
		// The mutation is always something the parent didn't have, so every heir brings something new.
		if (traits.size() >= settings().maxTraits) return traits;
		if (!addRandom(traits, random, kind, parentTraits)) {
			addRandom(traits, random, Kind.BOON, parentTraits);
		}
		return traits;
	}

	/** A boon earned during life, e.g. from settling a blood debt. Empty if there's no room for another trait. */
	public static Optional<String> rollExtraBoon(List<String> current, RandomSource random) {
		if (current.size() >= settings().maxTraits) return Optional.empty();
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
