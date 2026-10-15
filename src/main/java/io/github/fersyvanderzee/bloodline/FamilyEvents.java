package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.House;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.PendingEvent;
import io.github.fersyvanderzee.bloodline.data.Quest;
import io.github.fersyvanderzee.bloodline.data.Quest.Goal;
import io.github.fersyvanderzee.bloodline.data.Quest.Reward;
import io.github.fersyvanderzee.bloodline.data.Quest.Type;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Family events: every few days something happens to a house, so the world doesn't wait for the
 * player to start everything. Some events need a choice (a rival's feud, a wedding proposal, a
 * cousin at the door), answered by any member with clickable buttons; others start a duty right
 * away (a sickness, a restless spirit).
 */
public final class FamilyEvents {
	static final String LINE_PREFIX = "event:";
	private static final String SICKNESS = "sickness";
	private static final String FEUD = "feud";
	private static final String WEDDING = "wedding";
	private static final String COUSIN = "cousin";
	private static final String RESTLESS = "restless";
	private static final int TRIBUTE = 8;
	private static final int DOWRY = 5;
	private static final int ANSWER_DAYS = 2;
	private static final int SICK_EFFECT_TICKS = 300;

	private FamilyEvents() {
	}

	// --- Scheduling ---------------------------------------------------------------------------

