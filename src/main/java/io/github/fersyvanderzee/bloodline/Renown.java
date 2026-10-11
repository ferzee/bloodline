package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * The standing of a house. Fulfilling duties earns renown, neglecting blood debts costs it, and
 * each act of the Founder's Oath needs a certain amount before it can begin.
 */
public final class Renown {
	public static final int ACT_COMPLETED = 5;
	public static final int DEBT_INHERITED = -2;
	public static final int DEBT_FORGOTTEN = -3;

	private Renown() {
	}

	/** Renown for fulfilling a duty of this kind. */
	public static int forDuty(Quest quest) {
		if (quest.line.filter("reclaim"::equals).isPresent()) return 4;
		return switch (quest.type) {
			case BLOOD_DEBT -> 5;
			case UNFINISHED_BUSINESS -> 3;
			case RESTING_PLACE, FOUNDING, PREPARATION -> 2;
			case INN -> 3;
			case QUESTLINE -> 0;
		};
	}

	/** Changes the house's renown and tells the player why. Renown never drops below zero. */
	public static void change(ServerPlayer player, Lineage lineage, int amount, String reason) {
		if (amount == 0) return;
		lineage.house.renown = Math.max(0, lineage.house.renown + amount);
		Lineages.markDirty(player.level().getServer());
		// Renown belongs to the whole house, so every member online hears about it.
		String who = lineage.house.members.size() > 1 ? " (" + lineage.current.name() + ")" : "";
		Lineages.tellHouse(player.level().getServer(), lineage.house, Component.literal((amount > 0 ? "+" : "") + amount + " Renown")
			.withStyle(amount > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED)
			.append(Component.literal(" " + reason + who + " (House " + lineage.house.name + ": " + lineage.house.renown + ")").withStyle(ChatFormatting.GRAY)));
		if (amount > 0) Questlines.resume(player, lineage);
	}
}
