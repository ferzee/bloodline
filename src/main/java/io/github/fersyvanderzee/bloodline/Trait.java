package io.github.fersyvanderzee.bloodline;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.List;

/**
 * Something a family member is born with. Traits in the same group exclude each other
 * (nobody is both Stalwart and Frail).
 */
public record Trait(String id, Kind kind, String group, List<Effect> effects) {
	public enum Kind {
		BOON(ChatFormatting.GREEN), FLAW(ChatFormatting.RED), QUIRK(ChatFormatting.AQUA),
		/** Earned by finishing an oath. Every later member of the family is born with it. */
		LEGENDARY(ChatFormatting.GOLD);

		public final ChatFormatting color;

		Kind(ChatFormatting color) {
			this.color = color;
		}
	}

	public record Effect(Holder<Attribute> attribute, double amount, AttributeModifier.Operation operation) {
	}

	/** The trait's name, coloured by kind, with its description on hover. */
	public MutableComponent displayName() {
		return name().withStyle(s -> s.withColor(kind.color)
			.withHoverEvent(new HoverEvent.ShowText(Text.t("trait." + id + ".desc"))));
	}

	/** The trait's name without styling. */
	public MutableComponent name() {
		return Text.t("trait." + id);
	}
}
