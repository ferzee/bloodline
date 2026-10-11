package io.github.fersyvanderzee.bloodline;

import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.ChronicleEntry;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

import java.util.ArrayList;
import java.util.List;

/**
 * The family chronicle is a vanilla written book, so players don't need the mod installed.
 * Its pages are rewritten from the family's history every time it is opened.
 */
public final class Chronicle {
	private static final String KEY = "bloodline_chronicle";
	private static final int PAGE_BUDGET = 190;
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

	/** Refreshes the book's pages just before vanilla opens it. */
	public static void refresh(ServerPlayer player, ItemStack book) {
		Lineage lineage = Lineages.get(player);
		if (lineage != null) {
			book.set(DataComponents.WRITTEN_BOOK_CONTENT, build(lineage));
		}
	}

	static WrittenBookContent build(Lineage lineage) {
		List<Component> pages = new ArrayList<>();
		pages.add(cover(lineage));
		pages.addAll(familyTree(lineage));
		pages.addAll(entries(lineage));
		if (pages.size() > MAX_PAGES) {
			pages = new ArrayList<>(pages.subList(0, 1 + (int) Math.ceil((lineage.ancestors.size() + 1) / (double) ANCESTORS_PER_PAGE)));
			List<Component> recent = entries(lineage);
			pages.addAll(recent.subList(Math.max(0, recent.size() - (MAX_PAGES - pages.size())), recent.size()));
		}
		String title = "Chronicle of " + lineage.house;
		if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
		return new WrittenBookContent(Filterable.passThrough(title), "House " + lineage.house, 0,
			pages.stream().map(Filterable::passThrough).toList(), true);
	}

	private static Component cover(Lineage lineage) {
		return Component.empty()
			.append(Component.literal("\nThe Chronicle of\n").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal("House " + lineage.house + "\n\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
			.append(Component.literal("Head of the house:\n").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(lineage.current.name() + "\n").withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD))
			.append(Component.literal("Generation " + Names.numeral(lineage.current.generation()) + ", born day " + lineage.current.bornDay() + "\n\n").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal("Born with:\n").withStyle(ChatFormatting.DARK_GRAY))
			.append(bookTraits(lineage.current.traits()));
	}

	private static Component bookTraits(List<String> ids) {
		MutableComponent out = Component.empty();
		for (String id : ids) {
			Trait trait = Traits.get(id);
			if (trait == null) continue;
			ChatFormatting color = switch (trait.kind()) {
				case BOON -> ChatFormatting.DARK_GREEN;
				case FLAW -> ChatFormatting.DARK_RED;
				case QUIRK -> ChatFormatting.DARK_AQUA;
			};
			out.append(trait.displayName().withStyle(color)).append("\n");
		}
		return out;
	}

	private static List<Component> familyTree(Lineage lineage) {
		List<Component> lines = new ArrayList<>();
		for (Ancestor a : lineage.ancestors) {
			lines.add(Component.empty()
				.append(Component.literal(Names.numeral(a.generation()) + ". ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Component.literal(a.fullName()).withStyle(s -> s.withColor(ChatFormatting.BLACK)
					.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(a.deathMessage()))))
				.append(Component.literal("\n   day " + a.bornDay() + "-" + a.diedDay() + "\n").withStyle(ChatFormatting.DARK_GRAY)));
		}
		lines.add(Component.empty()
			.append(Component.literal(Names.numeral(lineage.current.generation()) + ". ").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.literal(lineage.current.name()).withStyle(ChatFormatting.BLACK, ChatFormatting.BOLD))
			.append(Component.literal("\n   living\n").withStyle(ChatFormatting.DARK_GRAY)));

		List<Component> pages = new ArrayList<>();
		for (int start = 0; start < lines.size(); start += ANCESTORS_PER_PAGE) {
			MutableComponent page = Component.literal("Family Tree\n\n").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
			MutableComponent body = Component.empty().withStyle(s -> s.withBold(false));
			lines.subList(start, Math.min(lines.size(), start + ANCESTORS_PER_PAGE)).forEach(body::append);
			pages.add(page.append(body));
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
