package io.github.fersyvanderzee.bloodline.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.PageButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import com.mojang.blaze3d.platform.InputConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The Chronicle as an open book: two pages side by side in a leather binding, with bookmark tabs for its
 * sections (taken from the links on its contents page). The pages are the same ones a plain written book
 * shows, so players without the mod read the very same Chronicle as an ordinary book.
 */
public class ChronicleScreen extends Screen {
	private static final Identifier COVER = sprite("book_cover");
	private static final Identifier PAGE_LEFT = sprite("page_left");
	private static final Identifier PAGE_RIGHT = sprite("page_right");
	private static final Identifier TAB = sprite("tab");
	private static final Identifier TAB_ACTIVE = sprite("tab_active");
	private static final Identifier RIBBON = sprite("ribbon");
	private static final Identifier FLOURISH = sprite("flourish");

	/** The same text area as a vanilla book page, so pages written for the book fit exactly. */
	private static final int TEXT_WIDTH = 114;
	private static final int LINES = 14;
	private static final int PAGE_PAD_X = 14;
	private static final int PAGE_PAD_TOP = 16;
	private static final int PAGE_WIDTH = TEXT_WIDTH + PAGE_PAD_X * 2;
	private static final int PAGE_HEIGHT = PAGE_PAD_TOP + LINES * 9 + 24;
	private static final int COVER_MARGIN = 8;
	private static final int BOOK_WIDTH = COVER_MARGIN * 2 + PAGE_WIDTH * 2;
	private static final int BOOK_HEIGHT = COVER_MARGIN * 2 + PAGE_HEIGHT;
	private static final int TAB_HEIGHT = 16;
	private static final int TAB_GAP = 2;
	private static final Style PAGE_STYLE = Style.EMPTY.withoutShadow().withColor(0x2B1D10);
	private static final int INK = 0xFF3B2A1A;
	private static final int FADED_INK = 0xFF7A6040;

	private final List<Component> pages;
	private final List<Bookmark> bookmarks;
	/**
	 * The book laid out in spreads: each section starts on a left-hand page, so a section ending on a left page
	 * leaves the right one blank. Each entry is a page of the book, or -1 for a blank page.
	 */
	private final List<Integer> slots;
	/** The left-hand slot of the spread being read (always even). */
	private int spread;
	private final List<List<FormattedCharSequence>> cache = new ArrayList<>();
	private PageButton back;
	private PageButton forward;

	/** A section of the book: its name and the page it starts on (0-based). */
	private record Bookmark(Component label, int page) {
	}

