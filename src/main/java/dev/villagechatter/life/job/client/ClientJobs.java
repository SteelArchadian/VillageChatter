package dev.villagechatter.life.job.client;

import java.util.List;

import dev.villagechatter.client.DialogueScreen;
import dev.villagechatter.life.job.JobNetwork;
import dev.villagechatter.life.job.Jobs;
import dev.villagechatter.quests.quest.QuestText;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerProfession;

/** Client side of "About your job": opens the job screen over the conversation, plus a few shared bits of wording. */
public final class ClientJobs {
    private ClientJobs() {}

    public static void receive(JobNetwork.JobInfo info) {
        Minecraft mc = Minecraft.getInstance();
        Entity villager = mc.level == null ? null : mc.level.getEntity(info.entityId());
        if (villager != null && mc.screen instanceof DialogueScreen dialogue) {
            dialogue.openChild(new JobScreen(dialogue, villager, info));
        }
    }

    /** "12 m north-east" from the villager. */
    static Component where(Entity villager, BlockPos pos) {
        int dx = pos.getX() - villager.getBlockX(), dz = pos.getZ() - villager.getBlockZ();
        int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dz * dz + Math.pow(pos.getY() - villager.getBlockY(), 2)));
        if (dist < 2) return Component.translatable("villagechatter.job.site.here");
        return Component.translatable("villagechatter.job.site.where", dist,
                Component.translatable("villagequests.dir." + QuestText.direction(dx, dz)));
    }

    /** "Free", "Theirs already", or "Claimed by Mildred (Librarian)". */
    static Component status(JobNetwork.Site site) {
        if (site.theirs()) return Component.translatable("villagechatter.job.site.theirs");
        if (site.free()) return Component.translatable("villagechatter.job.site.free");
        VillagerProfession job = Jobs.byId(site.ownerJob());
        return job == null || site.ownerJob().isEmpty()
                ? Component.translatable("villagechatter.job.site.claimed_by", site.owner())
                : Component.translatable("villagechatter.job.site.claimed", site.owner(), Jobs.name(job));
    }

    /** The confirm question's body: who works there now, and what the villager gives up. */
    static Component confirmMessage(Component name, VillagerProfession job, JobNetwork.Site site, JobNetwork.JobInfo info) {
        Component first = site == null || site.free() || site.theirs()
                ? Component.translatable("villagechatter.job.confirm.free", name, Jobs.name(job))
                : Component.translatable("villagechatter.job.confirm.claimed", site.owner());
        boolean changing = !info.current().equals(Jobs.id(job).toString());
        if (changing && info.has(JobNetwork.FLAG_TRADED)) {
            return first.copy().append("\n\n").append(Component.translatable("villagechatter.job.traded_warn", name));
        }
        return first;
    }

    static List<JobNetwork.Site> sitesFor(JobNetwork.JobInfo info, VillagerProfession job) {
        String id = Jobs.id(job).toString();
        return info.sites().stream().filter(s -> s.profession().equals(id)).toList();
    }
}
