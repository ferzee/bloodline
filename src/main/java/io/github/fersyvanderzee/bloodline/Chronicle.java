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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
import java.util.UUID;
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
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, build(player, lineage));
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
			book.set(DataComponents.WRITTEN_BOOK_CONTENT, build(player, lineage));
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

	static WrittenBookContent build(ServerPlayer viewer, Lineage lineage) {
		// While an heir is still to be chosen, the choice is the first thing in the book.
		boolean choosing = lineage.awaitingHeir && !lineage.candidates.isEmpty();
		int offset = choosing ? 1 : 0;
		List<Component> mainQuest = Questlines.bookPages(lineage, 2 + offset, lineage.isHead(viewer.getUUID()));
		List<Component> duties = duties(lineage);
		List<Component> head = List.of(head(lineage), members(viewer.level().getServer(), lineage), sacrifice(viewer, lineage));
		List<Component> tree = familyTree(lineage);
		List<Component> history = entries(lineage);
		int room = MAX_PAGES - 1 - mainQuest.size() - duties.size() - head.size() - tree.size();
		if (history.size() > room) history = history.subList(Math.max(0, history.size() - Math.max(room, 0)), history.size());

		// Page numbers are 1-based; the cover is page 1 and the main quest always starts on page 2.
		int mainPage = 2 + offset;
		int dutiesPage = mainPage + mainQuest.size();
		int headPage = dutiesPage + duties.size();
		int treePage = headPage + head.size();
		int historyPage = treePage + tree.size();
		long dutyCount = lineage.allQuests().stream().filter(q -> !Questlines.isOathStep(q) && q.type != Quest.Type.PREPARATION).count();

		List<Component> pages = new ArrayList<>();
		List<Component> contents = new ArrayList<>();
		if (choosing) contents.add(link(Text.t("book.heir.title"), 2));
		contents.addAll(List.of(
			link(Text.t("book.contents.main"), mainPage),
			link(dutyCount > 0 ? Text.t("book.contents.duties_count", dutyCount) : Text.t("book.contents.duties"), dutiesPage),
			link(Text.t("book.contents.house"), headPage),
			// The Sacrifice page is the last of the "You & the House" pages.
			link(Text.t("book.contents.sacrifice"), headPage + head.size() - 1),
			link(Text.t("book.contents.tree"), treePage),
			link(Text.t("book.contents.history"), historyPage)));
		pages.add(cover(lineage, contents));
		if (choosing) pages.add(Heirs.bookPage(lineage));
		pages.addAll(mainQuest);
		pages.addAll(duties);
		pages.addAll(head);
		pages.addAll(tree);
		pages.addAll(history.isEmpty() ? List.of(heading(Text.t("book.contents.history")).append(body(Text.t("book.history.empty")))) : history);

		String title = "Chronicle of " + lineage.house.name;
		if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
		return new WrittenBookContent(Filterable.passThrough(title), "House " + lineage.house.name, 0,
			pages.stream().map(Filterable::passThrough).toList(), true);
	}

	// --- Shared page styling ------------------------------------------------------------------

	static MutableComponent heading(Component text) {
		return Component.empty().append(text).append("\n\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
	}

	static MutableComponent body(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false));
	}

	static MutableComponent faint(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withBold(false));
	}

	/** For text that needs no translation, such as numbers and line breaks. */
	static MutableComponent faint(String text) {
		return faint(Component.literal(text));
	}

	/** A line that jumps to another page of the book. */
	static MutableComponent link(Component text, int page) {
		return Component.literal("» ").append(text).append("\n").withStyle(s -> s.withColor(ChatFormatting.DARK_BLUE).withBold(false)
			.withClickEvent(new ClickEvent.ChangePage(page))
			.withHoverEvent(new HoverEvent.ShowText(Text.t("book.go_to_page", page))));
	}

	/** A button that runs a command when clicked; the label includes its brackets ("[Choose]"). */
	static MutableComponent button(Component label, String command, Component hover) {
		return Component.empty().append(label).withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(true)
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
			.append(faint(Text.t("book.cover.chronicle_of"))).append("\n")
			.append(Text.t("book.cover.house", lineage.house.name).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)).append("\n\n")
			.append(Component.literal(lineage.current.name() + "\n").withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD))
			.append(faint(Text.t("book.cover.generation", Names.numeral(lineage.current.generation()), lineage.house.renown))).append("\n\n")
			.append(Text.t("book.cover.contents").withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD)).append("\n");
		contents.forEach(page::append);
		return page;
	}

	private static List<Component> duties(Lineage lineage) {
		List<Quest> quests = lineage.allQuests().stream().filter(q -> !Questlines.isOathStep(q) && q.type != Quest.Type.PREPARATION).toList();
		List<CompletedQuest> done = lineage.completedThisGeneration().stream()
			.filter(c -> c.type() != Quest.Type.QUESTLINE && c.type() != Quest.Type.PREPARATION).toList();
		List<Component> pages = new ArrayList<>();
		MutableComponent page = heading(Text.t("book.contents.duties"));
		int used = 20;
		if (quests.isEmpty() && done.isEmpty()) {
			page.append(body(Text.t("book.duties.none")));
		}
		for (Quest quest : quests) {
			String progress = quest.required > 1 ? " " + quest.progress / quest.displayScale + "/" + quest.required / quest.displayScale : "";
			int length = quest.title.getString().length() + quest.task.getString().length() + progress.length() + 10;
			if (used + length > PAGE_BUDGET) {
				pages.add(page);
				page = heading(Text.t("book.contents.duties"));
				used = 20;
			}
			String marker = quest.type == Quest.Type.BLOOD_DEBT ? "⚔ " : "";
			page.append(Component.literal(marker).append(quest.title).append("\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)
					.withHoverEvent(new HoverEvent.ShowText(Text.t("quest.reward", Quests.describe(quest.reward))))))
				.append(quest.task.copy().withStyle(s -> s.withColor(ChatFormatting.DARK_GRAY).withBold(false)))
				.append(faint(progress + "\n\n"));
			used += length;
		}
		for (CompletedQuest quest : done) {
			int length = quest.title().getString().length() + quest.task().getString().length() + 10;
			if (used + length > PAGE_BUDGET) {
				pages.add(page);
				page = heading(Text.t("book.contents.duties"));
				used = 20;
			}
			page.append(Component.literal("✔ ").withStyle(s -> s.withColor(ChatFormatting.DARK_GREEN).withBold(false).withStrikethrough(false)))
				.append(struck(quest.title())).append("\n")
				.append(struck(quest.task()))
				.append("\n\n");
			used += length;
		}
		pages.add(page);
		return pages;
	}

	/** Grey, crossed-out text for finished quests. */
	static MutableComponent struck(Component text) {
		return Component.empty().append(text).withStyle(s -> s.withColor(ChatFormatting.GRAY).withBold(false).withStrikethrough(true));
	}

	private static Component members(MinecraftServer server, Lineage lineage) {
		BloodlineState state = BloodlineState.get(server);
		MutableComponent page = heading(Text.t("book.members.title"));
		for (UUID id : lineage.house.members) {
			Lineage member = state.get(id);
			if (member == null) continue;
			boolean head = lineage.house.head.equals(id);
			page.append(Component.literal(member.current.name() + (head ? " ♛" : "") + "\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(head)
				.withHoverEvent(new HoverEvent.ShowText(head ? Text.t("book.members.head", member.fullName()) : Component.literal(member.fullName())))));
			page.append(faint("   ")).append(faint(Text.t("book.members.generation", Names.numeral(member.current.generation())))).append("\n");
		}
		if (lineage.house.steward.isPresent()) {
			var steward = lineage.house.steward.get();
			page.append("\n").append(faint(Text.t("book.members.steward", steward.name, steward.home.pos().getX() + ", " + steward.home.pos().getZ(),
				Stewards.vaultSlots(lineage.house.renown))));
		} else if (lineage.house.members.size() == 1) {
			page.append("\n").append(faint(Text.t("book.members.invite_hint")));
		}
		return page;
	}

	private static Component sacrifice(ServerPlayer viewer, Lineage lineage) {
		Sacrifice.Conditions now = Sacrifice.check(viewer, lineage);
		MutableComponent page = heading(Text.t("book.sacrifice.title"))
			.append(body(Text.t("book.sacrifice.intro"))).append("\n\n");
		page.append(condition(now.actDone(), Text.t("book.sacrifice.act")));
		page.append(condition(now.night(), Text.t("book.sacrifice.night")));
		page.append(condition(now.fullMoon(), now.fullMoon() ? Text.t("book.sacrifice.moon") : Text.t("book.sacrifice.moon_in", now.nightsUntilFullMoon())));
		page.append(condition(now.place().isPresent(), now.place().map(p -> Text.t("book.sacrifice.at", p))
			.orElse(Text.t(now.founder() ? "book.sacrifice.place_founder" : "book.sacrifice.place"))));
		page.append("\n").append(faint(Text.t("book.sacrifice.reward", Sacrifice.renown(), lineage.house.sacrifices, Sacrifice.forBloodSworn()))).append("\n\n");
		// Always offer the button: if something is missing, the command says exactly what.
		page.append(button(Text.t("button.offer_life"), "/bloodline sacrifice", Text.t(now.met() ? "book.sacrifice.hover_ready" : "book.sacrifice.hover_missing")));
		return page;
	}

	private static Component condition(boolean met, Component text) {
		return Component.literal(met ? "✔ " : "✘ ").append(text).append("\n")
			.withStyle(s -> s.withColor(met ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED).withBold(false));
	}

	private static Component head(Lineage lineage) {
		MutableComponent page = heading(Text.t("book.character.title"))
			.append(Component.literal(lineage.fullName() + "\n").withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(true)))
			.append(faint(Text.t("book.character.generation", Names.numeral(lineage.current.generation()), lineage.current.bornDay()))).append("\n\n")
			.append(faint(Text.t("book.character.born_with"))).append("\n")
			.append(traitLines(lineage.current.traits()));
		if (!lineage.house.legacies.isEmpty()) {
			page.append("\n").append(faint(Text.t("book.character.legacy"))).append("\n").append(traitLines(lineage.house.legacies));
		}
		return page;
	}

	private static List<Component> familyTree(Lineage lineage) {
		List<Component> lines = new ArrayList<>();
		for (Ancestor a : lineage.ancestors) {
			lines.add(Component.empty()
				.append(faint(Names.numeral(a.generation()) + ". "))
				.append(a.fullName().withStyle(s -> s.withColor(ChatFormatting.BLACK).withBold(false)
					.withHoverEvent(new HoverEvent.ShowText(a.deathMessage()))))
				.append("\n   ").append(faint(Text.t("book.tree.days", a.bornDay(), a.diedDay()))).append("\n"));
		}
		lines.add(Component.empty()
			.append(faint(Names.numeral(lineage.current.generation()) + ". "))
			.append(Component.literal(lineage.current.name()).withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD))
			.append("\n   ").append(faint(Text.t("book.tree.living"))).append("\n"));

		List<Component> pages = new ArrayList<>();
		for (int start = 0; start < lines.size(); start += ANCESTORS_PER_PAGE) {
			MutableComponent page = heading(Text.t("book.contents.tree"));
			lines.subList(start, Math.min(lines.size(), start + ANCESTORS_PER_PAGE)).forEach(page::append);
			pages.add(page);
		}
		return pages;
	}

	private static List<Component> entries(Lineage lineage) {
		List<Component> pages = new ArrayList<>();
		MutableComponent page = null;
		int used = 0;
		for (ChronicleEntry entry : lineage.house.chronicle) {
			int length = entry.text().getString().length() + 12;
			if (page == null || used + length > PAGE_BUDGET) {
				if (page != null) pages.add(page);
				page = Component.empty();
				used = 0;
			}
			page.append(Text.t("book.history.day", entry.day()).withStyle(ChatFormatting.DARK_RED)).append("\n")
				.append(entry.text().copy().withStyle(ChatFormatting.BLACK))
				.append("\n\n");
			used += length;
		}
		if (page != null) pages.add(page);
		return pages;
	}
}
