package dev.villagechatter.quests.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;

/** A player's running (and expired-but-not-yet-settled) quests. */
public final class PlayerQuests {
    public static final Codec<PlayerQuests> CODEC =
            ActiveQuest.CODEC.listOf().xmap(PlayerQuests::new, p -> p.quests);

    public final List<ActiveQuest> quests;

    public PlayerQuests() {
        this.quests = new ArrayList<>();
    }

    private PlayerQuests(List<ActiveQuest> quests) {
        this.quests = new ArrayList<>(quests);
    }

    public long activeCount() {
        return quests.stream().filter(ActiveQuest::isActive).count();
    }

    public Optional<ActiveQuest> byInstance(UUID instance) {
        return quests.stream().filter(q -> q.instance.equals(instance)).findFirst();
    }

    /** Quests from this villager that still matter (failed ones only live on in the journal). */
    public List<ActiveQuest> fromGiver(UUID giver) {
        return quests.stream().filter(q -> q.giver.equals(giver) && q.state != ActiveQuest.State.FAILED).toList();
    }

    /** Keeps the journal tidy: only the most recent failed quests stay listed. */
    public void pruneFailed(int keep) {
        List<ActiveQuest> failed = quests.stream().filter(q -> q.state == ActiveQuest.State.FAILED).toList();
        for (int i = 0; i < failed.size() - keep; i++) quests.remove(failed.get(i));
    }
}
