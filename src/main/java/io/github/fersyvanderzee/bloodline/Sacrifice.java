package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Sacrifice: under a full moon, at the resting place of an ancestor, a member of the house
 * may give their life for it. It is a real death (their belongings fall where they knelt), but
 * their heir inherits every boon, the house gains renown, and the next act of the oath can begin.
 * Three sacrifices make the house Blood-Sworn.
 */
public final class Sacrifice {
	public static final int RENOWN = 10;
	public static final int FOR_BLOOD_SWORN = 3;
	private static final int RITUAL_RADIUS = 6;
	private static final long CONFIRM_TICKS = 20 * 30;

	/** Players who asked to sacrifice and still have to confirm, with when the offer runs out. */
	private static final Map<UUID, Long> AWAITING_CONFIRMATION = new HashMap<>();
	/** Players in the middle of dying by sacrifice, so their death is recorded as one. */
	private static final Set<UUID> SACRIFICING = new HashSet<>();

	private Sacrifice() {
	}

	/** Which of the ritual's conditions hold right now, and whose grave the player stands at. */
	public record Conditions(boolean night, boolean fullMoon, Optional<String> grave, int nightsUntilFullMoon) {
		public boolean met() {
			return night && fullMoon && grave.isPresent();
		}
	}

	public static Conditions check(ServerPlayer player, Lineage lineage) {
		ServerLevel level = player.level();
		boolean overworld = level.dimension() == Level.OVERWORLD;
		boolean night = overworld && level.isDarkOutside();
		boolean fullMoon = overworld && level.environmentAttributes().getDimensionValue(EnvironmentAttributes.MOON_PHASE) == MoonPhase.FULL_MOON;
		int day = Lineages.today(level.getServer()) - 1;
		int nightsUntil = (MoonPhase.COUNT - day % MoonPhase.COUNT) % MoonPhase.COUNT;
		return new Conditions(night, fullMoon, graveNear(player, lineage), nightsUntil);
	}

	/** The name of an ancestor of any branch of the house whose resting place the player stands at. */
	private static Optional<String> graveNear(ServerPlayer player, Lineage lineage) {
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
			player.sendSystemMessage(Component.literal("The ritual can't be performed here and now.").withStyle(ChatFormatting.RED));
			if (!conditions.night()) player.sendSystemMessage(Component.literal(" - It must be night.").withStyle(ChatFormatting.GRAY));
			if (!conditions.fullMoon()) player.sendSystemMessage(Component.literal(" - The moon must be full (in "
				+ conditions.nightsUntilFullMoon() + " nights).").withStyle(ChatFormatting.GRAY));
			if (conditions.grave().isEmpty()) player.sendSystemMessage(Component.literal(" - You must stand at an ancestor's resting place.")
				.withStyle(ChatFormatting.GRAY));
			return;
		}
		AWAITING_CONFIRMATION.put(player.getUUID(), player.level().getGameTime() + CONFIRM_TICKS);
		player.sendSystemMessage(Component.literal("You kneel at the grave of " + conditions.grave().get() + ". ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal("Will " + lineage.current.name() + " give their life for House " + lineage.house.name + "? ")
				.withStyle(ChatFormatting.WHITE))
			.append(Component.literal("[I give my life]").withStyle(s -> s.withColor(ChatFormatting.DARK_RED).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand("/bloodline sacrifice confirm"))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(lineage.current.name() + " dies. Everything they carry falls here.\n"
					+ "Their heir inherits every boon, and the house gains " + RENOWN + " renown."))))));
	}

	/** Second step: the player dies, and their death is recorded as a sacrifice. */
	public static void confirm(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		Long until = AWAITING_CONFIRMATION.remove(player.getUUID());
		if (lineage == null || until == null || player.level().getGameTime() > until) {
			player.sendSystemMessage(Component.literal("Kneel at the grave first: open your Chronicle or use /bloodline sacrifice.")
				.withStyle(ChatFormatting.RED));
			return;
		}
		if (!check(player, lineage).met()) {
			player.sendSystemMessage(Component.literal("The moment has passed.").withStyle(ChatFormatting.RED));
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
		server.getPlayerList().broadcastSystemMessage(Component.literal(ancestor.name() + " gave their life for House " + lineage.house.name + ".")
			.withStyle(ChatFormatting.DARK_RED, ChatFormatting.ITALIC), false);
		Renown.change(player, lineage, RENOWN, "for the sacrifice of " + ancestor.name());
		if (lineage.house.sacrifices == FOR_BLOOD_SWORN && !lineage.house.legacies.contains("blood_sworn")) {
			lineage.house.legacies.add("blood_sworn");
			Lineages.record(server, lineage, Component.literal("Three have now given their lives for House " + lineage.house.name
				+ ". The house is Blood-Sworn, and every child of it will be too."));
			Lineages.tellHouse(server, lineage.house, Component.literal("House " + lineage.house.name + " is Blood-Sworn! Legendary trait: ")
				.withStyle(ChatFormatting.GOLD).append(Traits.get("blood_sworn").displayName()));
			Lineages.onlineMembers(server, lineage.house).forEach(member -> {
				Lineage theirs = Lineages.get(member);
				if (theirs != null && !theirs.awaitingHeir) Traits.apply(member, theirs.allTraits());
			});
		}
		Lineages.markDirty(server);
	}
}
