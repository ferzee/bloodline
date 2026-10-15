package io.github.fersyvanderzee.bloodline;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Choices shown as a window on the player's screen. Players with Bloodline installed get a parchment scroll
 * drawn by the mod ({@link ScrollPayload}); everyone else gets the same choice as one of Minecraft's own
 * dialog windows, so a server works for players without the mod too. Each card and button runs a
 * /bloodline command, exactly like the clickable buttons in chat.
 */
public final class Screens {
	private static final int TEXT_WIDTH = 260;
	private static final int BUTTON_WIDTH = 200;

	/** A button: its label, what it shows on hover, and the command it runs (none: it just closes the window). */
	public record Button(Component label, Component tooltip, String command) {
	}

	/** A card to click, such as a child who could become heir. */
	public record Card(Component name, Component subtitle, List<Component> lines, Component tooltip, String command) {
	}

	/** Everything a window shows: an emblem, a title, some text, cards, buttons and a note at the bottom. */
	public record Window(ItemStack icon, Component title, List<Component> body, List<Card> cards, List<Button> buttons, Component footer) {
		public static Window of(ItemStack icon, Component title, List<Component> body, List<Button> buttons) {
			return new Window(icon, title, body, List.of(), buttons, Component.empty());
		}
	}

	private record Delayed(UUID player, long dueTick, Window window) {
	}

	/** Windows waiting to open, for moments when an immediate one would be closed again (such as respawning). */
	private static final List<Delayed> DELAYED = new ArrayList<>();

	private Screens() {
	}

	public static void show(ServerPlayer player, Window window) {
		if (ServerPlayNetworking.canSend(player, ScrollPayload.TYPE.id())) {
			ServerPlayNetworking.send(player, scroll(window));
		} else {
			player.openDialog(dialog(window));
		}
	}

	/** Opens the window a little later (in ticks). */
	public static void showLater(ServerPlayer player, int delayTicks, Window window) {
		long due = player.level().getServer().getTickCount() + delayTicks;
		DELAYED.add(new Delayed(player.getUUID(), due, window));
	}

	/** Runs every server tick: opens delayed windows that are due, if their player is still online. */
	public static void tick(MinecraftServer server) {
		if (DELAYED.isEmpty()) return;
		List<Delayed> due = new ArrayList<>();
		for (Iterator<Delayed> it = DELAYED.iterator(); it.hasNext(); ) {
			Delayed delayed = it.next();
			if (server.getTickCount() < delayed.dueTick()) continue;
			it.remove();
			due.add(delayed);
		}
		for (Delayed delayed : due) {
			ServerPlayer player = server.getPlayerList().getPlayer(delayed.player());
			if (player != null) show(player, delayed.window());
		}
	}

	// --- The mod's own scroll ----------------------------------------------------------------------------

	private static ScrollPayload scroll(Window window) {
		List<ScrollPayload.Card> cards = window.cards().stream()
			.map(c -> new ScrollPayload.Card(c.name(), c.subtitle(), c.lines(), orEmpty(c.tooltip()), c.command()))
			.toList();
		List<ScrollPayload.Choice> choices = new ArrayList<>();
		for (int i = 0; i < window.buttons().size(); i++) {
			Button b = window.buttons().get(i);
			choices.add(new ScrollPayload.Choice(b.label(), orEmpty(b.tooltip()), b.command() == null ? "" : b.command(), toneOf(b, i).ordinal()));
		}
		return new ScrollPayload(window.title(), window.body(), cards, choices, window.footer(), window.icon());
	}

	/** A red label is a grave choice; otherwise the first button is the one the window suggests. */
	private static ScrollPayload.Tone toneOf(Button button, int index) {
		TextColor color = button.label().getStyle().getColor();
		if (color != null && color.equals(TextColor.fromLegacyFormat(ChatFormatting.DARK_RED))) return ScrollPayload.Tone.BLOOD;
		return index == 0 && button.command() != null ? ScrollPayload.Tone.PRIMARY : ScrollPayload.Tone.PLAIN;
	}

	private static Component orEmpty(Component component) {
		return component == null ? Component.empty() : component;
	}

	// --- Minecraft's own dialog, for players without the mod -------------------------------------------------

	private static Holder<Dialog> dialog(Window window) {
		List<Component> lines = new ArrayList<>(window.body());
		List<Button> buttons = new ArrayList<>();
		for (Card card : window.cards()) {
			MutableComponent line = Component.empty().append(card.name().copy().withStyle(ChatFormatting.BOLD)).append(": ");
			for (int i = 0; i < card.lines().size(); i++) {
				if (i > 0) line.append(", ");
				line.append(card.lines().get(i));
			}
			lines.add(line);
			buttons.add(new Button(card.name(), card.tooltip(), card.command()));
		}
		buttons.addAll(window.buttons());
		if (!window.footer().getString().isEmpty()) lines.add(window.footer().copy().withStyle(ChatFormatting.DARK_GRAY));
		List<DialogBody> text = lines.stream().<DialogBody>map(line -> new PlainMessage(line, TEXT_WIDTH)).toList();
		// Escape closes the window; the choice stays available in chat, the Chronicle and by command.
		CommonDialogData common = new CommonDialogData(window.title(), Optional.empty(), true, false, DialogAction.CLOSE, text, List.of());
		List<ActionButton> actions = buttons.stream()
			.map(b -> new ActionButton(new CommonButtonData(b.label(), Optional.ofNullable(b.tooltip()), BUTTON_WIDTH),
				b.command() == null ? Optional.<net.minecraft.server.dialog.action.Action>empty()
					: Optional.<net.minecraft.server.dialog.action.Action>of(new StaticAction(new ClickEvent.RunCommand(b.command())))))
			.toList();
		return Holder.direct(new MultiActionDialog(common, actions, Optional.empty(), 1));
	}
}
