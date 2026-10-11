package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Member;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Reward;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Family duties. Founders get a few goals to start their house. When someone dies their heir
 * inherits duties from them: avenge them (a blood debt that passes down until it is settled),
 * visit where they fell, and finish something they cared about in life.
 */
public final class Quests {
	private static final int MAX_BLOOD_DEBTS = 3;
	private static final int VISIT_RADIUS = 6;
	private static final int MIN_TICKS_BEFORE_VISIT = 600;
	private static final float STRONG_KILLER_HEALTH = 100;

	private record Template(String title, String task, Goal goal, List<String> targets, int required, int displayScale, Reward reward) {
		Template(String title, String task, Goal goal, List<String> targets, int required, Reward reward) {
			this(title, task, goal, targets, required, 1, reward);
		}

		Quest create(String ancestorName) {
			return new Quest(Type.UNFINISHED_BUSINESS, title.formatted(ancestorName), Component.literal(task), goal, targets, required,
				displayScale, 0, 0, Optional.empty(), reward);
		}
	}

	private static final List<Template> MINING = List.of(
		new Template("Finish %s's mine", "Mine iron ore", Goal.MINE, List.of("iron_ore", "deepslate_iron_ore"), 16, Reward.item(3, "diamond", 2)),
		new Template("%s's last vein", "Mine diamond ore", Goal.MINE, List.of("diamond_ore", "deepslate_diamond_ore"), 3, Reward.item(5, "golden_apple", 2))
	);
	private static final List<Template> FIGHTING = List.of(
		new Template("Finish %s's hunt", "Slay monsters", Goal.CUSTOM_STAT, List.of("mob_kills"), 25, Reward.item(4, "golden_apple", 1))
	);
	private static final List<Template> GENERAL = List.of(
		new Template("%s's hearth", "Bake bread", Goal.CRAFT, List.of("bread"), 12, Reward.item(2, "cake", 1)),
		new Template("Light %s's halls", "Craft lanterns", Goal.CRAFT, List.of("lantern"), 6, Reward.item(2, "emerald", 4)),
		new Template("Arm the family as %s wished", "Craft an iron sword", Goal.CRAFT, List.of("iron_sword"), 1, Reward.levels(3))
	);

	/** Unfinished business, picked by how the ancestor lived (their epithet). */
	private static final Map<String, List<Template>> BY_EPITHET = Map.ofEntries(
		Map.entry("the Delver", MINING), Map.entry("the Stonebreaker", MINING), Map.entry("the Deep-Digger", MINING),
		Map.entry("the Slayer", FIGHTING), Map.entry("the Bold", FIGHTING), Map.entry("the Fierce", FIGHTING),
		Map.entry("the Scarred", FIGHTING), Map.entry("the Unbroken", FIGHTING),
		Map.entry("the Wanderer", wandering()), Map.entry("the Far-Walker", wandering()), Map.entry("the Restless", wandering()),
		Map.entry("the Angler", List.of(new Template("%s's favourite pond", "Catch fish", Goal.CUSTOM_STAT, List.of("fish_caught"), 10, Reward.item(3, "name_tag", 1)))),
		Map.entry("the Patient", List.of(new Template("%s's favourite pond", "Catch fish", Goal.CUSTOM_STAT, List.of("fish_caught"), 10, Reward.item(3, "name_tag", 1)))),
		Map.entry("the Shepherd", herding()), Map.entry("the Herder", herding()),
		Map.entry("the Merchant", trading()), Map.entry("the Silver-Tongued", trading()),
		Map.entry("the Brief", List.of(new Template("Outlive %s", "Stay alive (minutes)", Goal.CUSTOM_STAT, List.of("play_time"), 24000, 1200, Reward.item(3, "golden_carrot", 8))))
	);

	private static List<Template> wandering() {
		return List.of(new Template("The road %s never finished", "Travel on foot (metres)", Goal.CUSTOM_STAT, List.of("walk_one_cm", "sprint_one_cm"),
			300_000, 100, Reward.item(3, "ender_pearl", 4)));
	}

