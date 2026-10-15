package io.github.fersyvanderzee.bloodline.client;

import io.github.fersyvanderzee.bloodline.ScrollPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerFaceExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A Bloodline choice drawn as a parchment scroll: a wax seal with an emblem, the title in ink, the text, cards
 * to pick from (such as the children who could become heir) and leather buttons. Clicking a card or button
 * sends its /bloodline command, just like the buttons in chat.
 */
public class ScrollScreen extends Screen {
	private static final Identifier SCROLL = sprite("scroll");
	private static final Identifier CARD = sprite("card");
	private static final Identifier CARD_HOVER = sprite("card_hover");
	private static final Identifier SEAL = sprite("seal");
	private static final Identifier FLOURISH = sprite("flourish");

	private static final int INK = 0xFF3B2A1A;
	private static final int FADED_INK = 0xFF6E5638;
	private static final int PAPER_TEXT = 0xFFF3E6C8;
	private static final int MARGIN = 24;
	private static final int GAP = 6;
	private static final int CARD_GAP = 8;
	private static final int BUTTON_HEIGHT = 20;
	private static final int PORTRAIT = 32;
	private static final int OPEN_TICKS = 6;

	private final ScrollPayload scroll;
	private final List<Area> areas = new ArrayList<>();
	private int ticksOpen;

