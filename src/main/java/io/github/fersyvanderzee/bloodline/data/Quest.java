package io.github.fersyvanderzee.bloodline.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.StringRepresentable;

import java.util.List;
import java.util.Optional;

/**
 * A family duty. Most objectives are measured with vanilla statistics (kills, blocks mined,
 * items crafted...), so progress counts no matter how it was made.
 */
public class Quest {
	public enum Type implements StringRepresentable {
		/** Kill whatever killed an ancestor. Passes down the generations until settled. */
		BLOOD_DEBT("blood_debt"),
		/** Visit the place an ancestor died. */
		RESTING_PLACE("resting_place"),
		/** Finish something the ancestor cared about in life. */
		UNFINISHED_BUSINESS("unfinished_business"),
		/** Early goals for the founder of a house. */
		FOUNDING("founding");

		public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);
		private final String name;

		Type(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public enum Goal implements StringRepresentable {
		KILL("kill"), MINE("mine"), CRAFT("craft"), CUSTOM_STAT("custom_stat"), KILL_PLAYER("kill_player"), VISIT("visit");

		public static final Codec<Goal> CODEC = StringRepresentable.fromEnum(Goal::values);
		private final String name;

		Goal(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}

	public static final Codec<Quest> CODEC = RecordCodecBuilder.create(i -> i.group(
		Type.CODEC.fieldOf("type").forGetter(q -> q.type),
		Codec.STRING.fieldOf("title").forGetter(q -> q.title),
		ComponentSerialization.CODEC.fieldOf("task").forGetter(q -> q.task),
		Goal.CODEC.fieldOf("goal").forGetter(q -> q.goal),
		Codec.STRING.listOf().fieldOf("targets").forGetter(q -> q.targets),
		Codec.INT.fieldOf("required").forGetter(q -> q.required),
		Codec.INT.optionalFieldOf("display_scale", 1).forGetter(q -> q.displayScale),
		Codec.INT.optionalFieldOf("progress", 0).forGetter(q -> q.progress),
		Codec.INT.optionalFieldOf("last_seen", 0).forGetter(q -> q.lastSeen),
		GlobalPos.MAP_CODEC.codec().optionalFieldOf("place").forGetter(q -> q.place),
		Reward.CODEC.fieldOf("reward").forGetter(q -> q.reward)
	).apply(i, Quest::new));

	public final Type type;
	public final String title;
	/** What to do, e.g. "Slay Zombie". Progress is shown after it. */
	public final Component task;
	public final Goal goal;
	/** Entity, block, item or statistic ids, depending on the goal. Progress is the sum over all of them. */
	public final List<String> targets;
	public final int required;
	/** Divides progress for display, e.g. 100 to show centimetres walked as metres. */
	public final int displayScale;
	public int progress;
	/** The statistic total last time progress was checked. */
	public int lastSeen;
	public final Optional<GlobalPos> place;
	public final Reward reward;

	public Quest(Type type, String title, Component task, Goal goal, List<String> targets, int required, int displayScale,
				 int progress, int lastSeen, Optional<GlobalPos> place, Reward reward) {
		this.type = type;
		this.title = title;
		this.task = task;
		this.goal = goal;
		this.targets = List.copyOf(targets);
		this.required = required;
		this.displayScale = displayScale;
		this.progress = progress;
		this.lastSeen = lastSeen;
		this.place = place;
		this.reward = reward;
	}

	public boolean isDone() {
		return progress >= required;
	}

	/** What finishing a duty earns: experience levels, an item, and possibly a new trait. */
	public record Reward(int levels, Optional<String> item, int count, boolean trait) {
		public static final Codec<Reward> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.INT.optionalFieldOf("levels", 0).forGetter(Reward::levels),
			Codec.STRING.optionalFieldOf("item").forGetter(Reward::item),
			Codec.INT.optionalFieldOf("count", 0).forGetter(Reward::count),
			Codec.BOOL.optionalFieldOf("trait", false).forGetter(Reward::trait)
		).apply(i, Reward::new));

		public static Reward levels(int levels) {
			return new Reward(levels, Optional.empty(), 0, false);
		}

		public static Reward item(int levels, String item, int count) {
			return new Reward(levels, Optional.of(item), count, false);
		}

		public static Reward newTrait(int fallbackLevels) {
			return new Reward(fallbackLevels, Optional.empty(), 0, true);
		}
	}
}
