package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.CompletedQuest;
import io.github.fersyvanderzee.bloodline.data.House;
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
	private static final int VISIT_RADIUS = 6;
	private static final int MIN_TICKS_BEFORE_VISIT = 600;
	/** Blood debts are marked with the branch whose ancestor they avenge. */
	private static final String DEBT_PREFIX = "debt:";
	private static final float STRONG_KILLER_HEALTH = 100;

	/** Text is in the language file as bloodline.unfinished.<key> (the title, with the ancestor's name) and .task. */
	private record Template(String key, Goal goal, List<String> targets, int required, int displayScale, Reward reward) {
		Template(String key, Goal goal, List<String> targets, int required, Reward reward) {
			this(key, goal, targets, required, 1, reward);
		}

		Quest create(String ancestorName) {
			return Quest.of(Type.UNFINISHED_BUSINESS, Text.t("unfinished." + key, ancestorName), Text.t("unfinished." + key + ".task"),
				goal, targets, required, reward).scaled(displayScale);
		}
	}

	private static final List<Template> MINING = List.of(
		new Template("finish_mine", Goal.MINE, List.of("iron_ore", "deepslate_iron_ore"), 16, Reward.item(3, "diamond", 2)),
		new Template("last_vein", Goal.MINE, List.of("diamond_ore", "deepslate_diamond_ore"), 3, Reward.item(5, "golden_apple", 2))
	);
	private static final List<Template> FIGHTING = List.of(
		new Template("finish_hunt", Goal.CUSTOM_STAT, List.of("mob_kills"), 25, Reward.item(4, "golden_apple", 1))
	);
	private static final List<Template> GENERAL = List.of(
		new Template("hearth", Goal.CRAFT, List.of("bread"), 12, Reward.item(2, "cake", 1)),
		new Template("halls", Goal.CRAFT, List.of("lantern"), 6, Reward.item(2, "emerald", 4)),
		new Template("arm_family", Goal.CRAFT, List.of("iron_sword"), 1, Reward.levels(3))
	);

	/** Unfinished business, picked by how the ancestor lived (their epithet). */
	private static final Map<String, List<Template>> BY_EPITHET = Map.ofEntries(
		Map.entry("delver", MINING), Map.entry("stonebreaker", MINING), Map.entry("deep_digger", MINING),
		Map.entry("slayer", FIGHTING), Map.entry("bold", FIGHTING), Map.entry("fierce", FIGHTING),
		Map.entry("scarred", FIGHTING), Map.entry("unbroken", FIGHTING),
		Map.entry("wanderer", wandering()), Map.entry("far_walker", wandering()), Map.entry("restless", wandering()),
		Map.entry("angler", List.of(new Template("pond", Goal.CUSTOM_STAT, List.of("fish_caught"), 10, Reward.item(3, "name_tag", 1)))),
		Map.entry("patient", List.of(new Template("pond", Goal.CUSTOM_STAT, List.of("fish_caught"), 10, Reward.item(3, "name_tag", 1)))),
		Map.entry("shepherd", herding()), Map.entry("herder", herding()),
		Map.entry("merchant", trading()), Map.entry("silver_tongued", trading()),
		Map.entry("brief", List.of(new Template("outlive", Goal.CUSTOM_STAT, List.of("play_time"), 24000, 1200, Reward.item(3, "golden_carrot", 8))))
	);

	private static List<Template> wandering() {
		return List.of(new Template("road", Goal.CUSTOM_STAT, List.of("walk_one_cm", "sprint_one_cm"),
			300_000, 100, Reward.item(3, "ender_pearl", 4)));
	}

	private static List<Template> herding() {
		return List.of(new Template("herd", Goal.CUSTOM_STAT, List.of("animals_bred"), 10, Reward.item(3, "emerald", 5)));
	}

	private static List<Template> trading() {
		return List.of(new Template("dealings", Goal.CUSTOM_STAT, List.of("traded_with_villager"), 10, Reward.item(3, "emerald", 10)));
	}

	private Quests() {
	}

	/** Goals for the founder of a new house. */
	public static void addFounding(ServerPlayer player, Lineage lineage) {
		List<String> banners = Arrays.stream(DyeColor.values()).map(c -> c.getSerializedName() + "_banner").toList();
		// Placing counts however the banner was obtained, so this works in creative mode too.
		add(player, lineage, Quest.of(Type.FOUNDING, Text.t("founding.banner", lineage.house.name), Text.t("founding.banner.task"),
			Goal.USE, banners, 1, Reward.levels(3)));
		add(player, lineage, Quest.of(Type.FOUNDING, Text.t("founding.bed"), Text.t("founding.bed.task"),
			Goal.CUSTOM_STAT, List.of("sleep_in_bed"), 1, Reward.item(2, "bread", 8)));
		add(player, lineage, Quest.of(Type.FOUNDING, Text.t("founding.name"), Text.t("founding.name.task"),
			Goal.CUSTOM_STAT, List.of("mob_kills"), 15, Reward.newTrait(5)));
		Inns.addIntroQuest(player, lineage);
	}

	/**
	 * Called when the head of the family dies. Their own unfinished duties die with them, except
	 * blood debts, which pass on. Then the heir's new duties are added.
	 */
	public static void onDeath(ServerPlayer player, Lineage lineage, Ancestor ancestor, DamageSource source, boolean sacrificed) {
		// This branch's own duties die with them; house quests (blood debts, the oath) carry on.
		lineage.ownQuests.clear();
		// Debts nobody settled in this life pass to the heir, and the house's standing suffers for it.
		String origin = DEBT_PREFIX + player.getUUID();
		long inherited = lineage.house.quests.stream().filter(q -> q.type == Type.BLOOD_DEBT && q.line.filter(origin::equals).isPresent()).count();
		if (inherited > 0) Renown.change(player, lineage, (int) inherited * Renown.debtInherited(), Text.t("renown.reason.debts_unpaid"));

		LivingEntity killer = source.getEntity() instanceof LivingEntity living ? living : player.getKillCredit();
		// A sacrifice is given, not taken: nobody owes a blood debt for it.
		if (killer != null && killer != player && !sacrificed) {
			Reward reward = Reward.newTrait(8);
			Quest debt;
			if (killer instanceof ServerPlayer otherPlayer) {
				debt = Quest.of(Type.BLOOD_DEBT, Text.t("debt.title", ancestor.name()),
					Text.t("debt.defeat", otherPlayer.getDisplayName()), Goal.KILL_PLAYER,
					List.of(otherPlayer.getUUID().toString()), 1, reward);
			} else {
				Identifier type = BuiltInRegistries.ENTITY_TYPE.getKey(killer.getType());
				int count = killer.getMaxHealth() >= STRONG_KILLER_HEALTH ? 1 : 5;
				debt = Quest.of(Type.BLOOD_DEBT, Text.t("debt.title", ancestor.name()),
					Text.t("debt.slay", killer.getType().getDescription()), Goal.KILL,
					List.of(type.toString()), count, reward);
			}
			debt.line = Optional.of(DEBT_PREFIX + player.getUUID());
			add(player, lineage, debt);
			List<Quest> debts = lineage.allQuests().stream().filter(q -> q.type == Type.BLOOD_DEBT).toList();
			for (int i = 0; i < debts.size() - BloodlineConfig.get().renown.maxBloodDebts; i++) {
				lineage.removeQuest(debts.get(i));
				Renown.change(player, lineage, Renown.debtForgotten(), Text.t("renown.reason.debt_forgotten", debts.get(i).title));
			}
		}

		GlobalPos place = GlobalPos.of(player.level().dimension(), player.blockPosition());
		add(player, lineage, Quest.of(Type.RESTING_PLACE, Text.t("resting.title", ancestor.name()),
			Text.t("resting.task", ancestor.name(), describe(place)), Goal.VISIT, List.of(), 1, Reward.levels(3)).at(place, VISIT_RADIUS));

		List<Template> options = BY_EPITHET.getOrDefault(Epithets.id(ancestor.epithet()), GENERAL);
		add(player, lineage, options.get(player.getRandom().nextInt(options.size())).create(ancestor.name()));
	}

	public static void add(ServerPlayer player, Lineage lineage, Quest quest) {
		quest.seen.put(player.getUUID().toString(), statTotal(player, quest));
		lineage.addQuest(quest);
	}

	/** "12, 64, -30", with the dimension added outside the Overworld. */
	public static MutableComponent describe(GlobalPos place) {
		String where = place.pos().getX() + ", " + place.pos().getY() + ", " + place.pos().getZ();
		if (place.dimension() == Level.NETHER) return Text.t("place.nether", where);
		if (place.dimension() == Level.END) return Text.t("place.end", where);
		if (place.dimension() == Level.OVERWORLD) return Component.literal(where);
		return Text.t("place.other", where, place.dimension().identifier().toString());
	}

	/** Checks progress for every duty; runs about once a second per player. */
	public static void tick(ServerPlayer player, Lineage lineage) {
		boolean changed = false;
		for (Quest quest : lineage.allQuests()) {
			switch (quest.goal) {
				case KILL_PLAYER, KILL_WITH_OATHBLADE, MANUAL -> {
				}
				case KILL_CHAMPION -> Questlines.maybeSpawnChampion(player, quest);
				case VISIT -> {
					// Wait a little after respawning, so dying next to the spawn point doesn't finish this instantly.
					if (player.tickCount > MIN_TICKS_BEFORE_VISIT && isAt(player, quest)) {
						quest.progress = 1;
					}
				}
				case DIMENSION -> {
					if (player.level().dimension().identifier().toString().equals(quest.targets.getFirst())) quest.progress = 1;
				}
				case DEPTH -> {
					if (player.getBlockY() <= Integer.parseInt(quest.targets.getFirst())) quest.progress = 1;
				}
				default -> {
					// Each member has their own baseline, so on shared quests everyone's progress adds up.
					String key = player.getUUID().toString();
					int now = statTotal(player, quest);
					Integer before = quest.seen.get(key);
					if (before == null) {
						before = quest.legacyLastSeen != 0 && quest.seen.isEmpty() ? quest.legacyLastSeen : now;
						quest.legacyLastSeen = 0;
					}
					int delta = now - before;
					if (delta != 0 || !quest.seen.containsKey(key)) {
						quest.seen.put(key, now);
						changed = true;
					}
					if (delta > 0) {
						quest.progress = Math.min(quest.required, quest.progress + delta);
						quest.lastContributor = player.getUUID();
						// Distance and time tick up every second, so only count-style duties report progress.
						if (!quest.isDone() && quest.displayScale == 1) player.sendOverlayMessage(line(quest, false));
					}
				}
			}
			if (quest.isDone() && isRewardedTo(player, quest)) {
				complete(player, lineage, quest);
				changed = true;
			}
		}
		if (changed) Lineages.markDirty(player.level().getServer());
	}

	/** On shared quests the member who made the final progress collects the reward, if they're still online. */
	private static boolean isRewardedTo(ServerPlayer player, Quest quest) {
		if (quest.lastContributor == null || quest.lastContributor.equals(player.getUUID())) return true;
		return player.level().getServer().getPlayerList().getPlayer(quest.lastContributor) == null;
	}

	/**
	 * Whether the player is at the quest's place. Small radii use real distance; larger ones (whole
	 * structures) only check horizontal distance, plus depth when the targets say "underground".
	 */
	public static boolean isAt(ServerPlayer player, Quest quest) {
		if (quest.place.isEmpty() || quest.place.get().dimension() != player.level().dimension()) return false;
		var pos = quest.place.get().pos();
		if (quest.radius <= VISIT_RADIUS) return pos.closerToCenterThan(player.position(), quest.radius);
		if (quest.targets.contains("underground") && player.getBlockY() >= 0) return false;
		double dx = player.getX() - pos.getX(), dz = player.getZ() - pos.getZ();
		return dx * dx + dz * dz <= (double) quest.radius * quest.radius;
	}

	/** Kills made with the family's sworn blade count towards oath steps. */
	public static void onKill(ServerPlayer player, LivingEntity killed) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || !Heirlooms.isOathblade(player, player.getMainHandItem())) return;
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(killed.getType()).toString();
		for (Quest quest : lineage.allQuests()) {
			if (quest.goal == Goal.KILL_WITH_OATHBLADE && quest.targets.contains(type) && !quest.isDone()) {
				quest.progress++;
				quest.lastContributor = player.getUUID();
				if (!quest.isDone()) player.sendOverlayMessage(line(quest, false));
				Lineages.markDirty(player.level().getServer());
			}
		}
	}

	/** Blood debts against players can't be read from statistics, so they are settled here. */
	public static void onPlayerKilled(ServerPlayer killer, ServerPlayer victim) {
		Lineage lineage = Lineages.get(killer);
		if (lineage == null) return;
		for (Quest quest : lineage.allQuests()) {
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
				case USE -> BuiltInRegistries.ITEM.getOptional(id).map(i -> stats.getValue(Stats.ITEM_USED.get(i))).orElse(0);
				// Custom stats must be looked up through the registry: Stats.CUSTOM only accepts the registered instance.
				case CUSTOM_STAT -> BuiltInRegistries.CUSTOM_STAT.getOptional(id).map(stat -> stats.getValue(Stats.CUSTOM.get(stat))).orElse(0);
				default -> 0;
			};
		}
		return total;
	}

	private static final int MAX_COMPLETED = 30;

	private static void complete(ServerPlayer player, Lineage lineage, Quest quest) {
		MinecraftServer server = player.level().getServer();
		lineage.removeQuest(quest);
		lineage.completed.add(CompletedQuest.of(quest, lineage.current.generation()));
		while (lineage.completed.size() > MAX_COMPLETED) lineage.completed.removeFirst();
		player.sendSystemMessage((quest.type == Type.QUESTLINE ? Text.t("quest.done") : Text.t("quest.fulfilled")).withStyle(ChatFormatting.GREEN)
			.append(" ")
			.append(quest.title.copy().withStyle(ChatFormatting.GOLD))
			.append(quest.type == Type.QUESTLINE ? Component.literal(" - ").append(quest.task.copy()).withStyle(ChatFormatting.GRAY) : Component.empty()));
		player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.7f, 1f);
		if (House.isShared(quest.type)) {
			Lineages.tellOthers(player, lineage.house, Text.t("quest.member_completed", lineage.current.name(), quest.title.copy().withStyle(ChatFormatting.GOLD))
				.withStyle(ChatFormatting.GREEN));
		}
		if (quest.type != Type.QUESTLINE && quest.type != Type.PREPARATION) {
			String deed = quest.type == Type.BLOOD_DEBT ? "chronicle.debt_settled" : "chronicle.duty_fulfilled";
			Lineages.record(server, lineage, Text.t(deed, lineage.current.name(), quest.title));
		}
		giveReward(player, lineage, quest.reward);
		if (quest.line.isPresent()) Questlines.onStepComplete(player, lineage, quest);
		Renown.change(player, lineage, Renown.forDuty(quest), Text.t("renown.reason.duty", quest.title));
	}

	private static void giveReward(ServerPlayer player, Lineage lineage, Reward reward) {
		if (reward.trait()) {
			Optional<String> trait = Traits.rollExtraBoon(lineage.current.traits(), player.getRandom());
			if (trait.isPresent()) {
				Member current = lineage.current;
				List<String> traits = new ArrayList<>(current.traits());
				traits.add(trait.get());
				lineage.current = new Member(current.name(), current.generation(), traits, current.bornDay(), current.statsAtBirth());
				Traits.apply(player, lineage.allTraits());
				player.sendSystemMessage(Text.t("trait.earned", current.name(), Traits.get(trait.get()).displayName())
					.append(" ").append(Text.t("trait.earned.inheritable").withStyle(ChatFormatting.DARK_GRAY)));
				Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.trait_earned", current.name(), Traits.get(trait.get()).name()));
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
		MutableComponent out = quest.title.copy().withStyle(ChatFormatting.GOLD)
			.append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
			.append(quest.task.copy().withStyle(ChatFormatting.WHITE));
		if (quest.required > 1) {
			out.append(Component.literal(" (" + quest.progress / quest.displayScale + "/" + quest.required / quest.displayScale + ")")
				.withStyle(ChatFormatting.GRAY));
		}
		if (withReward) {
			out.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(Text.t("quest.reward", describe(quest.reward)))));
		}
		return out;
	}

	/** "a new trait for the bloodline, or 8 levels", "3 levels, 2 Diamond". Empty for no reward. */
	public static MutableComponent describe(Reward reward) {
		List<Component> parts = new ArrayList<>();
		if (reward.trait()) parts.add(Text.t("reward.trait"));
		if (reward.levels() > 0) parts.add(Text.t(reward.trait() ? "reward.or_levels" : "reward.levels", reward.levels()));
		reward.item().flatMap(id -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)))
			.ifPresent(item -> parts.add(Text.t("reward.item", reward.count(), item.getName(new ItemStack(item)))));
		MutableComponent out = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0) out.append(", ");
			out.append(parts.get(i));
		}
		return out;
	}

	/** Lists the family's duties in chat. */
	public static void show(ServerPlayer viewer, Lineage lineage) {
		List<CompletedQuest> done = lineage.completedThisGeneration().stream().filter(c -> c.type() != Type.QUESTLINE).toList();
		if (lineage.allQuests().isEmpty() && done.isEmpty()) {
			viewer.sendSystemMessage(Text.t("duties.none", lineage.house.name).withStyle(ChatFormatting.GRAY));
			return;
		}
		viewer.sendSystemMessage(Text.t("duties.header", lineage.house.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
			.append(Component.literal("  ").append(Text.t("duties.renown", lineage.house.renown)).withStyle(s -> s.withBold(false).withColor(ChatFormatting.YELLOW))));
		for (Quest quest : lineage.allQuests()) {
			String bullet = switch (quest.type) {
				case BLOOD_DEBT -> "  ⚔ ";
				case QUESTLINE -> "  ✦ ";
				case PREPARATION -> "  ⚒ ";
				case EVENT -> "  ✧ ";
				case INN -> "  ✉ ";
				default -> "  • ";
			};
			viewer.sendSystemMessage(Component.literal(bullet).withStyle(ChatFormatting.DARK_GRAY)
				.append(line(quest, true)));
		}
		// Finished this generation: shown crossed out below the open ones.
		for (CompletedQuest quest : done) {
			viewer.sendSystemMessage(Component.literal("  ✔ ").withStyle(ChatFormatting.DARK_GREEN)
				.append(Component.empty().append(quest.title()).append(": ").append(quest.task())
					.withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withStrikethrough(true))));
		}
	}

	/** A short clickable reminder that there are duties waiting. */
	public static void remind(ServerPlayer player, Lineage lineage) {
		if (lineage.allQuests().isEmpty()) return;
		int n = lineage.allQuests().size();
		player.sendSystemMessage(Text.t(n == 1 ? "duties.reminder.one" : "duties.reminder", lineage.house.name, n)
			.withStyle(ChatFormatting.GRAY)
			.append(" ")
			.append(Text.t("button.show").withStyle(s -> s.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.RunCommand("/bloodline quests"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("/bloodline quests"))))));
	}
}
