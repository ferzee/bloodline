package io.github.fersyvanderzee.bloodline;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Choices shown as a window on the player's screen, using Minecraft's own dialog screens, so they
 * work for players without the mod too. Each button runs a /bloodline command, exactly like the
 * clickable buttons in chat.
 */
public final class Screens {
	private static final int TEXT_WIDTH = 260;
	private static final int BUTTON_WIDTH = 200;

	/** A button: its label, what it shows on hover, and the command it runs (none: it just closes the window). */
	public record Button(Component label, Component tooltip, String command) {
	}

	private record Delayed(UUID player, long dueTick, Holder<Dialog> dialog) {
	}

	/** Windows waiting to open, for moments when an immediate one would be closed again (such as respawning). */
	private static final List<Delayed> DELAYED = new ArrayList<>();

	private Screens() {
	}

	private static Holder<Dialog> build(Component title, List<Component> body, List<Button> buttons) {
		List<DialogBody> text = body.stream().<DialogBody>map(line -> new PlainMessage(line, TEXT_WIDTH)).toList();
		// Escape closes the window; the choice stays available in chat, the Chronicle and by command.
		CommonDialogData common = new CommonDialogData(title, Optional.empty(), true, false, DialogAction.CLOSE, text, List.of());
		List<ActionButton> actions = buttons.stream()
			.map(b -> new ActionButton(new CommonButtonData(b.label(), Optional.ofNullable(b.tooltip()), BUTTON_WIDTH),
				b.command() == null ? Optional.<net.minecraft.server.dialog.action.Action>empty()
					: Optional.<net.minecraft.server.dialog.action.Action>of(new StaticAction(new ClickEvent.RunCommand(b.command())))))
			.toList();
		return Holder.direct(new MultiActionDialog(common, actions, Optional.empty(), 1));
	}

	public static void show(ServerPlayer player, Component title, List<Component> body, List<Button> buttons) {
		player.openDialog(build(title, body, buttons));
	}

	/** Opens the window a little later (in ticks). */
	public static void showLater(ServerPlayer player, int delayTicks, Component title, List<Component> body, List<Button> buttons) {
		long due = player.level().getServer().getTickCount() + delayTicks;
		DELAYED.add(new Delayed(player.getUUID(), due, build(title, body, buttons)));
	}

	/** Runs every server tick: opens delayed windows that are due, if their player is still online. */
	public static void tick(MinecraftServer server) {
		if (DELAYED.isEmpty()) return;
		for (Iterator<Delayed> it = DELAYED.iterator(); it.hasNext(); ) {
			Delayed delayed = it.next();
			if (server.getTickCount() < delayed.dueTick()) continue;
			it.remove();
			ServerPlayer player = server.getPlayerList().getPlayer(delayed.player());
			if (player != null) player.openDialog(delayed.dialog());
		}
	}
}
