package dev.villagechatter.quests.journal;

import java.util.ArrayList;
import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.quest.ActiveQuest;
import dev.villagechatter.quests.quest.QuestDefinition;
import dev.villagechatter.quests.quest.QuestDefinitions;
import dev.villagechatter.quests.quest.QuestManager;
import dev.villagechatter.quests.quest.QuestText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server side of the quest journal (J): builds the list the client shows, and handles Give up / clear. */
public final class QuestJournal {
    private QuestJournal() {}

    public static List<QuestNetwork.Entry> entries(ServerPlayer player) {
        List<QuestNetwork.Entry> out = new ArrayList<>();
        long now = player.serverLevel().getGameTime();
        for (ActiveQuest quest : player.getData(QuestRegistries.PLAYER_QUESTS).quests) {
            QuestDefinition def = QuestDefinitions.get(quest.questId);
            Component title = def == null ? Component.literal(quest.questId.toString()) : QuestManager.title(def, quest);
            net.minecraft.server.level.ServerLevel level = player.server.getLevel(quest.dimension);
            if (level != null && level.isLoaded(quest.giverPos)) dev.villagechatter.reputation.Villages.learnName(level, quest.giverPos);
            Component where = Component.translatable("villagequests.journal.location",
                    dev.villagechatter.reputation.Villages.label(level, quest.giverPos), dimensionName(quest.dimension.location()));
            boolean failed = quest.state == ActiveQuest.State.FAILED;
            Component status = failed
                    ? quest.failReasonText().copy().withStyle(ChatFormatting.RED)
                    : QuestManager.status(player, quest, def, now);
            out.add(new QuestNetwork.Entry(quest.instance, title, quest.giverName, where, status, failed));
        }
        return out;
    }

    public static void send(ServerPlayer player) {
        // Clients without the mod (or test players) can't take the packet.
        if (!player.connection.hasChannel(QuestNetwork.JournalData.TYPE)) return;
        PacketDistributor.sendToPlayer(player, new QuestNetwork.JournalData(entries(player), dev.villagechatter.reputation.faction.FactionBook.journalLine(player)));
    }

    public static void onAction(ServerPlayer player, QuestNetwork.QuestAction action) {
        player.getData(QuestRegistries.PLAYER_QUESTS).byInstance(action.instance()).ifPresent(quest -> {
            switch (action.action()) {
                case QuestNetwork.QuestAction.QUIT -> {
                    if (quest.isActive() || quest.state == ActiveQuest.State.EXPIRED) QuestManager.quit(player, quest, null);
                }
                case QuestNetwork.QuestAction.DISMISS -> {
                    if (quest.state == ActiveQuest.State.FAILED) player.getData(QuestRegistries.PLAYER_QUESTS).quests.remove(quest);
                }
                default -> {}
            }
        });
        send(player);
    }

    private static Component dimensionName(ResourceLocation id) {
        return Component.translatableWithFallback("villagequests.dimension." + id.getNamespace() + "." + id.getPath(), id.getPath().replace('_', ' '));
    }
}
