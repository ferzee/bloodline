package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;

import java.util.ArrayList;
import java.util.List;

/**
 * A house's family seat: the Fallen Manor it claimed (see Manors). {@code lectern} is where the manor's will lies,
 * used to find the manor again; {@code buried} lists the ancestors who already have a gravestone in its garden.
 */
public class Seat {
	public static final Codec<Seat> CODEC = RecordCodecBuilder.create(i -> i.group(
		Codec.STRING.fieldOf("manor").forGetter(s -> s.manor),
		GlobalPos.MAP_CODEC.fieldOf("lectern").forGetter(s -> s.lectern),
		Codec.STRING.listOf().optionalFieldOf("buried", List.of()).forGetter(s -> s.buried)
	).apply(i, Seat::new));

	/** The manor's key (its starting chunk). */
	public final String manor;
	public final GlobalPos lectern;
	public final List<String> buried;

	public Seat(String manor, GlobalPos lectern, List<String> buried) {
		this.manor = manor;
		this.lectern = lectern;
		this.buried = new ArrayList<>(buried);
	}
}
