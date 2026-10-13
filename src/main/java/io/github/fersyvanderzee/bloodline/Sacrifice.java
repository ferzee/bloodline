package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Sacrifice: under a full moon, at the resting place of an ancestor (or, for a founder with
 * no graves yet, at the steward's home), a member of the house may give their life for it. It is a real death (their belongings fall where they knelt), but
 * their heir inherits every boon, the house gains renown, and the next act of the oath can begin.
 * Only a family member who fulfilled an act of the oath may do it, so it can't be used to churn
 * through generations. Three sacrifices make the house Blood-Sworn.
 */
public final class Sacrifice {
	public static int renown() {
		return BloodlineConfig.get().sacrifice.renown;
	}

	public static int forBloodSworn() {
		return BloodlineConfig.get().sacrifice.sacrificesForBloodSworn;
	}
	private static final int RITUAL_RADIUS = 6;
	private static final long CONFIRM_TICKS = 20 * 30;

	/** Players who asked to sacrifice and still have to confirm, with when the offer runs out. */
	private static final Map<UUID, Long> AWAITING_CONFIRMATION = new HashMap<>();
	/** Players in the middle of dying by sacrifice, so their death is recorded as one. */
	private static final Set<UUID> SACRIFICING = new HashSet<>();

	private Sacrifice() {
	}

	/**
	 * Which of the ritual's conditions hold right now. {@code place} describes where the player
	 * kneels ("Elske the Bold's grave", or "the family seat"); {@code atSeat} is true for the latter.
	 */
	public record Conditions(boolean actDone, boolean night, boolean fullMoon, Optional<Component> place, boolean atSeat, boolean founder,
							 int nightsUntilFullMoon) {
		public boolean met() {
			return actDone && night && fullMoon && place.isPresent();
		}
	}

	public static Conditions check(ServerPlayer player, Lineage lineage) {
		ServerLevel level = player.level();
		boolean overworld = level.dimension() == Level.OVERWORLD;
		boolean night = overworld && level.isDarkOutside();
		boolean fullMoon = overworld && level.environmentAttributes().getDimensionValue(EnvironmentAttributes.MOON_PHASE) == MoonPhase.FULL_MOON;
		int day = Lineages.today(level.getServer()) - 1;
		int nightsUntil = (MoonPhase.COUNT - day % MoonPhase.COUNT) % MoonPhase.COUNT;
		Optional<Component> grave = graveNear(player, lineage).map(name -> Text.t("sacrifice.place.grave", name));
		// Founders have no graves to kneel at yet, so they may perform the ritual at the steward's home instead.
		boolean founder = lineage.ancestors.stream().noneMatch(a -> a.place().isPresent());
		boolean atSeat = grave.isEmpty() && founder && atStewardsHome(player, lineage);
		return new Conditions(lineage.fulfilledAct, night, fullMoon, atSeat ? Optional.of(Text.t("sacrifice.place.seat")) : grave, atSeat, founder, nightsUntil);
	}

	private static boolean atStewardsHome(ServerPlayer player, Lineage lineage) {
		return lineage.house.steward
			.filter(s -> s.home.dimension() == player.level().dimension() && s.home.pos().closerToCenterThan(player.position(), RITUAL_RADIUS))
			.isPresent();
	}

	/** The name of an ancestor of any branch of the house whose resting place the player stands at. */
	private static Optional<Component> graveNear(ServerPlayer player, Lineage lineage) {
		BloodlineState state = BloodlineState.get(player.level().getServer());
		for (UUID member : lineage.house.members) {
			Lineage branch = state.get(member);
			if (branch == null) continue;
			for (Ancestor ancestor : branch.ancestors) {
				Optional<GlobalPos> place = ancestor.place();
				if (place.isPresent() && place.get().dimension() == player.level().dimension()
					&& place.get().pos().closerToCenterThan(player.position(), RITUAL_RADIUS)) {
					return Optional.of(ancestor.fullName());
				}
			}
		}
		return Optional.empty();
	}

	/** First step: checks the conditions and asks the player to confirm. */
	public static void request(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null || lineage.awaitingHeir || !player.isAlive()) return;
		Conditions conditions = check(player, lineage);
		if (!conditions.met()) {
			player.sendSystemMessage(Text.t("sacrifice.not_now").withStyle(ChatFormatting.RED));
			if (!conditions.actDone()) player.sendSystemMessage(missing(Text.t("sacrifice.missing.act", lineage.current.name())));
			if (!conditions.night()) player.sendSystemMessage(missing(Text.t("sacrifice.missing.night")));
			if (!conditions.fullMoon()) player.sendSystemMessage(missing(Text.t("sacrifice.missing.moon", conditions.nightsUntilFullMoon())));
			if (conditions.place().isEmpty()) player.sendSystemMessage(missing(Text.t(conditions.founder()
				? "sacrifice.missing.place_founder" : "sacrifice.missing.place")));
			return;
		}
		AWAITING_CONFIRMATION.put(player.getUUID(), player.level().getGameTime() + CONFIRM_TICKS);
		player.sendSystemMessage(Text.t("sacrifice.kneel", conditions.place().get()).withStyle(ChatFormatting.GRAY)
			.append(" ")
			.append(Text.t("sacrifice.ask", lineage.current.name(), lineage.house.name).withStyle(ChatFormatting.WHITE))
			.append(" ")
			.append(Text.t("button.give_life").withStyle(s -> s.withColor(ChatFormatting.DARK_RED).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand("/bloodline sacrifice confirm"))
				.withHoverEvent(new HoverEvent.ShowText(Text.t("sacrifice.confirm_hover", lineage.current.name(), renown()))))));
		Screens.show(player, Text.t("book.sacrifice.title"),
			List.of(Text.t("sacrifice.kneel", conditions.place().get()), Text.t("sacrifice.ask", lineage.current.name(), lineage.house.name),
				Text.t("sacrifice.confirm_hover", lineage.current.name(), renown()).withStyle(ChatFormatting.GRAY)),
			List.of(new Screens.Button(Text.t("button.give_life").withStyle(ChatFormatting.DARK_RED), null, "/bloodline sacrifice confirm"),
				new Screens.Button(Text.t("button.not_yet"), null, null)));
	}

	private static Component missing(MutableComponent reason) {
		return Component.literal(" - ").append(reason).withStyle(ChatFormatting.GRAY);
	}

	/** Second step: the player dies, and their death is recorded as a sacrifice. */
	public static void confirm(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		Long until = AWAITING_CONFIRMATION.remove(player.getUUID());
		if (lineage == null || until == null || player.level().getGameTime() > until) {
			player.sendSystemMessage(Text.t("sacrifice.kneel_first")
				.withStyle(ChatFormatting.RED));
			return;
		}
		if (!check(player, lineage).met()) {
			player.sendSystemMessage(Text.t("sacrifice.passed").withStyle(ChatFormatting.RED));
			return;
		}
		ServerLevel level = player.level();
		level.playSound(null, player.blockPosition(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 1f, 0.6f);
		level.playSound(null, player.blockPosition(), SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.4f, 1.6f);
		SACRIFICING.add(player.getUUID());
		player.kill(level);
	}

	/** Whether this death is a sacrifice. Called once, from the death handler. */
	public static boolean consume(ServerPlayer player) {
		return SACRIFICING.remove(player.getUUID());
	}

	/** Rewards the house for a member's sacrifice. */
	public static void honour(ServerPlayer player, Lineage lineage, Ancestor ancestor) {
		MinecraftServer server = player.level().getServer();
		lineage.inheritAll = true;
		lineage.house.sacrifices++;
		server.getPlayerList().broadcastSystemMessage(Text.t("broadcast.sacrifice", ancestor.name(), lineage.house.name)
			.withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC), false);
		Renown.change(player, lineage, renown(), Text.t("renown.reason.sacrifice", ancestor.name()));
		if (lineage.house.sacrifices == forBloodSworn() && !lineage.house.legacies.contains("blood_sworn")) {
			lineage.house.legacies.add("blood_sworn");
			Lineages.record(server, lineage, Text.t("chronicle.blood_sworn", lineage.house.sacrifices, lineage.house.name));
			Lineages.tellHouse(server, lineage.house, Text.t("sacrifice.blood_sworn", lineage.house.name, Traits.get("blood_sworn").displayName())
				.withStyle(ChatFormatting.GOLD));
			Lineages.onlineMembers(server, lineage.house).forEach(member -> {
				Lineage theirs = Lineages.get(member);
				if (theirs != null && !theirs.awaitingHeir) Traits.apply(member, theirs.allTraits());
			});
		}
		Lineages.markDirty(server);
	}
}
