package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * A family event waiting for the house to choose what to do (see FamilyEvents). {@code detail}
 * carries what the event is about, such as the rival house's name; {@code member} is the member it
 * happened to.
 */
public record PendingEvent(String type, String detail, UUID member, int expiresDay) {
	public static final Codec<PendingEvent> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("type").forGetter(PendingEvent::type),
		Codec.STRING.optionalFieldOf("detail", "").forGetter(PendingEvent::detail),
		UUIDUtil.STRING_CODEC.fieldOf("member").forGetter(PendingEvent::member),
		Codec.INT.fieldOf("expires_day").forGetter(PendingEvent::expiresDay)
	).apply(i, PendingEvent::new));
}
