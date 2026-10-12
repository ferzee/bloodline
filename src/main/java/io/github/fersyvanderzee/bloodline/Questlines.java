package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Reward;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Multi-step questlines.
 *
 * <p>The Founder's Oath: the founder swears one of three oaths, and the family has to finish its
 * four acts, at most one per generation. Finishing the oath gives the family a legendary trait
 * every later member is born with.
 *
 * <p>The Stolen Heirloom: when an heirloom is destroyed, thieves turn out to have taken it, and
 * the family has to get it back from their captain at a real pillager outpost.
 */
public final class Questlines {
	private static final String OATH = "oath";
	private static final String RECLAIM = "reclaim";
	private static final int STRUCTURE_RADIUS = 48;
	private static final double CHAMPION_SEARCH_RANGE = 64;

	@FunctionalInterface
	interface StepFactory {
		/** {@code title} is the act's title; {@code task} is the step's text from the language file. */
		Quest create(ServerPlayer player, Lineage lineage, Component title, Component task);
	}

	@FunctionalInterface
	interface StepHook {
		void run(ServerPlayer player, Lineage lineage);
	}

	record Step(StepFactory factory, StepHook onComplete) {
		Step(StepFactory factory) {
			this(factory, (p, l) -> {
			});
		}
	}

	/** The renown each act needs to begin is set in the config (see Renown.forAct). */
	/** Text is in the language file as bloodline.oath.<oath>.act<n>.name, .intro and .step<n>. */
	record Act(List<Step> steps) {
	}

	/** Optional groundwork before a champion fight; {@code effect} is what it does to the champion. */
	/** Title and task are in the language file as bloodline.prep.<key> and .task. */
	record Preparation(String effect, String key, Goal goal, List<String> targets, int required) {
	}

	public static final String STANDS_ALONE = "alone";
	public static final String WEAKENED = "weakened";
	public static final String EXPOSED = "exposed";

	private static final Preparation SHARPEN = new Preparation(WEAKENED, "sharpen", Goal.CUSTOM_STAT, List.of("enchant_item"), 1);
	private static final Preparation SPYGLASS = new Preparation(EXPOSED, "spyglass", Goal.CRAFT, List.of("spyglass"), 1);

	/** Preparations offered before each champion, by champion kind. */
	private static final Map<String, List<Preparation>> PREPARATIONS = Map.of(
		Champions.FORTRESS_CAPTAIN.id(), List.of(
			new Preparation(STANDS_ALONE, "thin_ranks", Goal.KILL, List.of("minecraft:wither_skeleton"), 8),
			SHARPEN, SPYGLASS),
		Champions.LOST_DELVER.id(), List.of(
			new Preparation(STANDS_ALONE, "light_dark", Goal.CRAFT, List.of("torch"), 32),
			SHARPEN,
			new Preparation(EXPOSED, "muffle", Goal.CRAFT, List.of("white_wool"), 4)),
		Champions.WARLORD.id(), List.of(
			new Preparation(STANDS_ALONE, "warn_villages", Goal.CUSTOM_STAT, List.of("traded_with_villager"), 10),
			SHARPEN, SPYGLASS)
	);

	public static MutableComponent describeEffect(String effect) {
		return switch (effect) {
			case STANDS_ALONE, WEAKENED, EXPOSED -> Text.t("prep.effect." + effect);
			default -> Component.literal(effect);
		};
	}

	/** Text is in the language file as bloodline.oath.<id>.name and .vow. */
	record Oath(String id, String legacy, List<Act> acts) {
		MutableComponent name() {
			return Text.t("oath." + id + ".name");
		}

		MutableComponent vow() {
			return Text.t("oath." + id + ".vow");
		}

		MutableComponent actName(int act) {
			return Text.t("oath." + id + ".act" + (act + 1) + ".name");
		}

		MutableComponent actIntro(int act) {
			return Text.t("oath." + id + ".act" + (act + 1) + ".intro");
		}

