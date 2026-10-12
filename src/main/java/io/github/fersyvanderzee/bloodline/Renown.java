package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * The standing of a house. Fulfilling duties earns renown, neglecting blood debts costs it, and
 * each act of the Founder's Oath needs a certain amount before it can begin.
 */
public final class Renown {
	private static BloodlineConfig.Renown settings() {
		return BloodlineConfig.get().renown;
	}

	public static int actCompleted() {
		return settings().actCompleted;
	}

	public static int debtInherited() {
		return settings().debtInherited;
	}

	public static int debtForgotten() {
		return settings().debtForgotten;
	}

	/** Renown needed to begin an act of the oath (0-based). */
	public static int forAct(int act) {
		int[] thresholds = settings().actThresholds;
		return thresholds[Math.min(act, thresholds.length - 1)];
	}

	private Renown() {
	}

	/** Renown for fulfilling a duty of this kind. */
	public static int forDuty(Quest quest) {
		if (quest.line.filter("reclaim"::equals).isPresent()) return settings().reclaimedHeirloom;
		return switch (quest.type) {
			case BLOOD_DEBT -> settings().bloodDebt;
			case UNFINISHED_BUSINESS -> settings().unfinishedBusiness;
			case RESTING_PLACE -> settings().restingPlace;
			case FOUNDING -> settings().foundingGoal;
			case PREPARATION -> settings().preparation;
			case INN -> settings().innJob;
			case QUESTLINE -> 0;
		};
	}

	/** Changes the house's renown and tells the player why. Renown never drops below zero. */
	public static void change(ServerPlayer player, Lineage lineage, int amount, Component reason) {
		if (amount == 0) return;
		lineage.house.renown = Math.max(0, lineage.house.renown + amount);
		Lineages.markDirty(player.level().getServer());
		// Renown belongs to the whole house, so every member online hears about it.
		MutableComponent detail = Component.literal(" ").append(reason);
		if (lineage.house.members.size() > 1) detail.append(" (" + lineage.current.name() + ")");
		detail.append(" ").append(Text.t("renown.total", lineage.house.name, lineage.house.renown));
		Lineages.tellHouse(player.level().getServer(), lineage.house, Text.t("renown.change", (amount > 0 ? "+" : "") + amount)
			.withStyle(amount > 0 ? ChatFormatting.YELLOW : ChatFormatting.RED)
			.append(detail.withStyle(ChatFormatting.GRAY)));
		if (amount > 0) Questlines.resume(player, lineage);
	}
}