	private static List<Template> herding() {
		return List.of(new Template("Tend %s's herd", "Breed animals", Goal.CUSTOM_STAT, List.of("animals_bred"), 10, Reward.item(3, "emerald", 5)));
	}

	private static List<Template> trading() {
		return List.of(new Template("Honour %s's dealings", "Trade with villagers", Goal.CUSTOM_STAT, List.of("traded_with_villager"), 10, Reward.item(3, "emerald", 10)));
	}

	private Quests() {
	}

	/** Goals for the founder of a new house. */
	public static void addFounding(ServerPlayer player, Lineage lineage) {
		List<String> banners = Arrays.stream(DyeColor.values()).map(c -> c.getSerializedName() + "_banner").toList();
		add(player, lineage, new Quest(Type.FOUNDING, "Raise the banner of House " + lineage.house, Component.literal("Craft a banner"),
			Goal.CRAFT, banners, 1, 1, 0, 0, Optional.empty(), Reward.levels(3)));
		add(player, lineage, new Quest(Type.FOUNDING, "A roof for the family", Component.literal("Sleep in a bed"),
			Goal.CUSTOM_STAT, List.of("sleep_in_bed"), 1, 1, 0, 0, Optional.empty(), Reward.item(2, "bread", 8)));
		add(player, lineage, new Quest(Type.FOUNDING, "Make a name for the house", Component.literal("Slay monsters"),
			Goal.CUSTOM_STAT, List.of("mob_kills"), 15, 1, 0, 0, Optional.empty(), Reward.newTrait(5)));
	}

	/**
	 * Called when the head of the family dies. Their own unfinished duties die with them, except
	 * blood debts, which pass on. Then the heir's new duties are added.
	 */
	public static void onDeath(ServerPlayer player, Lineage lineage, Ancestor ancestor, DamageSource source) {
		lineage.quests.removeIf(q -> q.type != Type.BLOOD_DEBT);

		LivingEntity killer = source.getEntity() instanceof LivingEntity living ? living : player.getKillCredit();
		if (killer != null && killer != player) {
			Reward reward = Reward.newTrait(8);
			Quest debt;
			if (killer instanceof ServerPlayer otherPlayer) {
				debt = new Quest(Type.BLOOD_DEBT, "Avenge " + ancestor.name(),
					Component.literal("Defeat ").append(otherPlayer.getDisplayName()), Goal.KILL_PLAYER,
					List.of(otherPlayer.getUUID().toString()), 1, 1, 0, 0, Optional.empty(), reward);
			} else {
				Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType());
				int count = killer.getMaxHealth() >= STRONG_KILLER_HEALTH ? 1 : 5;
				debt = new Quest(Type.BLOOD_DEBT, "Avenge " + ancestor.name(),
					Component.literal("Slay ").append(killer.getType().getDescription()), Goal.KILL,
					List.of(type.toString()), count, 1, 0, 0, Optional.empty(), reward);
			}
			add(player, lineage, debt);
			List<Quest> debts = lineage.quests.stream().filter(q -> q.type == Type.BLOOD_DEBT).toList();
			for (int i = 0; i < debts.size() - MAX_BLOOD_DEBTS; i++) {
				lineage.quests.remove(debts.get(i));
			}
		}

		GlobalPos place = GlobalPos.of(player.level().dimension(), player.blockPosition());
		add(player, lineage, new Quest(Type.RESTING_PLACE, "Where " + ancestor.name() + " fell",
			Component.literal("Visit " + ancestor.name() + "'s resting place at " + describe(place)), Goal.VISIT, List.of(), 1, 1, 0, 0,
			Optional.of(place), Reward.levels(3)));