	/** Runs every few seconds per player: keeps the sick unwell, and starts the house's next event when it's due. */
	public static void tick(ServerPlayer player, Lineage lineage) {
		if (lineage.sick) {
			player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, SICK_EFFECT_TICKS, 0, true, false, true));
			player.addEffect(new MobEffectInstance(MobEffects.HUNGER, SICK_EFFECT_TICKS, 0, true, false, true));
		}
		BloodlineConfig.Events settings = BloodlineConfig.get().events;
		if (!settings.enabled || lineage.awaitingHeir) return;
		House house = lineage.house;
		MinecraftServer server = player.level().getServer();
		int today = Lineages.today(server);
		if (house.nextEventDay == 0) {
			house.nextEventDay = today + settings.intervalDays;
			Lineages.markDirty(server);
			return;
		}
		if (house.pendingEvent.isPresent()) {
			PendingEvent waiting = house.pendingEvent.get();
			if (waiting.expiresDay() < today) {
				house.pendingEvent = Optional.empty();
				if (waiting.type().equals(FEUD)) {
					// Ignoring a feud doesn't avoid it: only paying tribute does.
					Lineages.tellHouse(server, house, Text.t("event.feud.unanswered", waiting.detail()).withStyle(ChatFormatting.RED));
					acceptFeud(player, lineage, waiting.detail());
				} else {
					Lineages.tellHouse(server, house, Text.t("event.expired").withStyle(ChatFormatting.GRAY));
				}
				Lineages.markDirty(server);
			}
			return;
		}
		// One event at a time: wait until the house has dealt with the last one.
		if (house.quests.stream().anyMatch(q -> q.type == Type.EVENT)) return;
		if (today < house.nextEventDay) return;
		house.nextEventDay = today + settings.intervalDays + player.getRandom().nextInt(3);
		start(player, lineage);
		Lineages.markDirty(server);
	}

	private static void start(ServerPlayer player, Lineage lineage) {
		RandomSource random = player.getRandom();
		List<String> possible = new ArrayList<>(List.of(WEDDING, COUSIN));
		if (!lineage.sick) possible.add(SICKNESS);
		// Young houses get time to settle before anything comes to fight them.
		int founded = lineage.house.chronicle.isEmpty() ? 0 : lineage.house.chronicle.getFirst().day();
		boolean settled = Lineages.today(player.level().getServer()) >= founded + BloodlineConfig.get().events.graceDays;
		if (settled) possible.add(FEUD);
		if (settled && graveToHaunt(player, lineage, random).isPresent()) possible.add(RESTLESS);
		switch (possible.get(random.nextInt(possible.size()))) {
			case SICKNESS -> sickness(player, lineage);
			case RESTLESS -> restless(player, lineage, graveToHaunt(player, lineage, random).get());
			case FEUD -> propose(player, lineage, FEUD, Names.house(random));
			case WEDDING -> propose(player, lineage, WEDDING, "");
			default -> propose(player, lineage, COUSIN, Names.given(random, lineage.current.name()));
		}
	}

	/** For testing: starts one event now ("sickness", "restless", "feud", "wedding" or "cousin"). Returns false if it can't. */
	public static boolean debugStart(ServerPlayer player, Lineage lineage, String type) {
		lineage.house.pendingEvent = Optional.empty();
		RandomSource random = player.getRandom();
		switch (type) {
			case SICKNESS -> sickness(player, lineage);
			case RESTLESS -> {
				Optional<Ancestor> grave = graveToHaunt(player, lineage, random);
				if (grave.isEmpty()) return false;
				restless(player, lineage, grave.get());
			}
			case FEUD -> propose(player, lineage, FEUD, Names.house(random));
			case WEDDING -> propose(player, lineage, WEDDING, "");
			case COUSIN -> propose(player, lineage, COUSIN, Names.given(random, lineage.current.name()));
			default -> {
				return false;
			}
		}
		Lineages.markDirty(player.level().getServer());
		return true;
	}

	// --- Events that start a duty right away --------------------------------------------------

	private static void sickness(ServerPlayer player, Lineage lineage) {
		MinecraftServer server = player.level().getServer();
		lineage.sick = true;
		String name = lineage.current.name();
		Lineages.tellHouse(server, lineage.house, Text.t("event.sickness.announce", name).withStyle(ChatFormatting.DARK_GREEN));
		Lineages.record(server, lineage, Text.t("chronicle.sickness", name));
		Quest cure = Quest.of(Type.EVENT, Text.t("event.sickness.title", name), Text.t("event.sickness.task"),
			Goal.USE, List.of("golden_apple", "enchanted_golden_apple"), 1, Reward.none());
		cure.line = Optional.of(LINE_PREFIX + SICKNESS + ":" + player.getUUID());
		Quests.add(player, lineage, cure);
		announceDuty(server, lineage, cure);
	}

	/** An ancestor with a known grave in the player's dimension, to haunt it. */
	private static Optional<Ancestor> graveToHaunt(ServerPlayer player, Lineage lineage, RandomSource random) {
		List<Ancestor> graves = lineage.ancestors.stream()
			.filter(a -> a.place().filter(p -> p.dimension() == Level.OVERWORLD).isPresent()).toList();
		return graves.isEmpty() ? Optional.empty() : Optional.of(graves.get(random.nextInt(graves.size())));
	}

	private static void restless(ServerPlayer player, Lineage lineage, Ancestor ancestor) {
		MinecraftServer server = player.level().getServer();
		GlobalPos grave = ancestor.place().orElseThrow();
		Lineages.tellHouse(server, lineage.house, Text.t("event.restless.announce", ancestor.fullName()).withStyle(ChatFormatting.DARK_AQUA));
		String where = grave.pos().getX() + ", " + grave.pos().getY() + ", " + grave.pos().getZ();
		Quest quest = Quest.of(Type.EVENT, Text.t("event.restless.title", ancestor.name()), Text.t("event.restless.task", ancestor.name(), where),
				Goal.KILL_CHAMPION, List.of("shade_" + UUID.randomUUID().toString().substring(0, 8), Champions.SHADE.id(),
					grave.dimension().identifier().toString(), "night"), 1, Reward.levels(5))
			.at(grave, 12);
		quest.line = Optional.of(LINE_PREFIX + RESTLESS + ":" + ancestor.name());
		Quests.add(player, lineage, quest);
		announceDuty(server, lineage, quest);
	}

	private static void announceDuty(MinecraftServer server, Lineage lineage, Quest quest) {
		Lineages.tellHouse(server, lineage.house, Text.t("quest.new_objective").withStyle(ChatFormatting.GRAY).append(" ").append(Quests.line(quest, true)));
	}

	// --- Events that wait for a choice --------------------------------------------------------

	private static void propose(ServerPlayer player, Lineage lineage, String type, String detail) {
		MinecraftServer server = player.level().getServer();
		House house = lineage.house;
		house.pendingEvent = Optional.of(new PendingEvent(type, detail, player.getUUID(), Lineages.today(server) + ANSWER_DAYS));
		for (ServerPlayer member : Lineages.onlineMembers(server, house)) show(member, Lineages.get(member));
		player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.8f, 0.9f);
	}

	/** Shows the house's waiting event with its choices (also used by /bloodline event). */
	public static void show(ServerPlayer player, Lineage lineage) {
		if (lineage == null || lineage.house.pendingEvent.isEmpty()) {
			player.sendSystemMessage(Text.t("event.none").withStyle(ChatFormatting.GRAY));
			return;
		}
		PendingEvent event = lineage.house.pendingEvent.get();
		MutableComponent message = switch (event.type()) {
			case FEUD -> Text.t("event.feud.announce", event.detail(), lineage.house.name).withStyle(ChatFormatting.RED)
				.append(" ").append(Text.t("event.feud.ask").withStyle(ChatFormatting.GRAY)).append(" ")
				.append(button(Text.t("button.accept_challenge"), "accept", ChatFormatting.RED, Text.t("event.feud.accept_hover")))
				.append(" ")
				.append(button(Text.t("button.pay_tribute", TRIBUTE), "decline", ChatFormatting.GRAY, Text.t("event.feud.tribute_hover")));
			case WEDDING -> Text.t("event.wedding.announce", nameOf(player, event.member())).withStyle(ChatFormatting.LIGHT_PURPLE)
				.append(" ").append(Text.t("event.wedding.ask").withStyle(ChatFormatting.GRAY)).append(" ")
				.append(button(Text.t("button.accept_match", DOWRY), "accept", ChatFormatting.LIGHT_PURPLE, Text.t("event.wedding.accept_hover")))
				.append(" ")
				.append(button(Text.t("button.decline"), "decline", ChatFormatting.GRAY, Text.t("event.wedding.decline_hover")));
			default -> Text.t("event.cousin.announce", event.detail()).withStyle(ChatFormatting.GOLD)
				.append(" ")
				.append(button(Text.t("button.welcome"), "accept", ChatFormatting.GREEN, Text.t("event.cousin.accept_hover")))
				.append(" ")
				.append(button(Text.t("button.send_away"), "decline", ChatFormatting.GRAY, Text.t("event.cousin.decline_hover")));
		};
		player.sendSystemMessage(message);

		String accept = "/bloodline event accept", decline = "/bloodline event decline";
		switch (event.type()) {
			case FEUD -> Screens.show(player, Text.t("event.feud.window"),
				List.of(Text.t("event.feud.announce", event.detail(), lineage.house.name), Text.t("event.feud.ask")),
				List.of(new Screens.Button(Text.t("button.accept_challenge"), Text.t("event.feud.accept_hover"), accept),
					new Screens.Button(Text.t("button.pay_tribute", TRIBUTE), Text.t("event.feud.tribute_hover"), decline)));
			case WEDDING -> Screens.show(player, Text.t("event.wedding.window"),
				List.of(Text.t("event.wedding.announce", nameOf(player, event.member())), Text.t("event.wedding.ask")),
				List.of(new Screens.Button(Text.t("button.accept_match", DOWRY), Text.t("event.wedding.accept_hover"), accept),
					new Screens.Button(Text.t("button.decline"), Text.t("event.wedding.decline_hover"), decline)));
			default -> Screens.show(player, Text.t("event.cousin.window"),
				List.of(Text.t("event.cousin.announce", event.detail())),
				List.of(new Screens.Button(Text.t("button.welcome"), Text.t("event.cousin.accept_hover"), accept),
					new Screens.Button(Text.t("button.send_away"), Text.t("event.cousin.decline_hover"), decline)));
		}
	}

	private static String nameOf(ServerPlayer viewer, UUID member) {
		Lineage lineage = BloodlineState.get(viewer.level().getServer()).get(member);
		return lineage == null ? "?" : lineage.current.name();
	}

	private static Component button(Component label, String choice, ChatFormatting color, Component hover) {
		return Component.empty().append(label).withStyle(s -> s.withColor(color)
			.withClickEvent(new ClickEvent.RunCommand("/bloodline event " + choice))
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	/** A member answers the waiting event. The first answer counts. */
	public static void answer(ServerPlayer player, boolean accept) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || lineage.awaitingHeir || lineage.house.pendingEvent.isEmpty()) {
			player.sendSystemMessage(Text.t("event.none").withStyle(ChatFormatting.GRAY));
			return;
		}
		MinecraftServer server = player.level().getServer();
		PendingEvent event = lineage.house.pendingEvent.get();
		int cost = switch (event.type()) {
			case FEUD -> accept ? 0 : TRIBUTE;
			case WEDDING -> accept ? DOWRY : 0;
			default -> 0;
		};
		if (Inns.count(player, Items.EMERALD) < cost) {
			if (event.type().equals(FEUD)) {
				// A tribute that can't be paid is a refusal: the feud goes ahead now rather than waiting.
				lineage.house.pendingEvent = Optional.empty();
				Lineages.tellHouse(server, lineage.house, Text.t("event.feud.cannot_pay", cost, event.detail()).withStyle(ChatFormatting.RED));
				acceptFeud(player, lineage, event.detail());
				Lineages.markDirty(server);
			} else {
				player.sendSystemMessage(Text.t("event.too_poor", cost).withStyle(ChatFormatting.RED));
			}
			return;
		}
		Inns.take(player, Items.EMERALD, cost);
		lineage.house.pendingEvent = Optional.empty();
		String who = lineage.current.name();
		switch (event.type()) {
			case FEUD -> {
				if (accept) {
					acceptFeud(player, lineage, event.detail());
				} else {
					Lineages.record(server, lineage, Text.t("chronicle.tribute", lineage.house.name, event.detail()));
					Renown.change(player, lineage, -3, Text.t("renown.reason.tribute", event.detail()));
				}
			}
			case WEDDING -> {
				if (accept) {
					// The blessing belongs to the branch of the member the match was proposed for.
					Lineage bride = BloodlineState.get(server).get(event.member());
					(bride != null && bride.house == lineage.house ? bride : lineage).blessedMatch = true;
					String spouse = nameOf(player, event.member());
					Lineages.record(server, lineage, Text.t("chronicle.wedding", spouse));
					Lineages.tellHouse(server, lineage.house, Text.t("event.wedding.accepted", spouse).withStyle(ChatFormatting.LIGHT_PURPLE));
					player.level().playSound(null, player.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1f, 1.4f);
				} else {
					Lineages.tellHouse(server, lineage.house, Text.t("event.wedding.declined").withStyle(ChatFormatting.GRAY));
				}
			}
			default -> {
				if (accept) {
					Item[] gifts = {Items.IRON_SWORD, Items.IRON_AXE, Items.IRON_PICKAXE, Items.BOW, Items.IRON_CHESTPLATE};
					ItemStack gift = Heirlooms.cousinGift(player, lineage, gifts[player.getRandom().nextInt(gifts.length)], event.detail());
					Component giftName = gift.getHoverName(); // before adding it: that empties the stack
					if (!player.getInventory().add(gift)) player.spawnAtLocation(player.level(), gift);
					Lineages.record(server, lineage, Text.t("chronicle.cousin", who, event.detail(), giftName));
					Lineages.tellHouse(server, lineage.house, Text.t("event.cousin.welcomed", who, event.detail(), giftName)
						.withStyle(ChatFormatting.GOLD));
				} else {
					Lineages.record(server, lineage, Text.t("chronicle.cousin_away", who, event.detail()));
					Lineages.tellHouse(server, lineage.house, Text.t("event.cousin.sent_away", event.detail()).withStyle(ChatFormatting.GRAY));
				}
			}
		}
		Lineages.markDirty(server);
	}

	/** The rival's champion will hunt the house at night until it is defeated. */
	private static void acceptFeud(ServerPlayer player, Lineage lineage, String rival) {
		MinecraftServer server = player.level().getServer();
		Quest quest = Quest.of(Type.EVENT, Text.t("event.feud.title", rival), Text.t("event.feud.task", rival),
			Goal.KILL_CHAMPION, List.of("rival_" + UUID.randomUUID().toString().substring(0, 8), Champions.RIVAL.id(),
				Level.OVERWORLD.identifier().toString(), "night"), 1, Reward.item(4, "emerald", 8));
		quest.line = Optional.of(LINE_PREFIX + FEUD + ":" + rival);
		Quests.add(player, lineage, quest);
		Lineages.record(server, lineage, Text.t("chronicle.feud", rival, lineage.house.name, lineage.current.name()));
		announceDuty(server, lineage, quest);
	}

	// --- Endings ------------------------------------------------------------------------------

	/** Called when an event's duty is done (its quest line starts with "event:"). */
	static void onQuestComplete(ServerPlayer player, Lineage lineage, String line) {
		MinecraftServer server = player.level().getServer();
		String[] parts = line.substring(LINE_PREFIX.length()).split(":", 2);
		String detail = parts.length > 1 ? parts[1] : "";
		switch (parts[0]) {
			case SICKNESS -> {
				Lineage sick = BloodlineState.get(server).get(UUID.fromString(detail));
				if (sick != null) {
					sick.sick = false;
					Lineages.record(server, lineage, Text.t("chronicle.sickness_cured", sick.current.name()));
					Lineages.tellHouse(server, lineage.house, Text.t("event.sickness.cured", sick.current.name()).withStyle(ChatFormatting.GREEN));
				}
			}
			case FEUD -> Lineages.record(server, lineage, Text.t("chronicle.feud_won", lineage.current.name(), detail));
			case RESTLESS -> Lineages.record(server, lineage, Text.t("chronicle.restless", lineage.current.name(), detail));
			default -> {
			}
		}
	}
}