	public ChronicleScreen(List<Component> pages) {
		super(Component.translatable("book.view.title"));
		this.pages = pages.isEmpty() ? List.of(Component.empty()) : pages;
		this.bookmarks = bookmarksFrom(this.pages.getFirst());
		this.slots = layout(this.pages.size(), bookmarks);
		for (int i = 0; i < this.pages.size(); i++) cache.add(null);
	}

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath("bloodline", name);
	}

	/** The contents page links to each section; those links become the bookmarks. */
	private static List<Bookmark> bookmarksFrom(Component contents) {
		// A link arrives in parts ("» ", its words, a line break), all pointing at the same page.
		java.util.LinkedHashMap<Integer, StringBuilder> labels = new java.util.LinkedHashMap<>();
		contents.visit((style, text) -> {
			if (style.getClickEvent() instanceof ClickEvent.ChangePage(int page)) {
				labels.computeIfAbsent(page - 1, p -> new StringBuilder()).append(text);
			}
			return Optional.empty();
		}, Style.EMPTY);
		List<Bookmark> found = new ArrayList<>();
		labels.forEach((page, label) -> {
			String name = label.toString().replace("»", "").replace("\n", " ").trim();
			if (!name.isEmpty()) found.add(new Bookmark(Component.literal(name), page));
		});
		return found;
	}

	private static List<Integer> layout(int pageCount, List<Bookmark> bookmarks) {
		List<Integer> starts = new ArrayList<>(List.of(0));
		for (Bookmark mark : bookmarks) if (mark.page() > starts.getLast() && mark.page() < pageCount) starts.add(mark.page());
		List<Integer> slots = new ArrayList<>();
		for (int i = 0; i < starts.size(); i++) {
			int end = i + 1 < starts.size() ? starts.get(i + 1) : pageCount;
			for (int page = starts.get(i); page < end; page++) slots.add(page);
			if (slots.size() % 2 == 1) slots.add(-1);
		}
		return slots;
	}

	/** The book page in a slot of the current layout, or -1 for a blank page or past the end. */
	private int pageAt(int slot) {
		return slot >= 0 && slot < slots.size() ? slots.get(slot) : -1;
	}

	// --- Layout ---------------------------------------------------------------------------------------

	private int tabWidth() {
		int widest = 0;
		for (Bookmark mark : bookmarks) widest = Math.max(widest, font.width(mark.label()));
		return Math.min(96, widest + 14);
	}

	private int bookLeft() {
		int total = BOOK_WIDTH + (bookmarks.isEmpty() ? 0 : tabWidth() - 4);
		return Math.max(2, (width - total) / 2);
	}

	private int bookTop() {
		return Math.max(2, (height - BOOK_HEIGHT) / 2);
	}

	private int pageLeft(int side) {
		return bookLeft() + COVER_MARGIN + side * PAGE_WIDTH;
	}

	private int pageTop() {
		return bookTop() + COVER_MARGIN;
	}

	@Override
	protected void init() {
		int arrowsY = pageTop() + PAGE_HEIGHT - 20;
		back = addRenderableWidget(new PageButton(pageLeft(0) + 10, arrowsY, false, b -> turn(-2), true));
		forward = addRenderableWidget(new PageButton(pageLeft(1) + PAGE_WIDTH - 10 - 23, arrowsY, true, b -> turn(2), true));
		updateArrows();
	}

	@Override
	public void added() {
		Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
	}

	private void updateArrows() {
		if (back == null) return;
		back.visible = spread > 0;
		forward.visible = spread + 2 < slots.size();
	}

	private void turn(int by) {
		int target = Math.max(0, Math.min(slots.size() - 1, spread + by)) & ~1;
		if (target == spread) return;
		spread = target;
		updateArrows();
	}

	/** Opens the spread that shows a page of the book (0-based). */
	void goTo(int page) {
		int slot = Math.max(0, slots.indexOf(Math.max(0, Math.min(pages.size() - 1, page))));
		int target = slot & ~1;
		if (target == spread) return;
		spread = target;
		updateArrows();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean isInGameUi() {
		return true;
	}

	// --- Drawing --------------------------------------------------------------------------------------

	private List<FormattedCharSequence> lines(int page) {
		if (page < 0 || page >= pages.size()) return List.of();
		if (cache.get(page) == null) {
			FormattedText text = ComponentUtils.mergeStyles(ScrollScreen.ink(pages.get(page)), PAGE_STYLE);
			cache.set(page, font.split(text, TEXT_WIDTH));
		}
		return cache.get(page);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int left = bookLeft(), top = bookTop();
		// Bookmarks first, so the book covers their inner ends.
		int tabX = left + BOOK_WIDTH - 6, tabY = top + 14;
		for (Bookmark mark : bookmarks) {
			boolean current = sectionAt(pageAt(spread)) == mark;
			boolean hover = mouseX >= tabX && mouseX < tabX + tabWidth() && mouseY >= tabY && mouseY < tabY + TAB_HEIGHT;
			int out = current ? 4 : hover ? 2 : 0;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, current ? TAB_ACTIVE : TAB, tabX + out, tabY, tabWidth(), TAB_HEIGHT);
			graphics.text(font, ink(mark.label()), tabX + out + 9, tabY + 4, INK, false);
			tabY += TAB_HEIGHT + TAB_GAP;
		}

		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, COVER, left, top, BOOK_WIDTH, BOOK_HEIGHT);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PAGE_LEFT, pageLeft(0), pageTop(), PAGE_WIDTH, PAGE_HEIGHT);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PAGE_RIGHT, pageLeft(1), pageTop(), PAGE_WIDTH, PAGE_HEIGHT);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, RIBBON, left + BOOK_WIDTH / 2 - 3, top - 2, 6, 40);

		ActiveTextCollector text = graphics.textRenderer(GuiGraphicsExtractor.HoveredTextEffects.TOOLTIP_AND_CURSOR);
		visitText(text);
		for (int side = 0; side < 2; side++) {
			int page = pageAt(spread + side);
			if (page < 0) {
				// A blank page at the end of a section: just an ornament.
				if (spread + side < slots.size()) graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FLOURISH, pageLeft(side) + PAGE_WIDTH / 2 - 48,
					pageTop() + PAGE_HEIGHT / 2 - 4, 96, 7);
				continue;
			}
			graphics.text(font, Component.literal("- " + (page + 1) + " -"), pageLeft(side) + PAGE_WIDTH / 2 - font.width("- " + (page + 1) + " -") / 2,
				pageTop() + PAGE_HEIGHT - 16, FADED_INK, false);
		}
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private void visitText(ActiveTextCollector collector) {
		for (int side = 0; side < 2; side++) {
			List<FormattedCharSequence> page = lines(pageAt(spread + side));
			int x = pageLeft(side) + PAGE_PAD_X, y = pageTop() + PAGE_PAD_TOP;
			for (int i = 0; i < Math.min(LINES, page.size()); i++) collector.accept(TextAlignment.LEFT, x, y + i * 9, page.get(i));
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
	}

	/** The section a page belongs to: the last bookmark at or before it. */
	private Bookmark sectionAt(int page) {
		Bookmark section = null;
		for (Bookmark mark : bookmarks) if (mark.page() <= page) section = mark;
		return page < pages.size() ? section : null;
	}

	private static Component ink(Component label) {
		return label.copy().withStyle(s -> s.withColor(INK & 0xFFFFFF).withClickEvent(null).withHoverEvent(null));
	}

	// --- Input ----------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int tabX = bookLeft() + BOOK_WIDTH - 6, tabY = bookTop() + 14;
		for (Bookmark mark : bookmarks) {
			if (event.x() >= tabX && event.x() < tabX + tabWidth() + 4 && event.y() >= tabY && event.y() < tabY + TAB_HEIGHT) {
				Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
				goTo(mark.page());
				return true;
			}
			tabY += TAB_HEIGHT + TAB_GAP;
		}
		ActiveTextCollector.ClickableStyleFinder finder = new ActiveTextCollector.ClickableStyleFinder(font, (int) event.x(), (int) event.y());
		visitText(finder);
		Style clicked = finder.result();
		if (clicked != null && clicked.getClickEvent() != null) {
			switch (clicked.getClickEvent()) {
				case ClickEvent.ChangePage(int page) -> {
					Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 1f));
					goTo(page - 1);
				}
				case ClickEvent.RunCommand(String command) -> {
					if (minecraft.player != null) clickCommandAction(minecraft.player, command, null);
				}
				default -> defaultHandleGameClickEvent(clicked.getClickEvent(), minecraft, this);
			}
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.shortcutKey();
		if (key == InputConstants.KEYCODE_LEFT || key == InputConstants.KEYCODE_PAGEUP) {
			if (back.visible) back.onPress(event);
			return true;
		}
		if (key == InputConstants.KEYCODE_RIGHT || key == InputConstants.KEYCODE_PAGEDOWN) {
			if (forward.visible) forward.onPress(event);
			return true;
		}
		return super.keyPressed(event);
	}
}
