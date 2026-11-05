package dev.villagechatter;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.villagechatter.api.Factions;
import dev.villagechatter.life.GuardTrust;
import dev.villagechatter.life.law.CrimeKind;
import dev.villagechatter.life.law.CrimeLedger;
import dev.villagechatter.life.law.Witnesses;
import dev.villagechatter.quests.QuestCommands;
import dev.villagechatter.reputation.Standing;
import dev.villagechatter.reputation.faction.FactionBook;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Test shortcuts for creative players and ops, so a feature can be tried at once:
 * /vc standing &lt;village|pillager|piglin&gt; &lt;value|max|min&gt;, /vc fame, /vc bounty &lt;n&gt;, /vc quest &lt;id&gt;,
 * /vc raid start|win|lose (win = the pillagers won), /vc info (what everyone nearby thinks of you, and why).
 */
public final class DebugCommands {
    private DebugCommands() {}

    private static final double INFO_RANGE = 16;

    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("vc")
                .requires(s -> s.hasPermission(2) || s.getPlayer() != null && s.getPlayer().isCreative())
                .then(Commands.literal("standing")
                        .then(Commands.argument("faction", StringArgumentType.word())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggest(new String[] {"village", "pillager", "piglin"}, b))
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(new String[] {"max", "min", "0"}, b))
                                        .executes(DebugCommands::standing))))
                .then(Commands.literal("fame").executes(DebugCommands::fame))
                .then(Commands.literal("bounty")
                        .then(Commands.argument("n", IntegerArgumentType.integer(0)).executes(DebugCommands::bounty)))
                .then(Commands.literal("quest")
                        .then(Commands.argument("quest", ResourceLocationArgument.id())
                                .suggests((ctx, b) -> SharedSuggestionProvider.suggestResource(
                                        dev.villagechatter.quests.quest.QuestDefinitions.all().keySet(), b))
                                .executes(ctx -> QuestCommands.offer(ctx, ResourceLocationArgument.getId(ctx, "quest")))))
                .then(Commands.literal("raid")
                        .then(Commands.literal("start").executes(DebugCommands::raidStart))
                        .then(Commands.literal("win").executes(ctx -> raidEnd(ctx, true)))
                        .then(Commands.literal("lose").executes(ctx -> raidEnd(ctx, false))))
                .then(Commands.literal("info").executes(DebugCommands::info)));
    }

    private static int standing(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Factions.Faction faction;
        try {
            faction = Factions.Faction.valueOf(StringArgumentType.getString(ctx, "faction").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("Faction: village, pillager or piglin"));
            return 0;
        }
        String raw = StringArgumentType.getString(ctx, "value");
        double target;
        if (raw.equals("max")) target = FactionBook.MAX;
        else if (raw.equals("min")) target = FactionBook.MIN;
        else {
            try {
                target = Math.max(FactionBook.MIN, Math.min(FactionBook.MAX, Double.parseDouble(raw)));
            } catch (NumberFormatException e) {
                ctx.getSource().sendFailure(Component.literal("Value: a number from -100 to 100, max or min"));
                return 0;
            }
        }
        if (!FactionBook.enabled()) {
            ctx.getSource().sendFailure(Component.literal("Factions are off (config)"));
            return 0;
        }
        double now = current(player, faction);
        // The village meter is an average over the villages you know plus a roaming part; moving the roaming part
        // by the difference lands it where asked.
        FactionBook.adjust(player, faction, target - now);
        double after = current(player, faction);
        ctx.getSource().sendSuccess(() -> Component.literal(faction.name().toLowerCase(Locale.ROOT) + ": "
                + FactionBook.signed(now) + " -> " + FactionBook.signed(after)), false);
        return 1;
    }

    private static double current(ServerPlayer player, Factions.Faction faction) {
        return switch (faction) {
            case VILLAGE -> FactionBook.village(player);
            case PILLAGER -> FactionBook.pillager(player);
            case PIGLIN -> FactionBook.piglin(player);
        };
    }

    /** Hero of the Village for a Minecraft day, the "famous" state every check looks at. */
    private static int fame(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 24000, 0, false, true));
        ctx.getSource().sendSuccess(() -> Component.literal("Hero of the Village for one day"), false);
        return 1;
    }

    /** A reported theft with this fine at the nearest village, so its guards come for you; 0 clears your record there. */
    private static int bounty(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GlobalPos> village = Witnesses.village(level, player.blockPosition());
        if (village.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No village bell within 64 blocks"));
            return 0;
        }
        int fine = IntegerArgumentType.getInteger(ctx, "n");
        CrimeLedger ledger = CrimeLedger.get(level.getServer());
        CrimeLedger.Standing standing = ledger.standing(player.getUUID(), village.get());
        if (fine == 0) {
            standing.crimes.clear();
            standing.hostileUntil = 0;
            ctx.getSource().sendSuccess(() -> Component.literal("Record cleared at this village"), false);
        } else {
            CrimeLedger.Crime crime = new CrimeLedger.Crime(CrimeKind.THEFT, village.get(), null, level.getGameTime());
            crime.reported = true;
            crime.seenByGuard = true;
            crime.fine = fine;
            standing.crimes.add(crime);
            ctx.getSource().sendSuccess(() -> Component.literal("Wanted here: a theft, fine " + fine + " emeralds"), false);
        }
        ledger.setDirty();
        return 1;
    }

    private static int raidStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Raid raid = player.serverLevel().getRaids().createOrExtendRaid(player, player.blockPosition());
        if (raid == null) {
            ctx.getSource().sendFailure(Component.literal("No raid: stand in a village (near beds and villagers), not on Peaceful"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Raid started"), false);
        return 1;
    }

    /** The pillagers' reaction to a raid ending: "win" = the pillagers won, "lose" = the village held. */
    private static int raidEnd(CommandContext<CommandSourceStack> ctx, boolean pillagersWon) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!dev.villagechatter.reputation.faction.RaidOutcome.settle(player, pillagersWon)) {
            ctx.getSource().sendFailure(Component.literal("The village held and you weren't on the pillagers' side: nothing to say"));
            return 0;
        }
        return 1;
    }

    /** Everyone nearby who has an opinion of you, nearest first, with the numbers behind it. */
    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("Factions: village " + FactionBook.signed(FactionBook.village(player))
                + ", pillager " + FactionBook.signed(FactionBook.pillager(player)) + " (" + FactionBook.tier(FactionBook.pillager(player))
                .name().toLowerCase(Locale.ROOT) + ")" + ", piglin " + FactionBook.signed(FactionBook.piglin(player))
                + (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE) ? ", Hero" : "")).withStyle(ChatFormatting.GOLD), false);

        Witnesses.village(level, player.blockPosition()).ifPresent(village -> {
            CrimeLedger.Standing s = CrimeLedger.get(level.getServer()).all(player.getUUID()).get(village);
            if (s == null || s.crimes.isEmpty() && !s.hostile()) return;
            StringBuilder crimes = new StringBuilder();
            for (CrimeLedger.Crime c : s.crimes) {
                crimes.append(crimes.isEmpty() ? "" : ", ").append(c.kind.name().toLowerCase(Locale.ROOT))
                        .append(c.reported ? " (reported" : " (unreported").append(c.fine > 0 ? ", fine " + c.fine : "").append(")");
            }
            src.sendSuccess(() -> Component.literal("This village: " + (s.hostile() ? "attack on sight; " : s.wanted() ? "wanted; " : "")
                    + crimes).withStyle(ChatFormatting.RED), false);
        });

        var near = level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(INFO_RANGE),
                        e -> e != player && e.isAlive() && opinion(player, e) != null).stream()
                .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(player))).limit(12).toList();
        if (near.isEmpty()) src.sendSuccess(() -> Component.literal("Nobody nearby has an opinion of you"), false);
        for (LivingEntity e : near) {
            MutableComponent line = e.getName().copy().withStyle(ChatFormatting.YELLOW).append(": ").append(opinion(player, e));
            src.sendSuccess(() -> line, false);
        }
        return near.size();
    }

    /** What this one thinks and why, or null for things without an opinion. */
    private static Component opinion(ServerPlayer player, Entity e) {
        if (e instanceof Villager v) {
            StringBuilder why = new StringBuilder();
            var gossips = v.getGossips().getGossipEntries().get(player.getUUID());
            if (gossips != null) gossips.forEach((type, value) -> why.append(why.isEmpty() ? "" : ", ").append(type.getSerializedName()).append(' ').append(value));
            double goodwill = player.getData(dev.villagechatter.quests.QuestRegistries.GOODWILL).get(v.getUUID());
            if (goodwill != 0) why.append(why.isEmpty() ? "" : ", ").append("goodwill ").append(FactionBook.signed(goodwill));
            Standing.View view = Standing.forVillager(player, v);
            return Component.empty().append(view.title()).append(" " + FactionBook.signed(Standing.score(player, v))
                    + (why.isEmpty() ? " (never met)" : " (" + why + ")"));
        }
        if (GuardTrust.isGuard(e)) {
            return Component.literal("guard, trust " + FactionBook.signed(GuardTrust.score(player, e))
                    + (GuardTrust.trusts(player, e) ? " (trusts you)" : ""));
        }
        if (e instanceof IronGolem golem && !golem.isPlayerCreated()) {
            return Component.literal("golem, shared village opinion " + FactionBook.signed(
                    dev.villagechatter.life.law.VillageStanding.shared(player.serverLevel(), player, golem)));
        }
        if (e instanceof AbstractIllager) {
            return Component.empty().append(Standing.forPillagers(player).title());
        }
        if (e instanceof AbstractPiglin) {
            return Component.empty().append(Standing.forPiglins(player).title());
        }
        return null;
    }
}
