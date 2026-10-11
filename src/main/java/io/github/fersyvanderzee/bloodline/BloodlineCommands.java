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
			.then(Commands.literal("invite")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> {
						Houses.invite(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"));
						return 1;
					})))
			.then(Commands.literal("accept")
				.then(Commands.literal("keep").executes(ctx -> {
					Houses.accept(ctx.getSource().getPlayerOrException(), true);
					return 1;
				}))
				.then(Commands.literal("fresh").executes(ctx -> {
					Houses.accept(ctx.getSource().getPlayerOrException(), false);
					return 1;
				})))
			.then(Commands.literal("decline").executes(ctx -> {
				Houses.decline(ctx.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("leave").executes(ctx -> {
				Houses.leave(ctx.getSource().getPlayerOrException());
				return 1;
			}))
			.then(Commands.literal("remove")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> {
						Houses.remove(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"));
						return 1;
					})))
			.then(Commands.literal("head")
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> {
						Houses.makeHead(ctx.getSource().getPlayerOrException(), EntityArgument.getPlayer(ctx, "player"));
						return 1;
					})))
			.then(Commands.literal("quests")
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					Lineage lineage = lineageOf(ctx.getSource(), player);
					if (lineage == null) return 0;
					Quests.show(player, lineage);
					return 1;
				}))
			.then(Commands.literal("oath")
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					Lineage lineage = lineageOf(ctx.getSource(), player);
					if (lineage == null) return 0;
					if (lineage.house.oath.isEmpty()) Questlines.offerOath(player, lineage);
					else ctx.getSource().sendSuccess(() -> Component.literal(Questlines.oathSummary(lineage).trim()).withStyle(ChatFormatting.GOLD), false);
					return 1;
				})
				.then(Commands.argument("oath", StringArgumentType.word())
					.suggests((ctx, builder) -> {
						for (String id : new String[]{"sword", "deep", "hearth"}) builder.suggest(id);
						return builder.buildFuture();
					})
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						Lineage lineage = lineageOf(ctx.getSource(), player);
						if (lineage == null) return 0;
						if (lineage.house.oath.isPresent()) {
							ctx.getSource().sendFailure(Component.literal("House " + lineage.house.name + " has already sworn an oath."));
							return 0;
						}
						if (!lineage.isHead(player.getUUID())) {
							ctx.getSource().sendFailure(Component.literal("Only the head of House " + lineage.house.name + " can swear its oath."));
							return 0;
						}
						if (!Questlines.swear(player, lineage, StringArgumentType.getString(ctx, "oath"))) {
							ctx.getSource().sendFailure(Component.literal("Unknown oath. Choose sword, deep or hearth."));
							return 0;
						}
						// Sworn from the book: show the updated Main Quest pages straight away.
						Chronicle.reopen(player);
						return 1;
					})))
			.then(Commands.literal("debug")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("complete")
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						Lineage lineage = lineageOf(ctx.getSource(), player);
						if (lineage == null || lineage.allQuests().isEmpty()) return 0;
						// Finish the newest quest, so testers can step through questlines quickly.
						var quest = lineage.allQuests().getLast();
						quest.progress = quest.required;
						return 1;
					}))
				.then(Commands.literal("champion")
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						Champions.spawn(player, Champions.FORTRESS_CAPTAIN, "debug", java.util.Optional.empty(), java.util.List.of());
						return 1;
					})))
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
		source.sendSuccess(() -> Component.literal("House " + lineage.house.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		source.sendSuccess(() -> Component.literal("Head: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(lineage.fullName()).withStyle(ChatFormatting.WHITE))
			.append(Component.literal(", generation " + Names.numeral(lineage.current.generation()) + ", born day " + lineage.current.bornDay())
				.withStyle(ChatFormatting.GRAY)), false);
		source.sendSuccess(() -> Component.literal("Traits: ").withStyle(ChatFormatting.GRAY)
			.append(Lineages.traitList(lineage.current.traits(), parent)), false);
		if (!lineage.house.legacies.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Legacy: ").withStyle(ChatFormatting.GRAY)
				.append(Lineages.traitList(lineage.house.legacies, null)), false);
		}
		if (lineage.house.oath.isPresent()) {
			source.sendSuccess(() -> Component.literal(Questlines.oathSummary(lineage).trim()).withStyle(ChatFormatting.GRAY), false);
		}
		source.sendSuccess(() -> Component.literal("Members: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(Lineages.memberNames(player.level().getServer(), lineage.house)).withStyle(ChatFormatting.WHITE)), false);
		source.sendSuccess(() -> Component.literal("Renown: ").withStyle(ChatFormatting.GRAY)
			.append(Component.literal(String.valueOf(lineage.house.renown)).withStyle(ChatFormatting.YELLOW)), false);
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
		source.sendSuccess(() -> Component.literal("Family tree of House " + lineage.house.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
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
		if (!lineage.isHead(player.getUUID())) {
			ctx.getSource().sendFailure(Component.literal("Only the head of the house can rename it."));
			return 0;
		}
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.startsWith("House ")) name = name.substring("House ".length()).trim();
		if (name.isEmpty() || name.length() > MAX_HOUSE_LENGTH || !name.matches("[\\p{L}' -]+")) {
			ctx.getSource().sendFailure(Component.literal("House names can be up to " + MAX_HOUSE_LENGTH + " letters, spaces, hyphens or apostrophes."));
			return 0;
		}
		String old = lineage.house.name;
		lineage.house.name = name;
		Lineages.record(player.level().getServer(), lineage, Component.literal("House " + old + " took the name House " + name + "."));
		Lineages.onlineMembers(player.level().getServer(), lineage.house).forEach(Lineages::refreshName);
		Lineages.tellHouse(player.level().getServer(), lineage.house, Component.literal("Your family is now House " + lineage.house.name + ".").withStyle(ChatFormatting.GOLD));
		return 1;
	}
}
