package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Map;

/** The family member a player is currently living as. */
public record Member(String name, int generation, List<String> traits, int bornDay, Map<String, Integer> statsAtBirth) {
	public static final Codec<Member> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("name").forGetter(Member::name),
		Codec.INT.fieldOf("generation").forGetter(Member::generation),
		Codec.STRING.listOf().fieldOf("traits").forGetter(Member::traits),
		Codec.INT.fieldOf("born_day").forGetter(Member::bornDay),
		Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("stats_at_birth").forGetter(Member::statsAtBirth)
	).apply(i, Member::new));
}
