package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Reward;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
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
	private static final int SEARCH_RADIUS_CHUNKS = 100;
	private static final int STRUCTURE_RADIUS = 48;
	private static final double CHAMPION_SEARCH_RANGE = 64;

	@FunctionalInterface
	interface StepFactory {
		Quest create(ServerPlayer player, Lineage lineage, String title);
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

	/** {@code renown} is how much renown the house needs before the act can begin. */
	record Act(int renown, String name, String intro, List<Step> steps) {
	}

	/** Optional groundwork before a champion fight; {@code effect} is what it does to the champion. */
	record Preparation(String effect, String title, String task, Goal goal, List<String> targets, int required) {
	}

	public static final String STANDS_ALONE = "alone";
	public static final String WEAKENED = "weakened";
	public static final String EXPOSED = "exposed";

	private static final Preparation SHARPEN = new Preparation(WEAKENED, "Sharpen your edge", "Enchant an item", Goal.CUSTOM_STAT, List.of("enchant_item"), 1);
	private static final Preparation SPYGLASS = new Preparation(EXPOSED, "Watch from afar", "Craft a spyglass", Goal.CRAFT, List.of("spyglass"), 1);

	/** Preparations offered before each champion, by champion kind. */
	private static final Map<String, List<Preparation>> PREPARATIONS = Map.of(
		Champions.FORTRESS_CAPTAIN.id(), List.of(
			new Preparation(STANDS_ALONE, "Thin their ranks", "Slay Wither Skeletons", Goal.KILL, List.of("minecraft:wither_skeleton"), 8),
			SHARPEN, SPYGLASS),
		Champions.LOST_DELVER.id(), List.of(
			new Preparation(STANDS_ALONE, "Light the dark", "Craft torches", Goal.CRAFT, List.of("torch"), 32),
			SHARPEN,
			new Preparation(EXPOSED, "Muffle your steps", "Craft wool", Goal.CRAFT, List.of("white_wool"), 4)),
		Champions.WARLORD.id(), List.of(
			new Preparation(STANDS_ALONE, "Warn the villages", "Trade with villagers", Goal.CUSTOM_STAT, List.of("traded_with_villager"), 10),
			SHARPEN, SPYGLASS)
	);

	public static String describeEffect(String effect) {
		return switch (effect) {
			case STANDS_ALONE -> "the champion faces you without bodyguards";
			case WEAKENED -> "the champion has 30% less health";
			case EXPOSED -> "the champion glows and moves slowly";
			default -> effect;
		};
	}

	record Oath(String id, String name, String vow, String legacy, List<Act> acts) {
	}

	private static final List<Oath> OATHS = List.of(
		new Oath("sword", "Oath of the Sword", "We will carry one blade through every generation, until it fells the Wither.", "oathbound", List.of(
			new Act(0, "The First Blade", "Every oath begins with a blade. Prove your arm, then forge the sword your family will carry.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Slay monsters", Goal.CUSTOM_STAT, List.of("mob_kills"), 30, Reward.levels(3))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Craft an iron sword to swear upon", Goal.CRAFT, List.of("iron_sword"), 1, Reward.none()),
					Questlines::swearBlade))),
			new Act(10, "Trial by Fire", "The Oathblade must be tempered where the world burns.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Carry the Oathblade into the Nether", Goal.DIMENSION, List.of("minecraft:the_nether"), 1, Reward.levels(2))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Slay Blazes with the Oathblade", Goal.KILL_WITH_OATHBLADE, List.of("minecraft:blaze"), 3, Reward.levels(4))))),
			new Act(25, "The Fortress Captain", "A captain of the wither-bones holds a fortress in the Nether. It must fall to the Oathblade.", List.of(
				new Step((p, l, t) -> championQuest(p, t, "Defeat the Captain of the Fortress", Champions.FORTRESS_CAPTAIN, "oath_sword",
					Level.NETHER, BuiltinStructures.FORTRESS, false, Reward.item(5, "netherite_scrap", 2))))),
			new Act(45, "Oathfulfilled", "Only the Wither remains. Let the Oathblade strike the final blow.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Slay the Wither with the Oathblade", Goal.KILL_WITH_OATHBLADE, List.of("minecraft:wither"), 1, Reward.levels(10)))))
		)),
		new Oath("deep", "Oath of the Deep", "We will dig until we find what the old ones buried, and silence the Warden.", "deepborn", List.of(
			new Act(0, "Strike the Earth", "The family's fortune lies below. Dig deep.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Descend below Y -32", Goal.DEPTH, List.of("-32"), 1, Reward.levels(2))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Mine diamond ore", Goal.MINE, List.of("diamond_ore", "deepslate_diamond_ore"), 3, Reward.levels(4))))),
			new Act(10, "The Silent City", "Somewhere in the deep dark lies a city of the old ones. Walk its halls without waking the Warden.", List.of(
				new Step((p, l, t) -> structureQuest(p, t, "Find the ancient city", Level.OVERWORLD, BuiltinStructures.ANCIENT_CITY, true, Reward.levels(3))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Break sculk catalysts", Goal.MINE, List.of("sculk_catalyst"), 2, Reward.levels(4))))),
			new Act(25, "The Lost Delver", "A delver of another house never came home from the deep. What is left of them still guards the city.", List.of(
				new Step((p, l, t) -> championQuest(p, t, "Defeat the Lost Delver in the ancient city", Champions.LOST_DELVER, "oath_deep",
					Level.OVERWORLD, BuiltinStructures.ANCIENT_CITY, true, Reward.item(5, "echo_shard", 3))))),
			new Act(45, "Echoes", "Gather the echoes of the deep, then face the thing that guards them.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Craft a recovery compass", Goal.CRAFT, List.of("recovery_compass"), 1, Reward.levels(3))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Slay a Warden", Goal.KILL, List.of("minecraft:warden"), 1, Reward.levels(10)))))
		)),
		new Oath("hearth", "Oath of the Hearth", "We will stand between the villages and those who would burn them.", "beloved", List.of(
			new Act(0, "Friend of the Village", "A house is nothing without neighbours.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Trade with villagers", Goal.CUSTOM_STAT, List.of("traded_with_villager"), 15, Reward.levels(3))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Breed animals", Goal.CUSTOM_STAT, List.of("animals_bred"), 10, Reward.item(2, "emerald", 8))))),
			new Act(10, "Defender", "Pillagers threaten the villages. Stand between them and the innocent.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Slay pillagers or vindicators", Goal.KILL, List.of("minecraft:pillager", "minecraft:vindicator"), 8, Reward.levels(3))),
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Win a raid", Goal.CUSTOM_STAT, List.of("raid_win"), 1, Reward.levels(5))))),
			new Act(25, "The Warlord", "The raids are led from an outpost. Its warlord must answer to your house.", List.of(
				new Step((p, l, t) -> championQuest(p, t, "Defeat the Warlord of the Outpost", Champions.WARLORD, "oath_hearth",
					Level.OVERWORLD, BuiltinStructures.PILLAGER_OUTPOST, false, Reward.item(5, "totem_of_undying", 1))))),
			new Act(45, "Hero of the Realm", "Become the shield the realm remembers.", List.of(
				new Step((p, l, t) -> Quest.of(Type.QUESTLINE, t, "Win two more raids", Goal.CUSTOM_STAT, List.of("raid_win"), 2, Reward.levels(10)))))
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
		MutableComponent message = Component.literal("House " + lineage.house + " has sworn no oath. Choose one: ").withStyle(ChatFormatting.GRAY);
		for (Oath oath : OATHS) {
			message.append(Component.literal("[" + oath.name() + "]").withStyle(s -> s.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.SuggestCommand("/bloodline oath " + oath.id()))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal("\"" + oath.vow() + "\"\n\n").withStyle(ChatFormatting.ITALIC)
					.append(Component.literal("Four acts, at most one per generation.\nReward: the legendary trait ").withStyle(s2 -> s2.withItalic(false)))
					.append(Traits.get(oath.legacy()).displayName())))));
			message.append(" ");
		}
		player.sendSystemMessage(message);
	}

	/** Points a family without an oath to their chronicle, where the oath is chosen. */
	public static void nudgeOath(ServerPlayer player, Lineage lineage) {
		Chronicle.ensure(player);
		player.sendSystemMessage(Component.literal("House " + lineage.house + " has sworn no oath. Open your Chronicle to choose one.")
			.withStyle(ChatFormatting.GOLD));
	}

	public static boolean swear(ServerPlayer player, Lineage lineage, String id) {
		Optional<Oath> oath = oath(id);
		if (oath.isEmpty() || lineage.oath.isPresent()) return false;
		lineage.oath = Optional.of(id);
		lineage.oathActs = 0;
		Lineages.record(player.level().getServer(), lineage, Component.literal(lineage.current.name() + " swore the " + oath.get().name()
			+ " on behalf of House " + lineage.house + ": \"" + oath.get().vow() + "\""));
		title(player, oath.get().name(), "\"" + oath.get().vow() + "\"");
		startAct(player, lineage, oath.get(), 0);
		return true;
	}

	/** Starts the next act of the oath if this generation hasn't done one yet. */
	public static void resume(ServerPlayer player, Lineage lineage) {
		if (lineage.oath.isEmpty() || lineage.quests.stream().anyMatch(q -> q.line.filter(OATH::equals).isPresent())) return;
		Oath oath = oath(lineage.oath.get()).orElse(null);
		if (oath == null || lineage.oathActs >= oath.acts().size()) return;
		if (lineage.oathActs > 0 && lineage.oathActGeneration >= lineage.current.generation()) return;
		Act next = oath.acts().get(lineage.oathActs);
		if (lineage.renown < next.renown()) return;
		startAct(player, lineage, oath, lineage.oathActs);
	}

	private static void startAct(ServerPlayer player, Lineage lineage, Oath oath, int act) {
		player.sendSystemMessage(Component.literal(oath.name() + ", Act " + Names.numeral(act + 1) + ": " + oath.acts().get(act).name())
			.withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		player.sendSystemMessage(Component.literal(oath.acts().get(act).intro()).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		startStep(player, lineage, oath, act, 0);
	}

	private static void startStep(ServerPlayer player, Lineage lineage, Oath oath, int act, int step) {
		String title = oath.name() + " " + Names.numeral(act + 1) + ": " + oath.acts().get(act).name();
		Quest quest = oath.acts().get(act).steps().get(step).factory().create(player, lineage, title).inLine(OATH, act, step);
		Quests.add(player, lineage, quest);
		player.sendSystemMessage(Component.literal("New objective: ").withStyle(ChatFormatting.GRAY).append(Quests.line(quest, true)));
		if (quest.goal == Goal.KILL_CHAMPION) addPreparations(player, lineage, quest.targets.get(1));
		Lineages.markDirty(player.level().getServer());
	}

	public static void onStepComplete(ServerPlayer player, Lineage lineage, Quest quest) {
		String line = quest.line.orElse("");
		if (line.startsWith(PREP_PREFIX)) {
			String effect = line.substring(PREP_PREFIX.length());
			if (!lineage.preparations.contains(effect)) lineage.preparations.add(effect);
			player.sendSystemMessage(Component.literal("Preparation complete: " + describeEffect(effect) + ".").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (line.equals(RECLAIM)) {
			finishReclaim(player, lineage, quest);
			return;
		}
		if (!line.equals(OATH) || lineage.oath.isEmpty()) return;
		Oath oath = oath(lineage.oath.get()).orElse(null);
		if (oath == null) return;
		Act act = oath.acts().get(quest.act);
		act.steps().get(quest.step).onComplete().run(player, lineage);
		if (quest.goal == Goal.KILL_CHAMPION) {
			// The fight is over: unfinished preparations no longer matter.
			lineage.quests.removeIf(q -> q.type == Type.PREPARATION);
			lineage.preparations.clear();
		}
		if (quest.step + 1 < act.steps().size()) {
			startStep(player, lineage, oath, quest.act, quest.step + 1);
			return;
		}

		MinecraftServer server = player.level().getServer();
		lineage.oathActs = quest.act + 1;
		lineage.oathActGeneration = lineage.current.generation();
		Renown.change(player, lineage, Renown.ACT_COMPLETED, "for fulfilling Act " + Names.numeral(quest.act + 1));
		Lineages.record(server, lineage, Component.literal(lineage.current.name() + " fulfilled Act " + Names.numeral(quest.act + 1)
			+ " of the " + oath.name() + ": " + act.name() + "."));
		if (lineage.oathActs < oath.acts().size()) {
			title(player, "Act " + Names.numeral(quest.act + 1) + " fulfilled", act.name());
			player.sendSystemMessage(Component.literal("The next act of the oath must fall to your heir.").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
			return;
		}

		Trait legacy = Traits.get(oath.legacy());
		lineage.legacies.add(legacy.id());
		Traits.apply(player, lineage.allTraits());
		Lineages.record(server, lineage, Component.literal("After " + (lineage.current.generation()) + " generations, House " + lineage.house
			+ " fulfilled the " + oath.name() + ". Every child of the house will be born " + legacy.name() + "."));
		title(player, "Oath fulfilled", "House " + lineage.house + " is " + legacy.name());
		server.getPlayerList().broadcastSystemMessage(Component.literal("House " + lineage.house + " has fulfilled the " + oath.name() + "!")
			.withStyle(ChatFormatting.GOLD), false);
		player.sendSystemMessage(Component.literal("Legendary trait: ").append(legacy.displayName())
			.append(Component.literal(" (every heir is born with it)").withStyle(ChatFormatting.DARK_GRAY)));
		player.level().playSound(null, player.blockPosition(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 0.8f);
	}

	private static final String PREP_PREFIX = "prep:";

	private static void addPreparations(ServerPlayer player, Lineage lineage, String championKind) {
		List<Preparation> preparations = PREPARATIONS.getOrDefault(championKind, List.of());
		if (preparations.isEmpty()) return;
		player.sendSystemMessage(Component.literal("Before the fight, you can prepare. Each preparation weakens the champion:")
			.withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
		for (Preparation prep : preparations) {
			Quest quest = Quest.of(Type.PREPARATION, prep.title(), prep.task(), prep.goal(), prep.targets(), prep.required(), Reward.none());
			quest.line = Optional.of(PREP_PREFIX + prep.effect());
			Quests.add(player, lineage, quest);
			player.sendSystemMessage(Component.literal("  ⚒ ").withStyle(ChatFormatting.DARK_GRAY).append(Quests.line(quest, true))
				.append(Component.literal(" - " + describeEffect(prep.effect())).withStyle(ChatFormatting.DARK_GRAY)));
		}
	}

	public static String effectOf(Quest quest) {
		return quest.line.filter(l -> l.startsWith(PREP_PREFIX)).map(l -> l.substring(PREP_PREFIX.length())).orElse("");
	}

	private static void swearBlade(ServerPlayer player, Lineage lineage) {
		Heirlooms.swearBlade(player, lineage);
		player.sendSystemMessage(Component.literal("You swear the oath upon the blade. It is now the ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("Oathblade").withStyle(ChatFormatting.GOLD))
			.append(Component.literal(", and will stay with the family through every death.").withStyle(ChatFormatting.GRAY)));
		Lineages.record(player.level().getServer(), lineage, Component.literal(lineage.current.name() + " forged the Oathblade."));
	}

	public static boolean isOathStep(Quest quest) {
		return quest.line.filter(OATH::equals).isPresent();
	}

	/**
	 * The chronicle's Main Quest section. Before an oath is sworn it describes each oath with a
	 * button to swear it; afterwards it shows the acts, the current act's story and objective.
	 */
	public static List<Component> bookPages(Lineage lineage, int firstPage) {
		List<Component> pages = new java.util.ArrayList<>();
		Oath sworn = lineage.oath.flatMap(Questlines::oath).orElse(null);
		if (sworn == null) {
			MutableComponent intro = Chronicle.heading("The Founder's Oath")
				.append(Chronicle.body("Every great house is bound by an oath. Choose one, and your bloodline will carry it until it is fulfilled.\n\n"));
			for (int i = 0; i < OATHS.size(); i++) intro.append(Chronicle.link(OATHS.get(i).name(), firstPage + 1 + i));
			pages.add(intro);
			for (Oath oath : OATHS) {
				MutableComponent page = Component.literal(oath.name() + "\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
					.append(Component.literal("\"" + oath.vow() + "\"\n").withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(true).withBold(false)));
				for (int i = 0; i < oath.acts().size(); i++) {
					page.append(Chronicle.body(Names.numeral(i + 1) + ". " + oath.acts().get(i).name() + "\n"));
				}
				page.append(Chronicle.faint("Reward: ")).append(Traits.get(oath.legacy()).displayName().withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(false)))
					.append("\n\n")
					.append(Chronicle.button("Swear this oath", "/bloodline oath " + oath.id(),
						Component.literal("Swear the " + oath.name() + ". This can't be undone.")));
				pages.add(page);
			}
			return pages;
		}

		MutableComponent overview = Chronicle.heading(sworn.name());
		for (int i = 0; i < sworn.acts().size(); i++) {
			String mark = i < lineage.oathActs ? "✔ " : i == lineage.oathActs ? "▶ " : "○ ";
			ChatFormatting color = i < lineage.oathActs ? ChatFormatting.DARK_GREEN : i == lineage.oathActs ? ChatFormatting.BLACK : ChatFormatting.GRAY;
			overview.append(Component.literal(mark + Names.numeral(i + 1) + ". " + sworn.acts().get(i).name() + "\n")
				.withStyle(s -> s.withColor(color).withBold(false)));
		}
		overview.append(Chronicle.faint("\nOne act per generation.\nRenown: " + lineage.renown + "\nReward: "))
			.append(Traits.get(sworn.legacy()).displayName().withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(false)));
		pages.add(overview);

		if (lineage.oathActs >= sworn.acts().size()) {
			pages.add(Chronicle.heading("Fulfilled").append(Chronicle.body("House " + lineage.house + " kept its oath. Every child of the house is born "
				+ Traits.get(sworn.legacy()).name() + ".")));
			return pages;
		}
		Act act = sworn.acts().get(lineage.oathActs);
		pages.add(Chronicle.heading("Act " + Names.numeral(lineage.oathActs + 1) + ": " + act.name())
			.append(Component.literal(act.intro()).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withItalic(true).withBold(false))));
		Quest step = lineage.quests.stream().filter(Questlines::isOathStep).findFirst().orElse(null);
		MutableComponent objective = Chronicle.heading("Objective");
		int currentAct = lineage.oathActs;
		for (var done : lineage.completed) {
			if (done.line().filter(OATH::equals).isPresent() && done.act() == currentAct) {
				objective.append(Component.literal("✔ ").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(false)))
					.append(Chronicle.struck(done.task().getString()))
					.append("\n\n");
			}
		}
		if (step != null) {
			objective.append(step.task.copy().withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)));
			if (step.required > 1) {
				objective.append(Chronicle.faint("\n\n" + step.progress / step.displayScale + " / " + step.required / step.displayScale));
			}
			String reward = Quests.describe(step.reward);
			objective.append(Chronicle.faint("\n\nStep " + (step.step + 1) + " of " + act.steps().size() + (reward.isEmpty() ? "" : "\nReward: " + reward)));
		} else if (lineage.oathActs > 0 && lineage.oathActGeneration >= lineage.current.generation()) {
			objective.append(Chronicle.body("This generation has done its part. The next act falls to your heir."));
		} else {
			objective.append(Chronicle.body("The house is not yet renowned enough to begin this act.\n\n"))
				.append(Component.literal("Renown: " + lineage.renown + " / " + act.renown()).withStyle(s -> s.withColor(ChatFormatting.GOLD).withBold(false)))
				.append(Chronicle.faint("\n\nFulfil duties, settle blood debts and reclaim heirlooms to earn renown."));
		}
		pages.add(objective);

		List<Quest> preparations = lineage.quests.stream().filter(q -> q.type == Type.PREPARATION).toList();
		if (!preparations.isEmpty() || !lineage.preparations.isEmpty()) {
			MutableComponent prep = Chronicle.heading("Preparations");
			for (String effect : lineage.preparations) {
				prep.append(Component.literal("✔ " + describeEffect(effect) + "\n").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(false)));
			}
			for (Quest q : preparations) {
				String progress = q.required > 1 ? " " + q.progress + "/" + q.required : "";
				prep.append(Component.literal("○ " + q.task.getString() + progress + "\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)
					.withHoverEvent(new HoverEvent.ShowText(Component.literal(describeEffect(effectOf(q)))))));
			}
			prep.append(Chronicle.faint("\nOptional. Each one weakens the champion."));
			pages.add(prep);
		}
		return pages;
	}

	public static String oathSummary(Lineage lineage) {
		if (lineage.oath.isEmpty()) return "";
		Oath oath = oath(lineage.oath.get()).orElse(null);
		if (oath == null) return "";
		if (lineage.oathActs >= oath.acts().size()) return "\n" + oath.name() + ": fulfilled";
		return "\n" + oath.name() + ": act " + (lineage.oathActs + 1) + " of " + oath.acts().size();
	}

	// --- The Stolen Heirloom ------------------------------------------------------------------

	/** Starts a recovery quest for the oldest lost heirloom, if none is under way. */
	public static void tickReclaim(ServerPlayer player, Lineage lineage) {
		if (lineage.lostHeirlooms.isEmpty() || player.level().dimension() != Level.OVERWORLD) return;
		if (lineage.quests.stream().anyMatch(q -> q.line.filter(RECLAIM::equals).isPresent())) return;
		ItemStack lost = lineage.lostHeirlooms.getFirst();
		String name = Heirlooms.nameOf(lost);
		player.sendSystemMessage(Component.literal("Rumours reach House " + lineage.house + ": ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(name).withStyle(ChatFormatting.GOLD))
			.append(Component.literal(" was not destroyed. Pillagers were seen carrying a weapon with the family's mark...").withStyle(ChatFormatting.GRAY)));
		Quest quest = championQuest(player, "The Stolen Heirloom: " + name, "Take " + name + " back from the thief", Champions.THIEF,
			"reclaim_" + player.getRandom().nextInt(1_000_000), Level.OVERWORLD, BuiltinStructures.PILLAGER_OUTPOST, false, Reward.levels(3))
			.inLine(RECLAIM, 0, 0);
		Quests.add(player, lineage, quest);
		player.sendSystemMessage(Component.literal("New objective: ").withStyle(ChatFormatting.GRAY).append(Quests.line(quest, true)));
		Lineages.markDirty(player.level().getServer());
	}

	private static void finishReclaim(ServerPlayer player, Lineage lineage, Quest quest) {
		if (lineage.lostHeirlooms.isEmpty()) return;
		ItemStack heirloom = Heirlooms.reclaimed(lineage.lostHeirlooms.removeFirst(), lineage);
		if (!player.getInventory().add(heirloom)) player.spawnAtLocation(player.level(), heirloom);
		String name = Heirlooms.nameOf(heirloom);
		player.sendSystemMessage(Component.literal("You take back ").append(Component.literal(name).withStyle(ChatFormatting.GOLD))
			.append(". It is home again."));
		Lineages.record(player.level().getServer(), lineage, Component.literal(lineage.current.name() + " took " + name + " back from the thieves."));
	}

	/** Remembers a destroyed heirloom so the Stolen Heirloom questline can bring it back. */
	public static void onHeirloomLost(MinecraftServer server, ItemStack stack) {
		Heirlooms.lostOwner(stack).ifPresent(owner -> {
			Lineage lineage = io.github.fersyvanderzee.bloodline.data.BloodlineState.get(server).get(owner);
			if (lineage == null) return;
			lineage.lostHeirlooms.add(stack.copy());
			Lineages.markDirty(server);
		});
	}

	// --- Shared helpers -----------------------------------------------------------------------

	/**
	 * A quest to go to a real structure. If none can be found nearby, the quest falls back to just
	 * being in the right dimension. Overworld structures come with a map.
	 */
	private static Quest structureQuest(ServerPlayer player, String title, String task, ResourceKey<Level> dimension,
										ResourceKey<Structure> structure, boolean underground, Reward reward) {
		Optional<GlobalPos> place = locate(player, dimension, structure);
		List<String> targets = underground ? List.of("underground") : List.of();
		if (place.isEmpty()) {
			return Quest.of(Type.QUESTLINE, title, task + " (search " + dimensionName(dimension) + ")", Goal.DIMENSION,
				List.of(dimension.identifier().toString()), 1, reward);
		}
		giveMap(player, place.get(), task);
		return Quest.of(Type.QUESTLINE, title, task + " near " + coordinates(place.get(), underground), Goal.VISIT, targets, 1, reward)
			.at(place.get(), STRUCTURE_RADIUS);
	}

	/**
	 * A quest to kill a champion. The champion appears when the player reaches the structure (or,
	 * if none could be found, anywhere in the right dimension), and again if they come back
	 * after it was lost.
	 */
	private static Quest championQuest(ServerPlayer player, String title, String task, Champions.Kind kind, String key,
									   ResourceKey<Level> dimension, ResourceKey<Structure> structure, boolean underground, Reward reward) {
		Optional<GlobalPos> place = locate(player, dimension, structure);
		List<String> targets = underground
			? List.of(key, kind.id(), dimension.identifier().toString(), "underground")
			: List.of(key, kind.id(), dimension.identifier().toString());
		String where = place.map(p -> " near " + coordinates(p, underground)).orElse(" (search " + dimensionName(dimension) + ")");
		place.ifPresent(p -> giveMap(player, p, task));
		Quest quest = Quest.of(Type.QUESTLINE, title, task + where, Goal.KILL_CHAMPION, targets, 1, reward);
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
		if (quest.line.filter(RECLAIM::equals).isPresent() && !lineage.lostHeirlooms.isEmpty()) {
			weapon = Optional.of(lineage.lostHeirlooms.getFirst());
		}
		List<String> preparations = isOathStep(quest) ? List.copyOf(lineage.preparations) : List.of();
		Champions.spawn(player, kind, key, weapon, preparations);
		for (String effect : preparations) {
			player.sendSystemMessage(Component.literal("Your preparation pays off: " + describeEffect(effect) + ".").withStyle(ChatFormatting.GREEN));
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
		var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(structure);
		if (holder.isEmpty()) return Optional.empty();
		BlockPos found = level.findNearestMapStructure(HolderSet.direct(holder.get()), origin, SEARCH_RADIUS_CHUNKS, skipKnown);
		return Optional.ofNullable(found).map(pos -> GlobalPos.of(dimension, pos));
	}

	static void giveMap(ServerPlayer player, GlobalPos place, String purpose) {
		if (place.dimension() != Level.OVERWORLD) return;
		ServerLevel level = player.level().getServer().overworld();
		ItemStack map = MapItem.create(level, place.pos().getX(), place.pos().getZ(), (byte) 2, true, true);
		MapItem.renderBiomePreviewMap(level, map);
		MapItemSavedData.addTargetDecoration(map, place.pos(), "+", MapDecorationTypes.RED_X);
		map.set(DataComponents.ITEM_NAME, Component.literal("Map: " + purpose));
		if (!player.getInventory().add(map)) player.spawnAtLocation(player.level(), map);
	}

	private static String coordinates(GlobalPos place, boolean underground) {
		String where = place.pos().getX() + ", " + place.pos().getZ();
		if (place.dimension() == Level.NETHER) where += " in the Nether";
		return underground ? where + ", deep below" : where;
	}

	private static String dimensionName(ResourceKey<Level> dimension) {
		if (dimension == Level.NETHER) return "the Nether";
		if (dimension == Level.END) return "the End";
		return "the Overworld";
	}

	private static void title(ServerPlayer player, String title, String subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
		player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(title).withStyle(ChatFormatting.GOLD)));
		player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle).withStyle(ChatFormatting.GRAY)));
	}
}
