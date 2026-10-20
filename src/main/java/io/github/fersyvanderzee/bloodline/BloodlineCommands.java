package io.github.fersyvanderzee.bloodline;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.fersyvanderzee.bloodline.data.Ancestor;
import io.github.fersyvanderzee.bloodline.data.BloodlineState;
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
			.then(Commands.literal("heir")
				.then(Commands.argument("child", IntegerArgumentType.integer(1, Heirs.MAX_CHOICES))
					.executes(ctx -> {
						Heirs.choose(ctx.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(ctx, "child"));
						return 1;
					})))
			.then(Commands.literal("event")
				.executes(ctx -> {
					ServerPlayer player = ctx.getSource().getPlayerOrException();
					FamilyEvents.show(player, Lineages.get(player));
					return 1;
				})
				.then(Commands.literal("accept").executes(ctx -> {
					FamilyEvents.answer(ctx.getSource().getPlayerOrException(), true);
					return 1;
				}))
				.then(Commands.literal("decline").executes(ctx -> {
					FamilyEvents.answer(ctx.getSource().getPlayerOrException(), false);
					return 1;
				})))
			.then(Commands.literal("sacrifice")
				.executes(ctx -> {
					Sacrifice.request(ctx.getSource().getPlayerOrException());
					return 1;
				})
				.then(Commands.literal("confirm").executes(ctx -> {
					Sacrifice.confirm(ctx.getSource().getPlayerOrException());
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
					if (Questlines.canSwear(lineage.house)) Questlines.offerOath(player, lineage);
					else ctx.getSource().sendSuccess(() -> Questlines.oathSummary(lineage).withStyle(ChatFormatting.GOLD), false);
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
						if (!Questlines.canSwear(lineage.house)) {
							ctx.getSource().sendFailure(Text.t("command.oath.already", lineage.house.name));
							return 0;
						}
						if (!lineage.isHead(player.getUUID())) {
							ctx.getSource().sendFailure(Text.t("command.oath.head_only", lineage.house.name));
							return 0;
						}
						if (!Questlines.swear(player, lineage, StringArgumentType.getString(ctx, "oath"))) {
							ctx.getSource().sendFailure(Text.t("command.oath.not_available", lineage.house.name));
							return 0;
						}
						// Sworn from the book: show the updated Main Quest pages straight away.
						Chronicle.reopen(player);
						return 1;
					})))
			.then(Commands.literal("admin")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("reset")
					.then(Commands.argument("player", EntityArgument.player())
						.executes(ctx -> {
							ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
							Lineage lineage = Lineages.get(target);
							if (lineage == null) return 0;
							// Their branch is forgotten and they found a brand-new house, as if joining for the first time.
							var server = target.level().getServer();
							Houses.detach(server, lineage.house, target.getUUID(), lineage.current.name());
							BloodlineState.get(server).remove(target.getUUID());
							Lineages.onJoin(target);
							ctx.getSource().sendSuccess(() -> Text.t("command.reset", target.getGameProfile().name()), true);
							return 1;
						}))))
			.then(Commands.literal("manor")
				.then(Commands.literal("claim").executes(ctx -> Manors.claim(ctx.getSource().getPlayerOrException()) ? 1 : 0)))
			.then(Commands.literal("debug")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("manor")
					.executes(ctx -> {
						var description = Manors.describe(ctx.getSource().getPlayerOrException());
						if (description.isEmpty()) {
							ctx.getSource().sendFailure(Text.t("command.manor.none"));
							return 0;
						}
						ctx.getSource().sendSuccess(description::get, false);
						return 1;
					})
					.then(Commands.literal("clear").executes(ctx -> {
						if (!Manors.debugClear(ctx.getSource().getPlayerOrException())) {
							ctx.getSource().sendFailure(Text.t("command.manor.none"));
							return 0;
						}
						return 1;
					}))
					.then(Commands.literal("reset").executes(ctx -> {
						if (!Manors.reset(ctx.getSource().getPlayerOrException())) {
							ctx.getSource().sendFailure(Text.t("command.manor.none"));
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Text.t("command.manor.reset"), true);
						return 1;
					})))
				.then(Commands.literal("crypt")
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						var description = Crypts.describe(player);
						if (description.isEmpty()) {
							ctx.getSource().sendFailure(Text.t("command.crypt.none"));
							return 0;
						}
						ctx.getSource().sendSuccess(description::get, false);
						return 1;
					})
					.then(Commands.literal("reset").executes(ctx -> {
						if (!Crypts.reset(ctx.getSource().getPlayerOrException())) {
							ctx.getSource().sendFailure(Text.t("command.crypt.none"));
							return 0;
						}
						ctx.getSource().sendSuccess(() -> Text.t("command.crypt.reset"), true);
						return 1;
					})))
				.then(Commands.literal("age")
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						Lineage lineage = lineageOf(ctx.getSource(), player);
						if (lineage == null) return 0;
						// Pretend the current family member has lived a full life, so their children inherit normally.
						var stats = new java.util.HashMap<>(lineage.current.statsAtBirth());
						int lived = BloodlineConfig.get().families.briefLifeMinutes * 1200 + 1;
						stats.put("play_time", Epithets.snapshot(player).getOrDefault("play_time", 0) - lived);
						var m = lineage.current;
						lineage.current = new io.github.fersyvanderzee.bloodline.data.Member(m.name(), m.generation(), m.traits(), m.bornDay(), stats);
						Lineages.markDirty(player.level().getServer());
						ctx.getSource().sendSuccess(() -> Text.t("command.aged", m.name()), false);
						return 1;
					}))
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
				.then(Commands.literal("renown")
					.then(Commands.argument("amount", IntegerArgumentType.integer(-1000, 1000))
						.executes(ctx -> {
							ServerPlayer player = ctx.getSource().getPlayerOrException();
							Lineage lineage = lineageOf(ctx.getSource(), player);
							if (lineage == null) return 0;
							Renown.change(player, lineage, IntegerArgumentType.getInteger(ctx, "amount"), Text.t("renown.reason.debug"));
							return 1;
						})))
				.then(Commands.literal("event")
					.then(Commands.argument("type", StringArgumentType.word())
						.suggests((ctx, builder) -> {
							for (String type : new String[]{"sickness", "restless", "feud", "wedding", "cousin"}) builder.suggest(type);
							return builder.buildFuture();
						})
						.executes(ctx -> {
							ServerPlayer player = ctx.getSource().getPlayerOrException();
							Lineage lineage = lineageOf(ctx.getSource(), player);
							String type = StringArgumentType.getString(ctx, "type");
							if (lineage == null || !FamilyEvents.debugStart(player, lineage, type)) return 0;
							ctx.getSource().sendSuccess(() -> Text.t("command.event_started", type), false);
							return 1;
						})))
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
		if (lineage == null) source.sendFailure(Text.t("command.no_family", player.getGameProfile().name()));
		return lineage;
	}

	private static int info(CommandSourceStack source, ServerPlayer player) {
		Lineage lineage = lineageOf(source, player);
		if (lineage == null) return 0;
		Ancestor parent = lineage.parent();
		source.sendSuccess(() -> Text.t("book.cover.house", lineage.house.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		source.sendSuccess(() -> Text.t("info.character", Component.literal(lineage.fullName()).withStyle(ChatFormatting.WHITE),
			Names.numeral(lineage.current.generation()), lineage.current.bornDay()).withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Text.t("info.traits", Lineages.traitList(lineage.current.traits(), parent)).withStyle(ChatFormatting.GRAY), false);
		if (!lineage.house.legacies.isEmpty()) {
			source.sendSuccess(() -> Text.t("info.legacy", Lineages.traitList(lineage.house.legacies, null)).withStyle(ChatFormatting.GRAY), false);
		}
		if (lineage.house.oath.isPresent()) {
			source.sendSuccess(() -> Questlines.oathSummary(lineage).withStyle(ChatFormatting.GRAY), false);
		}
		source.sendSuccess(() -> Text.t("info.members", Lineages.memberNames(player.level().getServer(), lineage.house).withStyle(ChatFormatting.WHITE))
			.withStyle(ChatFormatting.GRAY), false);
		source.sendSuccess(() -> Text.t("duties.renown", Component.literal(String.valueOf(lineage.house.renown)).withStyle(ChatFormatting.YELLOW))
			.withStyle(ChatFormatting.GRAY), false);
		if (parent != null) {
			source.sendSuccess(() -> Text.t("info.parent", parent.fullName().withStyle(s -> s.withColor(ChatFormatting.WHITE)
				.withHoverEvent(new HoverEvent.ShowText(parent.deathMessage())))).withStyle(ChatFormatting.GRAY), false);
		}
		return 1;
	}

	private static int tree(CommandSourceStack source, ServerPlayer player) {
		Lineage lineage = lineageOf(source, player);
		if (lineage == null) return 0;
		source.sendSuccess(() -> Text.t("info.tree", lineage.house.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD), false);
		for (Ancestor a : lineage.ancestors) {
			source.sendSuccess(() -> Component.literal(Names.numeral(a.generation()) + ". ").withStyle(ChatFormatting.DARK_GRAY)
				.append(a.fullName().withStyle(s -> s.withColor(ChatFormatting.WHITE)
					.withHoverEvent(new HoverEvent.ShowText(a.deathMessage()))))
				.append(" (").append(Text.t("book.tree.days", a.bornDay(), a.diedDay())).append(") ").withStyle(ChatFormatting.GRAY)
				.append(Lineages.traitList(a.traits(), null)), false);
		}
		source.sendSuccess(() -> Component.literal(Names.numeral(lineage.current.generation()) + ". ").withStyle(ChatFormatting.DARK_GRAY)
			.append(Component.literal(lineage.current.name() + " (").append(Text.t("book.tree.living")).append(") ").withStyle(ChatFormatting.GOLD))
			.append(Lineages.traitList(lineage.current.traits(), null)), false);
		return 1;
	}

	private static int renameHouse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		Lineage lineage = lineageOf(ctx.getSource(), player);
		if (lineage == null) return 0;
		if (!lineage.isHead(player.getUUID())) {
			ctx.getSource().sendFailure(Text.t("command.rename.head_only"));
			return 0;
		}
		String name = StringArgumentType.getString(ctx, "name").trim();
		if (name.startsWith("House ")) name = name.substring("House ".length()).trim();
		if (name.isEmpty() || name.length() > MAX_HOUSE_LENGTH || !name.matches("[\\p{L}' -]+")) {
			ctx.getSource().sendFailure(Text.t("command.rename.invalid", MAX_HOUSE_LENGTH));
			return 0;
		}
		String old = lineage.house.name;
		lineage.house.name = name;
		Lineages.record(player.level().getServer(), lineage, Text.t("chronicle.renamed", old, name));
		Lineages.onlineMembers(player.level().getServer(), lineage.house).forEach(Lineages::refreshName);
		Lineages.tellHouse(player.level().getServer(), lineage.house, Text.t("command.renamed", lineage.house.name).withStyle(ChatFormatting.GOLD));
		return 1;
	}
}
