package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.ChronicleEntry;
import io.github.fersyvanderzee.bloodline.data.CompletedQuest;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import io.github.fersyvanderzee.bloodline.data.Quest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The family chronicle: the house's journal and history in one book. It is a vanilla written
 * book, so players don't need the mod installed. Its pages are rewritten from the family's state
 * every time it is opened.
 *
 * <p>Sections: contents, the main quest (where the Founder's Oath is sworn), duties, the head of
 * the house, the family tree, and the history.
 */
public final class Chronicle {
	private static final String KEY = "bloodline_chronicle";
	/** Roughly how many characters fit on a book page (14 lines of ~19 characters, with some slack). */
	static final int PAGE_BUDGET = 190;
	private static final int ANCESTORS_PER_PAGE = 6;
	private static final int MAX_PAGES = 100;

	private Chronicle() {
	}

	public static boolean isChronicle(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return stack.is(Items.WRITTEN_BOOK) && data != null && data.copyTag().contains(KEY);
	}

	public static void give(ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null) return;
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		CustomData.update(DataComponents.CUSTOM_DATA, book, tag -> tag.putBoolean(KEY, true));
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, build(lineage));
		if (!player.getInventory().add(book)) {
			player.spawnAtLocation(player.level(), book);
		}
	}

	/** Gives the player a chronicle if they don't carry one. */
	public static void ensure(ServerPlayer player) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			if (isChronicle(player.getInventory().getItem(i))) return;
		}
		give(player);
	}

	/** Refreshes the book's pages just before vanilla opens it. */
	public static void refresh(ServerPlayer player, ItemStack book) {
		Lineage lineage = Lineages.get(player);
		if (lineage != null) {
			book.set(DataComponents.WRITTEN_BOOK_CONTENT, build(lineage));
			// The client opens its own copy of the book, so send the new pages before the open-book packet.
			// Otherwise the inventory sync only arrives next tick and the book shows the previous pages.
			player.containerMenu.broadcastChanges();
		}
	}

	/** Reopens the chronicle the player is holding, e.g. after they swore an oath from it. */
	public static void reopen(ServerPlayer player) {
		for (InteractionHand hand : InteractionHand.values()) {
			ItemStack stack = player.getItemInHand(hand);
			if (isChronicle(stack)) {
				refresh(player, stack);
				player.openItemGui(stack, hand);
				return;
			}
		}
	}

	static WrittenBookContent build(Lineage lineage) {
		List<Component> mainQuest = Questlines.bookPages(lineage, 2);
		List<Component> duties = duties(lineage);
		List<Component> head = List.of(head(lineage));
		List<Component> tree = familyTree(lineage);
		List<Component> history = entries(lineage);
		int room = MAX_PAGES - 1 - mainQuest.size() - duties.size() - head.size() - tree.size();
		if (history.size() > room) history = history.subList(Math.max(0, history.size() - Math.max(room, 0)), history.size());

		// Page numbers are 1-based; the cover is page 1 and the main quest always starts on page 2.
		int mainPage = 2;
		int dutiesPage = mainPage + mainQuest.size();
		int headPage = dutiesPage + duties.size();
		int treePage = headPage + head.size();
		int historyPage = treePage + tree.size();
		long dutyCount = lineage.quests.stream().filter(q -> !Questlines.isOathStep(q) && q.type != Quest.Type.PREPARATION).count();

		List<Component> pages = new ArrayList<>();
		pages.add(cover(lineage, List.of(
			link("Main Quest", mainPage),
			link("Duties" + (dutyCount > 0 ? " (" + dutyCount + ")" : ""), dutiesPage),
			link("Head of the House", headPage),
			link("Family Tree", treePage),
			link("History", historyPage))));
		pages.addAll(mainQuest);
		pages.addAll(duties);
		pages.addAll(head);
		pages.addAll(tree);
		pages.addAll(history.isEmpty() ? List.of(heading("History").append(body("Nothing has happened yet."))) : history);

		String title = "Chronicle of " + lineage.house;
		if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
		return new WrittenBookContent(Filterable.passThrough(title), "House " + lineage.house, 0,
			pages.stream().map(Filterable::passThrough).toList(), true);
	}

	// --- Shared page styling ------------------------------------------------------------------

	static MutableComponent heading(String text) {
		return Component.literal(text + "\n\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
	}

	static MutableComponent body(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false));
	}

	static MutableComponent faint(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withBold(false));
	}

	/** A line that jumps to another page of the book. */
	static MutableComponent link(String text, int page) {
		return Component.literal("» " + text + "\n").withStyle(s -> s.withColor(ChatFormatting.DARK_BLUE).withBold(false)
			.withClickEvent(new ClickEvent.ChangePage(page))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal("Go to page " + page))));
	}

	/** A button that runs a command when clicked. */
	static MutableComponent button(String text, String command, Component hover) {
		return Component.literal("[" + text + "]").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(hover)));
	}

	static MutableComponent traitLines(List<String> ids) {
		MutableComponent out = Component.empty();
		for (String id : ids) {
			Trait trait = Traits.get(id);
			if (trait == null) continue;
			ChatFormatting color = switch (trait.kind()) {
				case BOON -> ChatFormatting.DARK_GREEN;
				case FLAW -> ChatFormatting.DARK_RED;
				case QUIRK -> ChatFormatting.DARK_AQUA;
				case LEGENDARY -> ChatFormatting.GOLD;
			};
			out.append(trait.displayName().withStyle(s -> s.withColor(color).withBold(false))).append("\n");
		}
		return out;
	}

	// --- Sections -----------------------------------------------------------------------------

	private static Component cover(Lineage lineage, List<Component> contents) {
		MutableComponent page = Component.empty()
			.append(faint("The Chronicle of\n"))
			.append(Component.literal("House " + lineage.house + "\n\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
			.append(faint("Head of the house:\n"))
			.append(Component.literal(lineage.current.name() + "\n").withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD))
			.append(faint("Generation " + Names.numeral(lineage.current.generation()) + ", renown " + lineage.renown + "\n\n"))
			.append(Component.literal("Contents\n").withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD));
		contents.forEach(page::append);
		return page;
	}

	private static List<Component> duties(Lineage lineage) {
		List<Quest> quests = lineage.quests.stream().filter(q -> !Questlines.isOathStep(q) && q.type != Quest.Type.PREPARATION).toList();
		List<CompletedQuest> done = lineage.completedThisGeneration().stream()
			.filter(c -> c.type() != Quest.Type.QUESTLINE && c.type() != Quest.Type.PREPARATION).toList();
		List<Component> pages = new ArrayList<>();
		MutableComponent page = heading("Duties");
		int used = 20;
		if (quests.isEmpty() && done.isEmpty()) {
			page.append(body("The family owes nothing to anyone, for now."));
		}
		for (Quest quest : quests) {
			String progress = quest.required > 1 ? " " + quest.progress / quest.displayScale + "/" + quest.required / quest.displayScale : "";
			int length = quest.title.length() + quest.task.getString().length() + progress.length() + 10;
			if (used + length > PAGE_BUDGET) {
				pages.add(page);
				page = heading("Duties");
				used = 20;
			}
			String marker = quest.type == Quest.Type.BLOOD_DEBT ? "⚔ " : "";
			page.append(Component.literal(marker + quest.title + "\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)
					.withHoverEvent(new HoverEvent.ShowText(Component.literal("Reward: " + Quests.describe(quest.reward))))))
				.append(quest.task.copy().withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withBold(false)))
				.append(faint(progress + "\n\n"));
			used += length;
		}
		for (CompletedQuest quest : done) {
			int length = quest.title().length() + quest.task().getString().length() + 10;
			if (used + length > PAGE_BUDGET) {
				pages.add(page);
				page = heading("Duties");
				used = 20;
			}
			page.append(Component.literal("✔ ").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(false).withStrikethrough(false)))
				.append(struck(quest.title() + "\n"))
				.append(struck(quest.task().getString()))
				.append("\n\n");
			used += length;
		}
		pages.add(page);
		return pages;
	}

	/** Grey, crossed-out text for finished quests. */
	static MutableComponent struck(String text) {
		return Component.literal(text).withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false).withStrikethrough(true));
	}

	private static Component head(Lineage lineage) {
		MutableComponent page = heading("Head of the House")
			.append(Component.literal(lineage.fullName() + "\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(true)))
			.append(faint("Generation " + Names.numeral(lineage.current.generation()) + ", born day " + lineage.current.bornDay() + "\n\n"))
			.append(faint("Born with:\n"))
			.append(traitLines(lineage.current.traits()));
		if (!lineage.legacies.isEmpty()) {
			page.append(faint("\nLegacy of the house:\n")).append(traitLines(lineage.legacies));
		}
		return page;
	}

	private static List<Component> familyTree(Lineage lineage) {
		List<Component> lines = new ArrayList<>();
		for (Ancestor a : lineage.ancestors) {
			lines.add(Component.empty()
				.append(faint(Names.numeral(a.generation()) + ". "))
				.append(Component.literal(a.fullName()).withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)
					.withHoverEvent(new HoverEvent.ShowText(a.deathMessage()))))
				.append(faint("\n   day " + a.bornDay() + "-" + a.diedDay() + "\n")));
		}
		lines.add(Component.empty()
			.append(faint(Names.numeral(lineage.current.generation()) + ". "))
			.append(Component.literal(lineage.current.name()).withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD))
			.append(faint("\n   living\n")));

		List<Component> pages = new ArrayList<>();
		for (int start = 0; start < lines.size(); start += ANCESTORS_PER_PAGE) {
			MutableComponent page = heading("Family Tree");
			lines.subList(start, Math.min(lines.size(), start + ANCESTORS_PER_PAGE)).forEach(page::append);
			pages.add(page);
		}
		return pages;
	}

	private static List<Component> entries(Lineage lineage) {
		List<Component> pages = new ArrayList<>();
		MutableComponent page = null;
		int used = 0;
		for (ChronicleEntry entry : lineage.chronicle) {
			int length = entry.text().getString().length() + 12;
			if (page == null || used + length > PAGE_BUDGET) {
				if (page != null) pages.add(page);
				page = Component.empty();
				used = 0;
			}
			page.append(Component.literal("Day " + entry.day() + "\n").withStyle(ChatFormatting.DARK_RED))
				.append(entry.text().copy().withStyle(ChatFormatting.BLACK))
				.append("\n\n");
			used += length;
		}
		if (page != null) pages.add(page);
		return pages;
	}
}
