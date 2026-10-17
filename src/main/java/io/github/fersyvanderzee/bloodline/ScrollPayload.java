package io.github.fersyvanderzee.bloodline;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A choice window for players who have Bloodline Chronicles installed: the client draws it as a parchment scroll.
 * Players without the mod get the same choice as one of Minecraft's own dialog windows (see {@link Screens}).
 * Every card and button runs a /bloodline command, so the server treats both the same.
 */
public record ScrollPayload(Component title, List<Component> body, List<Card> cards, List<Choice> choices, Component footer, ItemStack icon)
	implements CustomPacketPayload {

	public static final Type<ScrollPayload> TYPE = new Type<>(Bloodline.id("scroll"));

	/** A card to click, such as one of the children who could become heir: a name, a line under it, a few lines of detail. */
	public record Card(Component name, Component subtitle, List<Component> lines, Component tooltip, String command) {
		static final StreamCodec<RegistryFriendlyByteBuf, Card> CODEC = StreamCodec.composite(
			ComponentSerialization.TRUSTED_STREAM_CODEC, Card::name,
			ComponentSerialization.TRUSTED_STREAM_CODEC, Card::subtitle,
			ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list(8)), Card::lines,
			ComponentSerialization.TRUSTED_STREAM_CODEC, Card::tooltip,
			ByteBufCodecs.STRING_UTF8, Card::command,
			Card::new);
	}

	/** How a button looks: an ordinary one, the one the window suggests, or a grave one (giving your life). */
	public enum Tone { PLAIN, PRIMARY, BLOOD }

	/** A button. An empty command just closes the window. */
	public record Choice(Component label, Component tooltip, String command, int tone) {
		static final StreamCodec<RegistryFriendlyByteBuf, Choice> CODEC = StreamCodec.composite(
			ComponentSerialization.TRUSTED_STREAM_CODEC, Choice::label,
			ComponentSerialization.TRUSTED_STREAM_CODEC, Choice::tooltip,
			ByteBufCodecs.STRING_UTF8, Choice::command,
			ByteBufCodecs.VAR_INT, Choice::tone,
			Choice::new);

		public Tone toneOf() {
			return tone >= 0 && tone < Tone.values().length ? Tone.values()[tone] : Tone.PLAIN;
		}
	}

	public static final StreamCodec<RegistryFriendlyByteBuf, ScrollPayload> CODEC = StreamCodec.composite(
		ComponentSerialization.TRUSTED_STREAM_CODEC, ScrollPayload::title,
		ComponentSerialization.TRUSTED_STREAM_CODEC.apply(ByteBufCodecs.list(16)), ScrollPayload::body,
		Card.CODEC.apply(ByteBufCodecs.list(6)), ScrollPayload::cards,
		Choice.CODEC.apply(ByteBufCodecs.list(6)), ScrollPayload::choices,
		ComponentSerialization.TRUSTED_STREAM_CODEC, ScrollPayload::footer,
		ItemStack.OPTIONAL_STREAM_CODEC, ScrollPayload::icon,
		ScrollPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
