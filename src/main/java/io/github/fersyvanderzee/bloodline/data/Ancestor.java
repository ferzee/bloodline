package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;

import java.util.List;
import java.util.Optional;

/** A family member who has died. */
/** {@code place} is where they died: their resting place, where the Sacrifice can be performed. */
public record Ancestor(String name, String epithet, int generation, List<String> traits, int bornDay, int diedDay, Component deathMessage,
					   Optional<GlobalPos> place) {
	public static final Codec<Ancestor> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("name").forGetter(Ancestor::name),
		Codec.STRING.fieldOf("epithet").forGetter(Ancestor::epithet),
		Codec.INT.fieldOf("generation").forGetter(Ancestor::generation),
		Codec.STRING.listOf().fieldOf("traits").forGetter(Ancestor::traits),
		Codec.INT.fieldOf("born_day").forGetter(Ancestor::bornDay),
		Codec.INT.fieldOf("died_day").forGetter(Ancestor::diedDay),
		ComponentSerialization.CODEC.fieldOf("death_message").forGetter(Ancestor::deathMessage),
		GlobalPos.MAP_CODEC.codec().optionalFieldOf("place").forGetter(Ancestor::place)
	).apply(i, Ancestor::new));

	public String fullName() {
		return name + " " + epithet;
	}
}
