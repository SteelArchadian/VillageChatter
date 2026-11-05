package dev.villagechatter.quests;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import dev.villagechatter.quests.quest.QuestText;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import dev.villagechatter.quests.quest.TargetFinder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;

/**
 * /villagequests answer &lt;villager&gt; &lt;choice&gt;   (chat buttons)
 * /villagequests list | abandon &lt;n&gt;
 * /villagequests offer [quest] | locate &lt;quest&gt; [pos] | tp &lt;n&gt; | expire &lt;n&gt;   (ops, for testing)
 */
public final class QuestCommands {
    private QuestCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("villagequests")
                .then(Commands.literal("answer")
                        .then(Commands.argument("villager", StringArgumentType.word())
                                .then(Commands.argument("choice", StringArgumentType.word())
                                        .executes(QuestCommands::answer))))
                .then(Commands.literal("list").executes(QuestCommands::list))
                .then(Commands.literal("abandon")
                        .then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(QuestCommands::abandon)))
                .then(Commands.literal("offer").requires(s -> s.hasPermission(2))
                        .executes(ctx -> offer(ctx, null))
                        .then(Commands.argument("quest", ResourceLocationArgument.id())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(QuestDefinitions.all().keySet(), b))
                                .executes(ctx -> offer(ctx, ResourceLocationArgument.getId(ctx, "quest")))))
                .then(Commands.literal("locate").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("quest", ResourceLocationArgument.id())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(QuestDefinitions.all().keySet(), b))
                                .executes(ctx -> locate(ctx, BlockPos.containing(ctx.getSource().getPosition())))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> locate(ctx, BlockPosArgument.getBlockPos(ctx, "pos"))))))
                .then(Commands.literal("rumors").requires(s -> s.hasPermission(2))
                        .executes(ctx -> rumors(ctx, BlockPos.containing(ctx.getSource().getPosition())))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> rumors(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("tp").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(QuestCommands::tp)))
                .then(Commands.literal("expire").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1)).executes(QuestCommands::expire))));
    }

    private static int answer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        UUID villager;
        try {
            villager = UUID.fromString(StringArgumentType.getString(ctx, "villager"));
        } catch (IllegalArgumentException e) {
            return 0;
        }
        QuestManager.answer(player, villager, StringArgumentType.getString(ctx, "choice"));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<ActiveQuest> quests = player.getData(QuestRegistries.PLAYER_QUESTS).quests;
        if (quests.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.translatable("villagequests.cmd.none"), false);
            return 0;
        }
        long now = player.serverLevel().getGameTime();
        for (int i = 0; i < quests.size(); i++) {
            ActiveQuest q = quests.get(i);
            QuestDefinition def = QuestDefinitions.get(q.questId);
            Component title = def == null ? Component.literal(q.questId.toString()) : QuestManager.title(def, q);
            Component status = q.state == ActiveQuest.State.FAILED
                    ? q.failReasonText().copy().withStyle(ChatFormatting.RED)
                    : QuestManager.status(player, q, def, now);
            int n = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal(n + ". ").append(title.copy().withStyle(ChatFormatting.GOLD))
                    .append(" - ").append(QuestText.kindName(q.kind)).append(" ").append(QuestText.roughCoords(q.target))
                    .append(" - ").append(status), false);
        }
        return quests.size();
    }

    private static int abandon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ActiveQuest quest = QuestManager.byIndex(player, IntegerArgumentType.getInteger(ctx, "n"));
        if (quest == null || quest.state == ActiveQuest.State.FAILED) return 0;
        QuestManager.abandon(player, quest);
        ctx.getSource().sendSuccess(() -> Component.translatable("villagequests.cmd.abandoned", quest.giverName), false);
        return 1;
    }

    public static int offer(CommandContext<CommandSourceStack> ctx, ResourceLocation questId) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Villager villager = player.serverLevel().getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(16.0),
                        v -> questId == null || QuestDefinitions.get(questId) == null || QuestDefinitions.get(questId).givers().allows(v.isBaby())).stream()
                .min(Comparator.comparingDouble(v -> v.distanceToSqr(player))).orElse(null);
        if (villager == null) {
            ctx.getSource().sendFailure(Component.translatable("villagequests.cmd.no_villager"));
            return 0;
        }
        QuestDefinition def;
        if (questId != null) {
            def = QuestDefinitions.get(questId);
            if (def == null) {
                ctx.getSource().sendFailure(Component.translatable("villagequests.cmd.unknown_quest", questId.toString()));
                return 0;
            }
        } else {
            def = QuestDefinitions.pickFor(net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
                    .getKey(villager.getVillagerData().getProfession()), villager.isBaby(), java.util.Set.of(), player.getRandom());
            if (def == null) def = QuestDefinitions.all().values().stream().findFirst().orElse(null);
            if (def == null) return 0;
        }
        QuestManager.tryOffer(villager, def);
        QuestDefinition chosen = def;
        ctx.getSource().sendSuccess(() -> Component.translatable("villagequests.cmd.offered", villager.getName(), chosen.id().toString()), true);
        return 1;
    }

    /** Previews where this quest would put its target if accepted at pos (also works from the console). */
    private static int locate(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        ResourceLocation id = ResourceLocationArgument.getId(ctx, "quest");
        QuestDefinition def = QuestDefinitions.get(id);
        if (def == null) {
            ctx.getSource().sendFailure(Component.translatable("villagequests.cmd.unknown_quest", id.toString()));
            return 0;
        }
        if (def.location() == null) {
            ctx.getSource().sendFailure(Component.translatable("villagequests.cmd.no_location", id.toString()));
            return 0;
        }
        ServerLevel level = ctx.getSource().getLevel();
        long start = System.nanoTime();
        TargetFinder.Target target = TargetFinder.find(level, pos, def.location(), level.random);
        long ms = (System.nanoTime() - start) / 1_000_000;
        int dist = (int) Math.sqrt(target.pos().distSqr(new BlockPos(pos.getX(), target.pos().getY(), pos.getZ())));
        QuestsModule.LOGGER.info("locate {} from {}: {} at {} ({} blocks, {} ms)", id, pos.toShortString(), target.kind(),
                target.pos().toShortString(), dist, ms);
        ctx.getSource().sendSuccess(() -> Component.translatable("villagequests.cmd.located", id.toString(),
                QuestText.kindName(target.kind()), QuestText.coords(target.pos()), dist, ms), false);
        return 1;
    }

    private static int rumors(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        for (String line : dev.villagechatter.quests.quest.Rumors.survey(ctx.getSource().getLevel(), pos)) {
            QuestsModule.LOGGER.info("rumor site {}", line);
            ctx.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ActiveQuest quest = QuestManager.byIndex(player, IntegerArgumentType.getInteger(ctx, "n"));
        if (quest == null) return 0;
        var level = player.server.getLevel(quest.dimension);
        if (level == null) return 0;
        // Land a few blocks away from the target (in open air) so the guardian spawns in front of you.
        net.minecraft.core.BlockPos near = quest.target.offset(6, 0, 0);
        level.getChunk(near.getX() >> 4, near.getZ() >> 4);
        level.getChunk(quest.target.getX() >> 4, quest.target.getZ() >> 4);
        net.minecraft.core.BlockPos spot = dev.villagechatter.quests.quest.TargetFinder.findSpawnSpot(level, near, 10);
        if (spot == null) spot = quest.target;
        player.teleportTo(level, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, player.getYRot(), player.getXRot());
        return 1;
    }

    private static int expire(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ActiveQuest quest = QuestManager.byIndex(player, IntegerArgumentType.getInteger(ctx, "n"));
        if (quest == null || !quest.isActive()) return 0;
        quest.deadline = player.serverLevel().getGameTime();
        return 1;
    }
}