		List<Template> options = BY_EPITHET.getOrDefault(ancestor.epithet(), GENERAL);
		add(player, lineage, options.get(player.getRandom().nextInt(options.size())).create(ancestor.name()));
	}

	private static void add(ServerPlayer player, Lineage lineage, Quest quest) {
		quest.lastSeen = statTotal(player, quest);
		lineage.quests.add(quest);
	}

	private static String describe(GlobalPos place) {
		String where = place.pos().getX() + ", " + place.pos().getY() + ", " + place.pos().getZ();
		if (place.dimension() == Level.NETHER) return where + " in the Nether";
		if (place.dimension() == Level.END) return where + " in the End";
		if (place.dimension() == Level.OVERWORLD) return where;
		return where + " in " + place.dimension().identifier();
	}

	/** Checks progress for every duty; runs about once a second per player. */
	public static void tick(ServerPlayer player, Lineage lineage) {
		boolean changed = false;
		for (Quest quest : List.copyOf(lineage.quests)) {
			switch (quest.goal) {
				case KILL_PLAYER -> {
				}
				case VISIT -> {
					// Wait a little after respawning, so dying next to the spawn point doesn't finish this instantly.
					if (player.tickCount > MIN_TICKS_BEFORE_VISIT && quest.place.isPresent() && quest.place.get().dimension() == player.level().dimension()
						&& quest.place.get().pos().closerToCenterThan(player.position(), VISIT_RADIUS)) {
						quest.progress = 1;
					}
				}
				default -> {
					int now = statTotal(player, quest);
					int delta = now - quest.lastSeen;
					if (delta != 0) {
						quest.lastSeen = now;
						changed = true;
					}
					if (delta > 0) {
						quest.progress = Math.min(quest.required, quest.progress + delta);
						// Distance and time tick up every second, so only count-style duties report progress.
						if (!quest.isDone() && quest.displayScale == 1) player.sendOverlayMessage(line(quest, false));
					}
				}
			}
			if (quest.isDone()) {
				complete(player, lineage, quest);
				changed = true;
			}
		}
		if (changed) Lineages.markDirty(player.level().getServer());
	}

	/** Blood debts against players can't be read from statistics, so they are settled here. */
	public static void onPlayerKilled(ServerPlayer killer, ServerPlayer victim) {
		Lineage lineage = Lineages.get(killer);
		if (lineage == null) return;
		for (Quest quest : List.copyOf(lineage.quests)) {
			if (quest.goal == Goal.KILL_PLAYER && quest.targets.contains(victim.getUUID().toString())) {
				quest.progress = 1;
				complete(killer, lineage, quest);
			}
		}
	}

	private static int statTotal(ServerPlayer player, Quest quest) {
		ServerStatsCounter stats = player.getStats();
		int total = 0;
		for (String target : quest.targets) {
			Identifier id = Identifier.parse(target);
			total += switch (quest.goal) {
				case KILL -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(t -> stats.getValue(Stats.ENTITY_KILLED.get(t))).orElse(0);
				case MINE -> BuiltInRegistries.BLOCK.getOptional(id).map(b -> stats.getValue(Stats.BLOCK_MINED.get(b))).orElse(0);
				case CRAFT -> BuiltInRegistries.ITEM.getOptional(id).map(i -> stats.getValue(Stats.ITEM_CRAFTED.get(i))).orElse(0);
				case CUSTOM_STAT -> stats.getValue(Stats.CUSTOM.get(id));
				case KILL_PLAYER, VISIT -> 0;
			};
		}
		return total;
	}

	private static void complete(ServerPlayer player, Lineage lineage, Quest quest) {
		MinecraftServer server = player.level().getServer();
		lineage.quests.remove(quest);
		player.sendSystemMessage(Component.literal("Duty fulfilled: ").withStyle(ChatFormatting.GREEN)
			.append(Component.literal(quest.title).withStyle(ChatFormatting.GOLD)));
		player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7f, 1f);
		String deed = quest.type == Type.BLOOD_DEBT ? " settled a blood debt: " : " fulfilled a family duty: ";
		Lineages.record(server, lineage, Component.literal(lineage.current.name() + deed + quest.title + "."));
		giveReward(player, lineage, quest.reward);
	}

	private static void giveReward(ServerPlayer player, Lineage lineage, Reward reward) {
		if (reward.trait()) {
			Optional<String> trait = Traits.rollExtraBoon(lineage.current.traits(), player.getRandom());
			if (trait.isPresent()) {
				Member current = lineage.current;
				List<String> traits = new ArrayList<>(current.traits());
				traits.add(trait.get());
				lineage.current = new Member(current.name(), current.generation(), traits, current.bornDay(), current.statsAtBirth());
				Traits.apply(player, traits);
				player.sendSystemMessage(Component.literal("The deed has changed " + current.name() + ". New trait: ")
					.append(Traits.get(trait.get()).displayName())
					.append(Component.literal(" (their children may inherit it)").withStyle(ChatFormatting.DARK_GRAY)));
				Lineages.record(player.level().getServer(), lineage, Component.literal(current.name() + " grew " + Traits.get(trait.get()).name() + "."));
				return;
			}
		}
		if (reward.levels() > 0) player.giveExperienceLevels(reward.levels());
		reward.item().flatMap(id -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))).ifPresent(item -> give(player, item, reward.count()));
	}

	private static void give(ServerPlayer player, Item item, int count) {
		ItemStack stack = new ItemStack(item, count);
		if (!player.getInventory().add(stack)) player.spawnAtLocation(player.level(), stack);
	}

	/** One duty as a line of text: "Avenge Piers: Slay Zombie (2/5)". */
	public static MutableComponent line(Quest quest, boolean withReward) {
		MutableComponent out = Component.literal(quest.title).withStyle(ChatFormatting.GOLD)
			.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
			.append(quest.task.copy().withStyle(ChatFormatting.WHITE));
		if (quest.required > 1) {
			out.append(Component.literal(" (" + quest.progress / quest.displayScale + "/" + quest.required / quest.displayScale + ")")
				.withStyle(ChatFormatting.GRAY));
		}
		if (withReward) {
			out.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Component.literal("Reward: " + describe(quest.reward)))));
		}
		return out;
	}

	private static String describe(Reward reward) {
		List<String> parts = new ArrayList<>();
		if (reward.trait()) parts.add("a new trait for the bloodline");
		if (reward.levels() > 0) parts.add((reward.trait() ? "or " : "") + reward.levels() + " levels");
		reward.item().flatMap(id -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)))
			.ifPresent(item -> parts.add(reward.count() + " " + item.getName(new ItemStack(item)).getString()));
		return String.join(", ", parts);
	}

	/** Lists the family's duties in chat. */
	public static void show(ServerPlayer viewer, Lineage lineage) {
		if (lineage.quests.isEmpty()) {
			viewer.sendSystemMessage(Component.literal("House " + lineage.house + " has no duties right now.").withStyle(ChatFormatting.GRAY));
			return;
		}
		viewer.sendSystemMessage(Component.literal("Duties of House " + lineage.house).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		for (Quest quest : lineage.quests) {
			viewer.sendSystemMessage(Component.literal(quest.type == Type.BLOOD_DEBT ? "  ⚔ " : "  • ").withStyle(ChatFormatting.DARK_GRAY)
				.append(line(quest, true)));
		}
	}

	/** A short clickable reminder that there are duties waiting. */
	public static void remind(ServerPlayer player, Lineage lineage) {
		if (lineage.quests.isEmpty()) return;
		int n = lineage.quests.size();
		player.sendSystemMessage(Component.literal("House " + lineage.house + " has " + n + (n == 1 ? " duty" : " duties") + " for you. ")
			.withStyle(ChatFormatting.GRAY)
			.append(Component.literal("[Show]").withStyle(s -> s.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.RunCommand("/bloodline quests"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("/bloodline quests"))))));
	}
}