	/** Something clickable on the scroll, where it was last drawn. */
	private record Area(int x, int y, int width, int height, String command, Component tooltip) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + width && my >= y && my < y + height;
		}
	}

	public ScrollScreen(ScrollPayload scroll) {
		super(scroll.title());
		this.scroll = scroll;
	}

	private static Identifier sprite(String name) {
		return Identifier.fromNamespaceAndPath("bloodline", name);
	}

	@Override
	protected void init() {
		super.init();
		if (ticksOpen == 0) Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.BOOK_PAGE_TURN, 0.9f));
	}

	@Override
	public void tick() {
		ticksOpen++;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// --- Layout -------------------------------------------------------------------------------------

	private int panelWidth() {
		return Math.min(scroll.cards().isEmpty() ? 330 : 380, width - 16);
	}

	private int contentWidth() {
		return panelWidth() - MARGIN * 2;
	}

	private List<FormattedCharSequence> wrap(Component text, int width) {
		return font.split(ink(text), width);
	}

	private int bodyHeight() {
		int h = 0;
		for (Component line : scroll.body()) h += wrap(line, contentWidth()).size() * font.lineHeight + 3;
		return h;
	}

	private int cardWidth() {
		int n = scroll.cards().size();
		return Math.min(112, (contentWidth() - (n - 1) * CARD_GAP) / Math.max(1, n));
	}

	private int cardHeight() {
		int tallest = 0;
		for (ScrollPayload.Card card : scroll.cards()) {
			int lines = 0;
			for (Component line : card.lines()) lines += wrap(line, cardWidth() - 10).size();
			tallest = Math.max(tallest, lines);
		}
		return 8 + PORTRAIT + 6 + font.lineHeight + 2 + font.lineHeight + 6 + tallest * font.lineHeight + 8;
	}

	private List<List<ScrollPayload.Choice>> buttonRows() {
		List<List<ScrollPayload.Choice>> rows = new ArrayList<>();
		int total = 0;
		for (ScrollPayload.Choice choice : scroll.choices()) total += buttonWidth(choice) + GAP;
		if (total - GAP <= contentWidth()) {
			if (!scroll.choices().isEmpty()) rows.add(scroll.choices());
		} else {
			for (ScrollPayload.Choice choice : scroll.choices()) rows.add(List.of(choice));
		}
		return rows;
	}

	private int buttonWidth(ScrollPayload.Choice choice) {
		return Math.max(80, font.width(choice.label()) + 24);
	}

	private int footerHeight() {
		return scroll.footer().getString().isEmpty() ? 0 : wrap(scroll.footer(), contentWidth()).size() * font.lineHeight + GAP;
	}

	private int panelHeight() {
		int h = MARGIN + 6;                          // room for the seal
		h += font.lineHeight * 2 + GAP;              // title, drawn twice as large
		h += 7 + GAP;                                // flourish
		h += bodyHeight();
		if (!scroll.cards().isEmpty()) h += GAP + cardHeight();
		int rows = buttonRows().size();
		if (rows > 0) h += GAP * 2 + rows * (BUTTON_HEIGHT + 4) - 4;
		h += GAP + footerHeight();
		return h + MARGIN - 12;
	}

	// --- Drawing --------------------------------------------------------------------------------------

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
		areas.clear();
		int pw = panelWidth(), ph = panelHeight();
		// Shrink the whole scroll if the window is too small for it.
		float fit = Math.min(1f, (height - 12f) / (ph + 12f));
		float opening = Math.min(1f, (ticksOpen + partialTick) / OPEN_TICKS);
		float scale = fit * (0.94f + 0.06f * (1 - (1 - opening) * (1 - opening)));
		float cx = width / 2f, cy = height / 2f;
		graphics.pose().pushMatrix();
		graphics.pose().translate(cx, cy);
		graphics.pose().scale(scale, scale);
		graphics.pose().translate(-pw / 2f, -ph / 2f);
		// Mouse position in the scroll's own coordinates.
		double mx = (mouseX - cx) / scale + pw / 2f, my = (mouseY - cy) / scale + ph / 2f;
		Component tooltip = draw(graphics, pw, ph, mx, my);
		graphics.pose().popMatrix();
		if (tooltip != null && !tooltip.getString().isEmpty()) graphics.setTooltipForNextFrame(font, tooltip, mouseX, mouseY);
		lastScale = scale;
		lastOrigin = new double[]{cx - pw / 2f * scale, cy - ph / 2f * scale};
	}

	private float lastScale = 1;
	private double[] lastOrigin = {0, 0};

	/** Draws the scroll at (0, 0) and returns the tooltip under the mouse, if any. */
	private Component draw(GuiGraphicsExtractor graphics, int pw, int ph, double mx, double my) {
		Component tooltip = null;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SCROLL, 0, 0, pw, ph);

		// The wax seal and its emblem, half over the top edge.
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SEAL, pw / 2 - 14, -10, 28, 28);
		if (!scroll.icon().isEmpty()) graphics.item(scroll.icon(), pw / 2 - 8, -4);

		int y = MARGIN + 6;
		graphics.pose().pushMatrix();
		graphics.pose().translate(pw / 2f, y);
		graphics.pose().scale(2, 2);
		Component title = ink(scroll.title().copy().withStyle(s -> s.withBold(false)));
		graphics.text(font, title, -font.width(title) / 2, 0, INK, false);
		graphics.pose().popMatrix();
		y += font.lineHeight * 2 + GAP;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FLOURISH, pw / 2 - 48, y, 96, 7);
		y += 7 + GAP;

		for (Component line : scroll.body()) {
			for (FormattedCharSequence part : wrap(line, contentWidth())) {
				graphics.text(font, part, (pw - font.width(part)) / 2, y, INK, false);
				y += font.lineHeight;
			}
			y += 3;
		}

		if (!scroll.cards().isEmpty()) {
			y += GAP;
			int cw = cardWidth(), ch = cardHeight(), n = scroll.cards().size();
			int x = (pw - (n * cw + (n - 1) * CARD_GAP)) / 2;
			for (ScrollPayload.Card card : scroll.cards()) {
				boolean hover = mx >= x && mx < x + cw && my >= y && my < y + ch;
				int lift = hover ? -2 : 0;
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, hover ? CARD_HOVER : CARD, x, y + lift, cw, ch);
				int cy = y + lift + 8;
				// A portrait: the player's own face, in sepia, as the family resemblance.
				var player = Minecraft.getInstance().player;
				int px = x + (cw - PORTRAIT) / 2;
				graphics.fill(px - 2, cy - 2, px + PORTRAIT + 2, cy + PORTRAIT + 2, 0xFF6B4A2A);
				graphics.fill(px - 1, cy - 1, px + PORTRAIT + 1, cy + PORTRAIT + 1, 0xFFC9A24A);
				if (player != null) PlayerFaceExtractor.extractRenderState(graphics, player.getSkin(), px, cy, PORTRAIT, 0xFFE9D7B0);
				cy += PORTRAIT + 6;
				Component name = ink(card.name().copy().withStyle(s -> s.withBold(true)));
				graphics.text(font, name, x + (cw - font.width(name)) / 2, cy, INK, false);
				cy += font.lineHeight + 2;
				Component sub = ink(card.subtitle().copy().withStyle(s -> s.withItalic(true)));
				graphics.text(font, sub, x + (cw - font.width(sub)) / 2, cy, FADED_INK, false);
				cy += font.lineHeight + 6;
				for (Component line : card.lines()) {
					for (FormattedCharSequence part : wrap(line, cw - 10)) {
						graphics.text(font, part, x + (cw - font.width(part)) / 2, cy, INK, false);
						cy += font.lineHeight;
					}
				}
				areas.add(new Area(x, y, cw, ch, card.command(), card.tooltip()));
				if (hover) tooltip = card.tooltip();
				x += cw + CARD_GAP;
			}
			y += ch;
		}

		List<List<ScrollPayload.Choice>> rows = buttonRows();
		if (!rows.isEmpty()) y += GAP * 2;
		for (List<ScrollPayload.Choice> row : rows) {
			int total = 0;
			for (ScrollPayload.Choice choice : row) total += (row.size() == 1 ? Math.max(buttonWidth(choice), Math.min(200, contentWidth())) : buttonWidth(choice)) + GAP;
			int x = (pw - (total - GAP)) / 2;
			for (ScrollPayload.Choice choice : row) {
				int bw = row.size() == 1 ? Math.max(buttonWidth(choice), Math.min(200, contentWidth())) : buttonWidth(choice);
				boolean hover = mx >= x && mx < x + bw && my >= y && my < y + BUTTON_HEIGHT;
				String base = switch (choice.toneOf()) {
					case BLOOD -> "button_blood";
					case PRIMARY -> "button_primary";
					default -> "button";
				};
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite(hover ? base + "_hover" : base), x, y, bw, BUTTON_HEIGHT);
				Component label = choice.label().copy().withStyle(Style.EMPTY.withColor(PAPER_TEXT & 0xFFFFFF));
				graphics.text(font, label, x + (bw - font.width(label)) / 2, y + (BUTTON_HEIGHT - font.lineHeight) / 2 + 1, PAPER_TEXT, true);
				areas.add(new Area(x, y, bw, BUTTON_HEIGHT, choice.command(), choice.tooltip()));
				if (hover) tooltip = choice.tooltip();
				x += bw + GAP;
			}
			y += BUTTON_HEIGHT + 4;
		}

		if (!scroll.footer().getString().isEmpty()) {
			y += GAP;
			for (FormattedCharSequence part : font.split(scroll.footer().copy().withStyle(s -> s.withItalic(true).withColor(FADED_INK & 0xFFFFFF)), contentWidth())) {
				graphics.text(font, part, (pw - font.width(part)) / 2, y, FADED_INK, false);
				y += font.lineHeight;
			}
		}
		return tooltip;
	}

	// --- Clicking -------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = (event.x() - lastOrigin[0]) / lastScale, my = (event.y() - lastOrigin[1]) / lastScale;
		for (Area area : areas) {
			if (!area.contains(mx, my)) continue;
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f));
			if (!area.command().isEmpty() && minecraft.player != null) {
				String command = area.command().startsWith("/") ? area.command().substring(1) : area.command();
				minecraft.player.connection.sendCommand(command);
			}
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	// --- Ink --------------------------------------------------------------------------------------------

	/** Text in ink on paper: no colour means ink, and bright chat colours are darkened so they read on parchment. */
	static Component ink(Component text) {
		MutableComponent out = Component.empty();
		text.visit((style, part) -> {
			out.append(Component.literal(part).withStyle(style.withColor(TextColor.fromRgb(darken(style.getColor())))));
			return Optional.empty();
		}, Style.EMPTY);
		return out;
	}

	private static int darken(TextColor color) {
		if (color == null) return INK & 0xFFFFFF;
		int rgb = color.getValue();
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		double light = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
		if (light < 0.3) return rgb;
		// Brighter colours are darkened more, down to half their brightness.
		double f = Math.max(0.5, Math.min(0.85, 0.3 / light));
		return ((int) (r * f) << 16) | ((int) (g * f) << 8) | (int) (b * f);
	}
}
