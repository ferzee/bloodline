package io.github.fersyvanderzee.bloodline;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.Lineage;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;

final class BloodlineCommands {
	private static final int MAX_HOUSE_LENGTH = 20;

	private BloodlineCommands() {
	}

	static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("bloodline")
			.executes(ctx -> info(ctx.getSource(), ctx.getSource().getPlayerOrException()))
			.then(Commands.literal("info")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> info(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
			.then(Commands.literal("tree")
				.executes(ctx -> tree(ctx.getSource(), ctx.getSource().getPlayerOrException()))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> tree(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
			.then(Commands.literal("chronicle")
				.executes(ctx -> {
					Chronicle.give(ctx.getSource().getPlayerOrException());
					return 1;
				}))
			.then(Commands.literal("house")
				.then(Commands.argument("name", StringArgumentType.greedyString())
					.executes(BloodlineCommands::renameHouse))));
	}

	private static Lineage lineageOf(CommandSourceStack source, ServerPlayer player) {
		Lineage lineage = Lineages.get(player);
		if (lineage == null) source.sendFailure(Component.literal(player.getGameProfile().name() + " has no family yet."));
		return lineage;
	}

	private static int info(CommandSourceStack source, ServerPlayer player) {
		Lineage lineage = lineageOf(source, player);
		if (lineage == null) return 0;
		Ancestor parent = lineage.parent();
		source.sendSuccess(() -> Component.literal("House " + lineage.house).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		source.sendSuccess(() -> Component.literal("Head: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(lineage.fullName()).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(", generation " + Names.numeral(lineage.current.generation()) + ", born day " + lineage.current.bornDay())
				.withStyle(ChatFormatting.GRAY)), false);
		source.sendSuccess(() -> Component.literal("Traits: ").withStyle(ChatFormatting.GRAY)
			.append(Lineages.traitList(lineage.current.traits(), parent)), false);
		if (parent != null) {
			source.sendSuccess(() -> Component.literal("Parent: ").withStyle(ChatFormatting.GRAY)
				.append(Component.literal(parent.fullName()).withStyle(s -> s.withColor(ChatFormatting.WHITE)
					.withHoverEvent(new HoverEvent.ShowText(parent.deathMessage())))), false);
		}
		return 1;
	}

	private static int tree(CommandSourceStack source, ServerPlayer player) {
		Lineage lineage = lineageOf(source, player);
		if (lineage == null) return 0;
		source.sendSuccess(() -> Component.literal("Family tree of House " + lineage.house).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		for (Ancestor a : lineage.ancestors) {
			source.sendSuccess(() -> Component.literal(Names.numeral(a.generation()) + ". ").withStyle(ChatFormatting.DARK_GRAY)
				.append(Component.literal(a.fullName()).withStyle(s -> s.withColor(ChatFormatting.WHITE)
					.withHoverEvent(new HoverEvent.ShowText(a.deathMessage()))))
				.append(Component.literal(" (day " + a.bornDay() + "-" + a.diedDay() + ") ").withStyle(ChatFormatting.GRAY))
				.append(Lineages.traitList(a.traits(), null)), false);
		}
		source.sendSuccess(() -> Component.literal(Names.numeral(lineage.current.generation()) + ". ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(lineage.current.name() + " (living) ").withStyle(ChatFormatting.GOLD))
			.append(Lineages.traitList(lineage.current.traits(), null)), false);
		return 1;
	}

	private static int renameHouse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Lineage lineage = lineageOf(ctx.getSource(), player);
		if (lineage == null) return 0;
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.startsWith("House ")) name = name.substring("House ".length()).trim();
		if (name.isEmpty() || name.length() > MAX_HOUSE_LENGTH || !name.matches("[\\p{L}' -]+")) {
			ctx.getSource().sendFailure(Component.literal("House names can be up to " + MAX_HOUSE_LENGTH + " letters, spaces, hyphens or apostrophes."));
			return 0;
		}
		String old = lineage.house;
		lineage.house = name;
		Lineages.record(player.level().getServer(), lineage, Component.literal("House " + old + " took the name House " + name + "."));
		Lineages.refreshName(player);
		ctx.getSource().sendSuccess(() -> Component.literal("Your family is now House " + lineage.house + ".").withStyle(ChatFormatting.GOLD), false);
		return 1;
	}
}
