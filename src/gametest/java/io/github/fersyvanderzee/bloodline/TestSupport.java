package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/** Shortcuts for the quest tests: a fresh family, stats, quest lookups and text checks. */
final class TestSupport {
	private TestSupport() {
	}

	/** A new player in the test world, who founds a house as they join. */
	static ServerPlayer join(GameTestHelper helper) {
		@SuppressWarnings("removal")
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.snapTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1.5, 1, 1.5)));
		return player;
	}

	static Lineage lineage(GameTestHelper helper, ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		check(helper, lineage != null, "the player has a family");
		return lineage;
	}

	static void stat(ServerPlayer player, Stat<?> stat, int amount) {
		player.awardStat(stat, amount);
	}

	/** Runs the quest checks once, as the server does every second. */
	static void tick(ServerPlayer player) {
		Quests.tick(player, Lineages.get(player));
	}

	static Optional<Quest> quest(Lineage lineage, Predicate<Quest> which) {
		return lineage.allQuests().stream().filter(which).findFirst();
	}

	static Quest needQuest(GameTestHelper helper, Lineage lineage, String what, Predicate<Quest> which) {
		Optional<Quest> quest = quest(lineage, which);
		check(helper, quest.isPresent(), "expected a quest: " + what + "; the family has " + describe(lineage.allQuests()));
		return quest.get();
	}

	static boolean completed(Lineage lineage, String titleKey) {
		return lineage.completed.stream().anyMatch(c -> keyOf(c.title()).equals("bloodline." + titleKey));
	}

	static String keyOf(Component component) {
		return component.getContents() instanceof TranslatableContents t ? t.getKey() : component.getString();
	}

	static String describe(List<Quest> quests) {
		return quests.stream().map(q -> keyOf(q.title) + " (" + q.goal + " " + q.progress + "/" + q.required + ")").toList().toString();
	}

	static int count(ServerPlayer player, Item item) {
		int total = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	static void check(GameTestHelper helper, boolean condition, String message) {
		if (!condition) throw helper.assertionException(Component.literal(message));
	}

	/** Fails if the text, or any text inside it, uses a Bloodline Chronicles translation key that has no English text. */
	static void checkText(GameTestHelper helper, Component component, String where) {
		if (component.getContents() instanceof TranslatableContents t) {
			if (t.getKey().startsWith("bloodline.")) {
				check(helper, Text.has(t.getKey().substring("bloodline.".length())), "missing text " + t.getKey() + " in " + where);
			}
			for (Object arg : t.getArgs()) if (arg instanceof Component inner) checkText(helper, inner, where);
		}
		for (Component sibling : component.getSiblings()) checkText(helper, sibling, where);
	}

	static void checkTexts(GameTestHelper helper, Lineage lineage) {
		for (Quest quest : lineage.allQuests()) {
			checkText(helper, quest.title, "a quest title");
			checkText(helper, quest.task, "a quest task");
		}
	}

	// --- Reaching private parts of the mod --------------------------------------------------------

	@SuppressWarnings("unchecked")
	static <T> T field(Class<?> owner, String name) {
		try {
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(null);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@SuppressWarnings("unchecked")
	static <T> T call(Class<?> owner, String name, Object... args) {
		for (Method method : owner.getDeclaredMethods()) {
			if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
			try {
				method.setAccessible(true);
				return (T) method.invoke(null, args);
			} catch (java.lang.reflect.InvocationTargetException e) {
				if (e.getCause() instanceof RuntimeException runtime) throw runtime;
				throw new IllegalStateException(e.getCause());
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		}
		throw new IllegalStateException("no method " + owner.getSimpleName() + "." + name + " with " + args.length + " arguments");
	}

	static Object read(Object record, String accessor) {
		try {
			Method method = record.getClass().getDeclaredMethod(accessor);
			method.setAccessible(true);
			return method.invoke(record);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}
}
