package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

public record ChronicleEntry(int day, Component text) {
	public static final Codec<ChronicleEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.INT.fieldOf("day").forGetter(ChronicleEntry::day),
		ComponentSerialization.CODEC.fieldOf("text").forGetter(ChronicleEntry::text)
	).apply(i, ChronicleEntry::new));
}
