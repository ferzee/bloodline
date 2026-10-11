package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * How a family was saved before houses could have several members. Only used to read old worlds;
 * {@link BloodlineState} turns each one into a one-member {@link House} and its {@link Lineage}.
 */
record LegacyLineage(String house, Member current, boolean awaitingHeir, List<Ancestor> ancestors, List<ChronicleEntry> chronicle,
					 List<String> milestones, List<Quest> quests, Optional<String> oath, int oathActs, int oathActGeneration,
					 List<String> legacies, List<ItemStack> lostHeirlooms, int renown, List<String> preparations,
					 List<CompletedQuest> completed) {
	static final Codec<LegacyLineage> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("house").forGetter(LegacyLineage::house),
		Member.CODEC.fieldOf("current").forGetter(LegacyLineage::current),
		Codec.BOOL.optionalFieldOf("awaiting_heir", false).forGetter(LegacyLineage::awaitingHeir),
		Ancestor.CODEC.listOf().optionalFieldOf("ancestors", List.of()).forGetter(LegacyLineage::ancestors),
		ChronicleEntry.CODEC.listOf().optionalFieldOf("chronicle", List.of()).forGetter(LegacyLineage::chronicle),
		Codec.STRING.listOf().optionalFieldOf("milestones", List.of()).forGetter(LegacyLineage::milestones),
		Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(LegacyLineage::quests),
		Codec.STRING.optionalFieldOf("oath").forGetter(LegacyLineage::oath),
		Codec.INT.optionalFieldOf("oath_acts", 0).forGetter(LegacyLineage::oathActs),
		Codec.INT.optionalFieldOf("oath_act_generation", 0).forGetter(LegacyLineage::oathActGeneration),
		Codec.STRING.listOf().optionalFieldOf("legacies", List.of()).forGetter(LegacyLineage::legacies),
		ItemStack.CODEC.listOf().optionalFieldOf("lost_heirlooms", List.of()).forGetter(LegacyLineage::lostHeirlooms),
		Codec.INT.optionalFieldOf("renown", 0).forGetter(LegacyLineage::renown),
		Codec.STRING.listOf().optionalFieldOf("preparations", List.of()).forGetter(LegacyLineage::preparations),
		CompletedQuest.CODEC.listOf().optionalFieldOf("completed", List.of()).forGetter(LegacyLineage::completed)
	).apply(i, LegacyLineage::new));
}