		MutableComponent stepTask(int act, int step) {
			return Text.t("oath." + id + ".act" + (act + 1) + ".step" + (step + 1));
		}
	}

	private static final List<Oath> OATHS = List.of(
		new Oath("sword", "oathbound", List.of(
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CUSTOM_STAT, List.of("mob_kills"), 30, Reward.levels(3))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CRAFT, List.of("iron_sword"), 1, Reward.none()),
					Questlines::swearBlade))),
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.DIMENSION, List.of("minecraft:the_nether"), 1, Reward.levels(2))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.KILL_WITH_OATHBLADE, List.of("minecraft:blaze"), 3, Reward.levels(4))))),
			new Act(List.of(
				new Step((p, l, t, k) -> championQuest(p, t, k, Champions.FORTRESS_CAPTAIN, "oath_sword",
					Level.NETHER, BuiltinStructures.FORTRESS, false, Reward.item(5, "netherite_scrap", 2))))),
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.KILL_WITH_OATHBLADE, List.of("minecraft:wither"), 1, Reward.levels(10)))))
		)),
		new Oath("deep", "deepborn", List.of(
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.DEPTH, List.of("-32"), 1, Reward.levels(2))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.MINE, List.of("diamond_ore", "deepslate_diamond_ore"), 3, Reward.levels(4))))),
			new Act(List.of(
				new Step((p, l, t, k) -> structureQuest(p, t, k, Level.OVERWORLD, BuiltinStructures.ANCIENT_CITY, true, Reward.levels(3))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.MINE, List.of("sculk_catalyst"), 2, Reward.levels(4))))),
			new Act(List.of(
				new Step((p, l, t, k) -> championQuest(p, t, k, Champions.LOST_DELVER, "oath_deep",
					Level.OVERWORLD, BuiltinStructures.ANCIENT_CITY, true, Reward.item(5, "echo_shard", 3))))),
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CRAFT, List.of("recovery_compass"), 1, Reward.levels(3))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.KILL, List.of("minecraft:warden"), 1, Reward.levels(10)))))
		)),
		new Oath("hearth", "beloved", List.of(
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CUSTOM_STAT, List.of("traded_with_villager"), 15, Reward.levels(3))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CUSTOM_STAT, List.of("animals_bred"), 10, Reward.item(2, "emerald", 8))))),
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.KILL, List.of("minecraft:pillager", "minecraft:vindicator"), 8, Reward.levels(3))),
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CUSTOM_STAT, List.of("raid_win"), 1, Reward.levels(5))))),
			new Act(List.of(
				new Step((p, l, t, k) -> championQuest(p, t, k, Champions.WARLORD, "oath_hearth",
					Level.OVERWORLD, BuiltinStructures.PILLAGER_OUTPOST, false, Reward.item(5, "totem_of_undying", 1))))),
			new Act(List.of(
				new Step((p, l, t, k) -> Quest.of(Type.QUESTLINE, t, k, Goal.CUSTOM_STAT, List.of("raid_win"), 2, Reward.levels(10)))))
		))
	);

	private static final Map<String, Champions.Kind> CHAMPIONS = Champions.ALL.stream()
		.collect(java.util.stream.Collectors.toMap(Champions.Kind::id, k -> k));

	private Questlines() {
	}

	private static Optional<Oath> oath(String id) {
		return OATHS.stream().filter(o -> o.id().equals(id)).findFirst();
	}

	// --- The Founder's Oath -------------------------------------------------------------------

	/** Asks a family that hasn't sworn an oath yet to choose one. */
	public static void offerOath(ServerPlayer player, Lineage lineage) {
		MutableComponent message = Text.t("oath.offer", lineage.house.name).withStyle(ChatFormatting.GRAY).append(" ");
		for (Oath oath : OATHS) {
			message.append(Component.literal("[").append(oath.name()).append("]").withStyle(s -> s.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.SuggestCommand("/bloodline oath " + oath.id()))
				.withHoverEvent(new HoverEvent.ShowText(Text.t("oath.quoted", oath.vow()).withStyle(ChatFormatting.ITALIC)
					.append("\n\n")
					.append(Text.t("oath.offer_hover", Traits.get(oath.legacy()).displayName()).withStyle(s2 -> s2.withItalic(false)))))));
			message.append(" ");
		}
		player.sendSystemMessage(message);
	}

	/** Points a family without an oath to their chronicle, where the oath is chosen. */
	public static void nudgeOath(ServerPlayer player, Lineage lineage) {
		Chronicle.ensure(player);
		if (!lineage.isHead(player.getUUID())) {
			player.sendSystemMessage(Text.t("oath.nudge_member", lineage.house.name)
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		player.sendSystemMessage(Text.t("oath.nudge_head", lineage.house.name)
			.withStyle(ChatFormatting.GOLD));
	}

	public static boolean swear(ServerPlayer player, Lineage lineage, String id) {
		Optional<Oath> oath = oath(id);
		if (oath.isEmpty() || lineage.house.oath.isPresent()) return false;
		lineage.house.oath = Optional.of(id);
		lineage.house.oathActs = 0;
		Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.oath_sworn", lineage.current.name(), oath.get().name(),
			lineage.house.name, oath.get().vow()));
		titleHouse(player, lineage, oath.get().name(), Text.t("oath.quoted", oath.get().vow()));
		startAct(player, lineage, oath.get(), 0);
		return true;
	}

	/** Starts the next act of the oath if this generation hasn't done one yet. */
	public static void resume(ServerPlayer player, Lineage lineage) {
		if (lineage.house.oath.isEmpty() || lineage.allQuests().stream().anyMatch(q -> q.line.filter(OATH::equals).isPresent())) return;
		Oath oath = oath(lineage.house.oath.get()).orElse(null);
		if (oath == null || lineage.house.oathActs >= oath.acts().size()) return;
		if (lineage.house.actPending) return;
		if (lineage.house.renown < Renown.forAct(lineage.house.oathActs)) return;
		startAct(player, lineage, oath, lineage.house.oathActs);
	}

	private static void startAct(ServerPlayer player, Lineage lineage, Oath oath, int act) {
		tell(player, lineage, Text.t("oath.act_header", oath.name(), Names.numeral(act + 1), oath.actName(act))
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		tell(player, lineage, oath.actIntro(act).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		startStep(player, lineage, oath, act, 0);
	}

	private static void startStep(ServerPlayer player, Lineage lineage, Oath oath, int act, int step) {
		Component title = Text.t("oath.step_title", oath.name(), Names.numeral(act + 1), oath.actName(act));
		Quest quest = oath.acts().get(act).steps().get(step).factory().create(player, lineage, title, oath.stepTask(act, step)).inLine(OATH, act, step);
		Quests.add(player, lineage, quest);
		tell(player, lineage, Text.t("quest.new_objective").withStyle(ChatFormatting.GRAY).append(" ").append(Quests.line(quest, true)));
		if (quest.goal == Goal.KILL_CHAMPION) addPreparations(player, lineage, quest.targets.get(1));
		Lineages.markDirty(player.level().getServer());
	}

	public static void onStepComplete(ServerPlayer player, Lineage lineage, Quest quest) {
		String line = quest.line.orElse("");
		if (line.startsWith(PREP_PREFIX)) {
			String effect = line.substring(PREP_PREFIX.length());
			if (!lineage.house.preparations.contains(effect)) lineage.house.preparations.add(effect);
			tell(player, lineage, Text.t("prep.complete", describeEffect(effect)).withStyle(ChatFormatting.GRAY));
			return;
		}
		if (line.equals(RECLAIM)) {
			finishReclaim(player, lineage, quest);
			return;
		}
		if (!line.equals(OATH) || lineage.house.oath.isEmpty()) return;
		Oath oath = oath(lineage.house.oath.get()).orElse(null);
		if (oath == null) return;
		Act act = oath.acts().get(quest.act);
		act.steps().get(quest.step).onComplete().run(player, lineage);
		if (quest.goal == Goal.KILL_CHAMPION) {
			// The fight is over: unfinished preparations no longer matter.
			lineage.house.quests.removeIf(q -> q.type == Type.PREPARATION);
			lineage.house.preparations.clear();
		}
		if (quest.step + 1 < act.steps().size()) {
			startStep(player, lineage, oath, quest.act, quest.step + 1);
			return;
		}

		MinecraftServer server = player.level().getServer();
		lineage.house.oathActs = quest.act + 1;
		lineage.house.actPending = true;
		// Finishing an act is what earns a family member the right to give their life for the house.
		lineage.fulfilledAct = true;
		Renown.change(player, lineage, Renown.actCompleted(), Text.t("renown.reason.act", Names.numeral(quest.act + 1)));
		Lineages.record(server, lineage, Text.t("chronicle.act_fulfilled", lineage.current.name(), Names.numeral(quest.act + 1), oath.name(),
			oath.actName(quest.act)));
		if (lineage.house.oathActs < oath.acts().size()) {
			titleHouse(player, lineage, Text.t("oath.act_fulfilled_title", Names.numeral(quest.act + 1)), oath.actName(quest.act));
			tell(player, lineage, Text.t("oath.next_act_waits").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			return;
		}

		Trait legacy = Traits.get(oath.legacy());
		lineage.house.legacies.add(legacy.id());
		Traits.apply(player, lineage.allTraits());
		Lineages.record(server, lineage, Text.t("chronicle.oath_fulfilled", lineage.current.generation(), lineage.house.name, oath.name(), legacy.name()));
		titleHouse(player, lineage, Text.t("oath.fulfilled_title"), Text.t("oath.fulfilled_subtitle", lineage.house.name, legacy.name()));
		server.getPlayerList().broadcastSystemMessage(Text.t("broadcast.oath_fulfilled", lineage.house.name, oath.name())
			.withStyle(ChatFormatting.GOLD), false);
		tell(player, lineage, Text.t("oath.legacy_gained", legacy.displayName())
			.append(" ").append(Text.t("oath.legacy_gained.note").withStyle(ChatFormatting.DARK_GRAY)));
		player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 0.8f);
	}

	private static final String PREP_PREFIX = "prep:";

	private static void addPreparations(ServerPlayer player, Lineage lineage, String championKind) {
		List<Preparation> preparations = PREPARATIONS.getOrDefault(championKind, List.of());
		if (preparations.isEmpty()) return;
		tell(player, lineage, Text.t("prep.intro")
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		for (Preparation prep : preparations) {
			Quest quest = Quest.of(Type.PREPARATION, Text.t("prep." + prep.key()), Text.t("prep." + prep.key() + ".task"),
				prep.goal(), prep.targets(), prep.required(), Reward.none());
			quest.line = Optional.of(PREP_PREFIX + prep.effect());
			Quests.add(player, lineage, quest);
			tell(player, lineage, Component.literal("  ⚒ ").withStyle(ChatFormatting.DARK_GRAY).append(Quests.line(quest, true))
				.append(Component.literal(" - ").append(describeEffect(prep.effect())).withStyle(ChatFormatting.DARK_GRAY)));
		}
	}

	public static String effectOf(Quest quest) {
		return quest.line.filter(l -> l.startsWith(PREP_PREFIX)).map(l -> l.substring(PREP_PREFIX.length())).orElse("");
	}

	private static void swearBlade(ServerPlayer player, Lineage lineage) {
		Heirlooms.swearBlade(player, lineage);
		player.sendSystemMessage(Text.t("oathblade.sworn", Text.t("oathblade.name").withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY));
		Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.oathblade", lineage.current.name()));
	}

	public static boolean isOathStep(Quest quest) {
		return quest.line.filter(OATH::equals).isPresent();
	}

	/**
	 * The chronicle's Main Quest section. Before an oath is sworn it describes each oath with a
	 * button to swear it; afterwards it shows the acts, the current act's story and objective.
	 */
	public static List<Component> bookPages(Lineage lineage, int firstPage, boolean viewerIsHead) {
		List<Component> pages = new java.util.ArrayList<>();
		Oath sworn = lineage.house.oath.flatMap(Questlines::oath).orElse(null);
		if (sworn == null) {
			MutableComponent intro = Chronicle.heading(Text.t("book.oath.title"))
				.append(Chronicle.body(Text.t("book.oath.intro"))).append("\n\n");
			for (int i = 0; i < OATHS.size(); i++) intro.append(Chronicle.link(OATHS.get(i).name(), firstPage + 1 + i));
			pages.add(intro);
			for (Oath oath : OATHS) {
				MutableComponent page = Component.empty().append(oath.name()).append("\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
					.append(Text.t("oath.quoted", oath.vow()).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(true).withBold(false)))
					.append("\n");
				for (int i = 0; i < oath.acts().size(); i++) {
					page.append(Chronicle.body(Component.literal(Names.numeral(i + 1) + ". ").append(oath.actName(i)))).append("\n");
				}
				page.append(Chronicle.faint(Text.t("book.oath.reward"))).append(" ")
					.append(Traits.get(oath.legacy()).displayName().withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(false)))
					.append("\n\n")
					.append(viewerIsHead
						? Chronicle.button(Text.t("button.swear"), "/bloodline oath " + oath.id(), Text.t("book.oath.swear_hover", oath.name()))
						: Chronicle.faint(Text.t("book.oath.head_only")));
				pages.add(page);
			}
			return pages;
		}

		MutableComponent overview = Chronicle.heading(sworn.name());
		for (int i = 0; i < sworn.acts().size(); i++) {
			String mark = i < lineage.house.oathActs ? "✔ " : i == lineage.house.oathActs ? "▶ " : "○ ";
			ChatFormatting color = i < lineage.house.oathActs ? ChatFormatting.DARK_GREEN : i == lineage.house.oathActs ? ChatFormatting.BLACK : ChatFormatting.GRAY;
			overview.append(Component.literal(mark + Names.numeral(i + 1) + ". ").append(sworn.actName(i)).append("\n")
				.withStyle(s -> s.withColor(color).withBold(false)));
		}
		overview.append("\n").append(Chronicle.faint(Text.t("book.oath.pace", lineage.house.renown))).append(" ")
			.append(Traits.get(sworn.legacy()).displayName().withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(false)));
		pages.add(overview);

		if (lineage.house.oathActs >= sworn.acts().size()) {
			pages.add(Chronicle.heading(Text.t("book.oath.fulfilled")).append(Chronicle.body(Text.t("book.oath.fulfilled_text", lineage.house.name,
				Traits.get(sworn.legacy()).name()))));
			return pages;
		}
		int currentAct = lineage.house.oathActs;
		Act act = sworn.acts().get(currentAct);
		pages.add(Chronicle.heading(Text.t("book.oath.act", Names.numeral(currentAct + 1), sworn.actName(currentAct)))
			.append(sworn.actIntro(currentAct).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(true).withBold(false))));
		Quest step = lineage.allQuests().stream().filter(Questlines::isOathStep).findFirst().orElse(null);
		MutableComponent objective = Chronicle.heading(Text.t("book.oath.objective"));
		for (var done : lineage.completed) {
			if (done.line().filter(OATH::equals).isPresent() && done.act() == currentAct) {
				objective.append(Component.literal("✔ ").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(false)))
					.append(Chronicle.struck(done.task()))
					.append("\n\n");
			}
		}
		if (step != null) {
			objective.append(step.task.copy().withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)));
			if (step.required > 1) {
				objective.append(Chronicle.faint("\n\n" + step.progress / step.displayScale + " / " + step.required / step.displayScale));
			}
			objective.append("\n\n").append(Chronicle.faint(Text.t("book.oath.step", step.step + 1, act.steps().size())));
			Component reward = Quests.describe(step.reward);
			if (!reward.getString().isEmpty()) objective.append("\n").append(Chronicle.faint(Text.t("quest.reward", reward)));
		} else if (lineage.house.actPending) {
			objective.append(Chronicle.body(Text.t("book.oath.wait_heir")));
		} else {
			objective.append(Chronicle.body(Text.t("book.oath.need_renown"))).append("\n\n")
				.append(Text.t("book.oath.renown_progress", lineage.house.renown, Renown.forAct(currentAct)).withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(false)))
				.append("\n\n").append(Chronicle.faint(Text.t("book.oath.renown_hint")));
		}
		pages.add(objective);

		List<Quest> preparations = lineage.allQuests().stream().filter(q -> q.type == Type.PREPARATION).toList();
		if (!preparations.isEmpty() || !lineage.house.preparations.isEmpty()) {
			MutableComponent prep = Chronicle.heading(Text.t("book.prep.title"));
			for (String effect : lineage.house.preparations) {
				prep.append(Component.literal("✔ ").append(describeEffect(effect)).append("\n").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(false)));
			}
			for (Quest q : preparations) {
				String progress = q.required > 1 ? " " + q.progress + "/" + q.required : "";
				prep.append(Component.literal("○ ").append(q.task).append(progress + "\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)
					.withHoverEvent(new HoverEvent.ShowText(describeEffect(effectOf(q))))));
			}
			prep.append("\n").append(Chronicle.faint(Text.t("book.prep.optional")));
			pages.add(prep);
		}
		return pages;
	}

	/** "Oath of the Sword: act 2 of 4", or empty if the house has sworn no oath. */
	public static MutableComponent oathSummary(Lineage lineage) {
		Oath oath = lineage.house.oath.flatMap(Questlines::oath).orElse(null);
		if (oath == null) return Component.empty();
		if (lineage.house.oathActs >= oath.acts().size()) return Text.t("oath.summary.done", oath.name());
		return Text.t("oath.summary", oath.name(), lineage.house.oathActs + 1, oath.acts().size());
	}

	// --- The Stolen Heirloom ------------------------------------------------------------------

	/** Starts a recovery quest for the oldest lost heirloom, if none is under way. */
	public static void tickReclaim(ServerPlayer player, Lineage lineage) {
		if (lineage.house.lostHeirlooms.isEmpty() || player.level().dimension() != Level.OVERWORLD) return;
		if (lineage.allQuests().stream().anyMatch(q -> q.line.filter(RECLAIM::equals).isPresent())) return;
		ItemStack lost = lineage.house.lostHeirlooms.getFirst();
		String name = Heirlooms.nameOf(lost);
		tell(player, lineage, Text.t("reclaim.rumour", lineage.house.name, Component.literal(name).withStyle(ChatFormatting.GOLD)).withStyle(ChatFormatting.GRAY));
		Quest quest = championQuest(player, Text.t("reclaim.title", name), Text.t("reclaim.task", name), Champions.THIEF,
			"reclaim_" + player.getRandom().nextInt(1_000_000), Level.OVERWORLD, BuiltinStructures.PILLAGER_OUTPOST, false, Reward.levels(3))
			.inLine(RECLAIM, 0, 0);
		Quests.add(player, lineage, quest);
		tell(player, lineage, Text.t("quest.new_objective").withStyle(ChatFormatting.GRAY).append(" ").append(Quests.line(quest, true)));
		Lineages.markDirty(player.level().getServer());
	}

	private static void finishReclaim(ServerPlayer player, Lineage lineage, Quest quest) {
		if (lineage.house.lostHeirlooms.isEmpty()) return;
		ItemStack heirloom = Heirlooms.reclaimed(lineage.house.lostHeirlooms.removeFirst(), lineage);
		if (!player.getInventory().add(heirloom)) player.spawnAtLocation(player.level(), heirloom);
		String name = Heirlooms.nameOf(heirloom);
		player.sendSystemMessage(Text.t("reclaim.done", Component.literal(name).withStyle(ChatFormatting.GOLD)));
		Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.reclaimed", lineage.current.name(), name));
	}

	/** Remembers a destroyed heirloom so the Stolen Heirloom questline can bring it back. */
	public static void onHeirloomLost(MinecraftServer server, ItemStack stack) {
		Heirlooms.lostOwner(stack).ifPresent(owner -> {
			Lineage lineage = io.github.fersyvanderzee.bloodline.data.BloodlineState.get(server).get(owner);
			if (lineage == null) return;
			lineage.house.lostHeirlooms.add(stack.copy());
			Lineages.markDirty(server);
		});
	}

	// --- Shared helpers -----------------------------------------------------------------------

	/**
	 * A quest to go to a real structure. If none can be found nearby, the quest falls back to just
	 * being in the right dimension. Overworld structures come with a map.
	 */
	private static Quest structureQuest(ServerPlayer player, Component title, Component task, ResourceKey<Level> dimension,
										ResourceKey<Structure> structure, boolean underground, Reward reward) {
		Optional<GlobalPos> place = locate(player, dimension, structure);
		List<String> targets = underground ? List.of("underground") : List.of();
		if (place.isEmpty()) {
			return Quest.of(Type.QUESTLINE, title, Text.t("place.search", task, dimensionName(dimension)), Goal.DIMENSION,
				List.of(dimension.identifier().toString()), 1, reward);
		}
		giveMap(player, place.get(), task);
		return Quest.of(Type.QUESTLINE, title, Text.t("place.near", task, coordinates(place.get(), underground)), Goal.VISIT, targets, 1, reward)
			.at(place.get(), STRUCTURE_RADIUS);
	}

	/**
	 * A quest to kill a champion. The champion appears when the player reaches the structure (or,
	 * if none could be found, anywhere in the right dimension), and again if they come back
	 * after it was lost.
	 */
	private static Quest championQuest(ServerPlayer player, Component title, Component task, Champions.Kind kind, String key,
									   ResourceKey<Level> dimension, ResourceKey<Structure> structure, boolean underground, Reward reward) {
		Optional<GlobalPos> place = locate(player, dimension, structure);
		List<String> targets = underground
			? List.of(key, kind.id(), dimension.identifier().toString(), "underground")
			: List.of(key, kind.id(), dimension.identifier().toString());
		Component full = place.<Component>map(p -> Text.t("place.near", task, coordinates(p, underground)))
			.orElseGet(() -> Text.t("place.search", task, dimensionName(dimension)));
		place.ifPresent(p -> giveMap(player, p, task));
		Quest quest = Quest.of(Type.QUESTLINE, title, full, Goal.KILL_CHAMPION, targets, 1, reward);
		return place.map(p -> quest.at(p, STRUCTURE_RADIUS)).orElse(quest);
	}

	/** Spawns the champion for a quest once the player is in the right place and it isn't already around. */
	public static void maybeSpawnChampion(ServerPlayer player, Quest quest) {
		if (quest.isDone() || quest.targets.size() < 3 || player.tickCount < 100) return;
		String key = quest.targets.get(0);
		Champions.Kind kind = CHAMPIONS.get(quest.targets.get(1));
		if (kind == null || !player.level().dimension().identifier().toString().equals(quest.targets.get(2))) return;
		if (quest.place.isPresent() ? !Quests.isAt(player, quest) : (quest.targets.contains("underground") && player.getBlockY() >= 0)) return;
		boolean present = !player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(CHAMPION_SEARCH_RANGE),
			m -> m.entityTags().contains("bloodline_key=" + key)).isEmpty();
		if (present) return;
		Optional<ItemStack> weapon = Optional.empty();
		Lineage lineage = Lineages.get(player);
		if (lineage == null) return;
		if (quest.line.filter(RECLAIM::equals).isPresent() && !lineage.house.lostHeirlooms.isEmpty()) {
			weapon = Optional.of(lineage.house.lostHeirlooms.getFirst());
		}
		List<String> preparations = isOathStep(quest) ? List.copyOf(lineage.house.preparations) : List.of();
		Champions.spawn(player, kind, key, weapon, preparations);
		for (String effect : preparations) {
			player.sendSystemMessage(Text.t("prep.pays_off", describeEffect(effect)).withStyle(ChatFormatting.GREEN));
		}
	}

	static Optional<GlobalPos> locate(ServerPlayer player, ResourceKey<Level> dimension, ResourceKey<Structure> structure) {
		return locate(player, dimension, structure, false);
	}

	/** {@code skipKnown} skips structures already found or pointed to by maps, like vanilla treasure maps. */
	static Optional<GlobalPos> locate(ServerPlayer player, ResourceKey<Level> dimension, ResourceKey<Structure> structure, boolean skipKnown) {
		MinecraftServer server = player.level().getServer();
		ServerLevel level = server.getLevel(dimension);
		if (level == null) return Optional.empty();
		BlockPos origin = player.blockPosition();
		if (player.level().dimension() == Level.OVERWORLD && dimension == Level.NETHER) {
			origin = new BlockPos(origin.getX() / 8, origin.getY(), origin.getZ() / 8);
		} else if (player.level().dimension() == Level.NETHER && dimension == Level.OVERWORLD) {
			origin = new BlockPos(origin.getX() * 8, origin.getY(), origin.getZ() * 8);
		} else if (player.level().dimension() != dimension) {
			origin = BlockPos.ZERO;
		}
		int radius = skipKnown ? BloodlineConfig.get().structures.rumourSearchRadiusChunks : BloodlineConfig.get().structures.searchRadiusChunks;
		return StructureSearch.find(level, structure, origin, radius, skipKnown).map(pos -> GlobalPos.of(dimension, pos));
	}

	static void giveMap(ServerPlayer player, GlobalPos place, Component purpose) {
		if (place.dimension() != Level.OVERWORLD) return;
		ServerLevel level = player.level().getServer().overworld();
		ItemStack map = MapItem.create(level, place.pos().getX(), place.pos().getZ(), (byte) 2, true, true);
		MapItem.renderBiomePreviewMap(level, map);
		MapItemSavedData.addTargetDecoration(map, place.pos(), "+", MapDecorationTypes.RED_X);
		map.set(DataComponents.ITEM_NAME, Text.t("map.name", purpose));
		if (!player.getInventory().add(map)) player.spawnAtLocation(player.level(), map);
	}

	private static Component coordinates(GlobalPos place, boolean underground) {
		String where = place.pos().getX() + ", " + place.pos().getZ();
		MutableComponent text = place.dimension() == Level.NETHER ? Text.t("place.nether", where) : Component.literal(where);
		return underground ? Text.t("place.deep", text) : text;
	}

	private static Component dimensionName(ResourceKey<Level> dimension) {
		if (dimension == Level.NETHER) return Text.t("dimension.nether");
		if (dimension == Level.END) return Text.t("dimension.end");
		return Text.t("dimension.overworld");
	}

	/** Oath news concerns the whole house, so it goes to every online member. */
	private static void tell(ServerPlayer player, Lineage lineage, Component message) {
		Lineages.tellHouse(player.level().getServer(), lineage.house, message);
	}

	private static void titleHouse(ServerPlayer player, Lineage lineage, Component title, Component subtitle) {
		Lineages.onlineMembers(player.level().getServer(), lineage.house).forEach(member -> title(member, title, subtitle));
	}

	private static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(title.copy().withStyle(ChatFormatting.GOLD)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle.copy().withStyle(ChatFormatting.GRAY)));
	}
}
