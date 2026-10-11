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
		FOUNDING("founding"),
		/** A step of a multi-part questline, such as the Founder's Oath. Survives death. */
		QUESTLINE("questline"),
		/** Optional groundwork before a champion fight that weakens the champion. Survives death. */
		PREPARATION("preparation"),
		/** A job taken from an inn's quest board. */
		INN("inn");

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
		KILL("kill"), MINE("mine"), CRAFT("craft"), CUSTOM_STAT("custom_stat"), KILL_PLAYER("kill_player"), VISIT("visit"),
		/** Be in the dimension named by the first target. */
		DIMENSION("dimension"),
		/** Be at or below the Y level given by the first target. */
		DEPTH("depth"),
		/** Kill the target entity types while holding the family's sworn heirloom. */
		KILL_WITH_OATHBLADE("kill_with_oathblade"),
		/** Kill the champion spawned for this quest; the first target is its key. */
		KILL_CHAMPION("kill_champion"),
		/** Done once something else marks it complete (rewards that happen elsewhere). */
		MANUAL("manual");

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
		Codec.INT.optionalFieldOf("radius", 6).forGetter(q -> q.radius),
		Reward.CODEC.fieldOf("reward").forGetter(q -> q.reward),
		Codec.STRING.optionalFieldOf("line").forGetter(q -> q.line),
		Codec.INT.optionalFieldOf("act", 0).forGetter(q -> q.act),
		Codec.INT.optionalFieldOf("step", 0).forGetter(q -> q.step)
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
	public int displayScale;
	public int progress;
	/** The statistic total last time progress was checked. */
	public int lastSeen;
	public Optional<GlobalPos> place;
	/** How close to {@link #place} counts as having arrived. Larger radii only check horizontal distance. */
	public int radius;
	public final Reward reward;
	/** For questline steps: which questline, act and step this is. */
	public Optional<String> line;
	public int act;
	public int step;

	public Quest(Type type, String title, Component task, Goal goal, List<String> targets, int required, int displayScale,
				 int progress, int lastSeen, Optional<GlobalPos> place, int radius, Reward reward, Optional<String> line, int act, int step) {
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
		this.radius = radius;
		this.reward = reward;
		this.line = line;
		this.act = act;
		this.step = step;
	}

	public static Quest of(Type type, String title, Component task, Goal goal, List<String> targets, int required, Reward reward) {
		return new Quest(type, title, task, goal, targets, required, 1, 0, 0, Optional.empty(), 6, reward, Optional.empty(), 0, 0);
	}

	public static Quest of(Type type, String title, String task, Goal goal, List<String> targets, int required, Reward reward) {
		return of(type, title, Component.literal(task), goal, targets, required, reward);
	}

	public Quest at(GlobalPos place, int radius) {
		this.place = Optional.of(place);
		this.radius = radius;
		return this;
	}

	public Quest scaled(int displayScale) {
		this.displayScale = displayScale;
		return this;
	}

	public Quest inLine(String line, int act, int step) {
		this.line = Optional.of(line);
		this.act = act;
		this.step = step;
		return this;
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

		public static Reward none() {
			return new Reward(0, Optional.empty(), 0, false);
		}
	}
}
