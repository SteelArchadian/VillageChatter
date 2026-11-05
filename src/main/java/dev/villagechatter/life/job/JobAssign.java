package dev.villagechatter.life.job;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.villagechatter.ChatterAttachments;
import dev.villagechatter.ChatterConfig;
import dev.villagechatter.network.ChatterNetwork;
import dev.villagechatter.network.ChatterNetwork.DialogueAction;
import dev.villagechatter.server.ConversationManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * "About your job": a player gives a villager a profession and a workstation. The old workstation is let go, the new
 * one claimed, and the job is remembered so the villager keeps it when its block is broken: vanilla would make it
 * jobless, so the job is put back and it looks for another free block. Whoever worked there before loses their job
 * (trades and all) and waits, jobless, until a player assigns them. Villagers no player touched stay vanilla.
 */
public final class JobAssign {
    private JobAssign() {}

    /** The dialogue option id. */
    public static final String OPTION = "job";

    public enum Outcome { ASSIGNED, BUMPED, ALREADY, CANT_WORK, LOCKED, WRONG_BLOCK, TAKEN }

    /** What happened, and who lost the workstation if someone did. */
    public record Result(Outcome outcome, @Nullable Villager previousOwner) {
        public boolean ok() {
            return outcome == Outcome.ASSIGNED || outcome == Outcome.BUMPED || outcome == Outcome.ALREADY;
        }
    }

    /** Picking a workstation out in the world: the villager waits where it is until this tick. */
    private record Pick(Villager villager, int until) {}

    private static final Map<UUID, Pick> PICKING = new HashMap<>();

    // ------------------------------------------------------------------ the screen's data

    public static void openFor(ServerPlayer player, Villager villager) {
        if (!ChatterConfig.JOB_ASSIGNMENT.get()) return;
        int flags = 0;
        if (villager.isBaby()) flags |= JobNetwork.FLAG_KID;
        if (villager.getVillagerData().getProfession() == VillagerProfession.NITWIT) flags |= JobNetwork.FLAG_NITWIT;
        if (Jobs.hasTraded(villager)) flags |= JobNetwork.FLAG_TRADED;
        if (Jobs.hasTraded(villager) && !ChatterConfig.RETRAIN_TRADED.get()) flags |= JobNetwork.FLAG_TRADED_LOCKED;
        int radius = ChatterConfig.JOB_PICK_RADIUS.get();
        PacketDistributor.sendToPlayer(player, new JobNetwork.JobInfo(villager.getId(),
                Jobs.id(villager.getVillagerData().getProfession()).toString(), flags, radius,
                ChatterConfig.JOB_PICK_SECONDS.get(), sites((ServerLevel) villager.level(), villager, radius)));
    }

    /** Every workstation within the radius, nearest first, with whoever works there. */
    public static List<JobNetwork.Site> sites(ServerLevel level, Villager villager, int radius) {
        Map<BlockPos, Villager> owners = owners(level, villager.blockPosition(), radius + 32);
        PoiManager poi = level.getPoiManager();
        BlockPos center = villager.blockPosition();
        List<JobNetwork.Site> out = new ArrayList<>();
        poi.getInRange(h -> Jobs.forPoi(h) != null, center, radius, PoiManager.Occupancy.ANY)
                .sorted(Comparator.comparingDouble(r -> r.getPos().distSqr(center)))
                .limit(512)
                .forEach(r -> {
                    VillagerProfession job = Jobs.forPoi(r.getPoiType());
                    Villager owner = owners.get(r.getPos());
                    Component name;
                    String ownerJob = "";
                    if (owner != null) {
                        name = owner.getName();
                        ownerJob = Jobs.id(owner.getVillagerData().getProfession()).toString();
                    } else {
                        // Claimed by a villager we can't see (unloaded, far off): still not free.
                        name = poi.getFreeTickets(r.getPos()) > 0 ? Component.empty() : Component.translatable("villagechatter.job.someone");
                    }
                    out.add(new JobNetwork.Site(r.getPos(), Jobs.id(job).toString(), name, ownerJob, owner == villager));
                });
        return out;
    }

    private static Map<BlockPos, Villager> owners(ServerLevel level, BlockPos center, int range) {
        Map<BlockPos, Villager> owners = new HashMap<>();
        for (Villager v : level.getEntitiesOfClass(Villager.class, new net.minecraft.world.phys.AABB(center).inflate(range))) {
            v.getBrain().getMemory(MemoryModuleType.JOB_SITE)
                    .filter(g -> g.dimension() == level.dimension())
                    .ifPresent(g -> owners.put(g.pos(), v));
        }
        return owners;
    }

    // ------------------------------------------------------------------ assigning

    public static Result assign(Villager villager, VillagerProfession job, BlockPos pos) {
        ServerLevel level = (ServerLevel) villager.level();
        if (!Jobs.canWork(villager)) return new Result(Outcome.CANT_WORK, null);
        boolean changing = villager.getVillagerData().getProfession() != job;
        if (changing && Jobs.hasTraded(villager) && !ChatterConfig.RETRAIN_TRADED.get()) return new Result(Outcome.LOCKED, null);
        PoiManager poi = level.getPoiManager();
        Optional<Holder<PoiType>> type = poi.getType(pos);
        if (type.isEmpty() || !job.heldJobSite().test(type.get())) return new Result(Outcome.WRONG_BLOCK, null);

        GlobalPos target = GlobalPos.of(level.dimension(), pos);
        Optional<GlobalPos> old = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
        boolean alreadyHere = old.isPresent() && old.get().equals(target);
        if (alreadyHere && !changing) {
            villager.setData(ChatterAttachments.ASSIGNED_JOB, Jobs.id(job).toString());
            return new Result(Outcome.ALREADY, null);
        }
        Villager owner = alreadyHere ? null : owners(level, pos, 48).get(pos);
        if (owner == villager) owner = null;
        if (!alreadyHere && owner == null && poi.getFreeTickets(pos) <= 0) return new Result(Outcome.TAKEN, null);

        // Let go of the old workstation first: vanilla's release checks the block still suits the current job.
        if (!alreadyHere) {
            villager.releasePoi(MemoryModuleType.JOB_SITE);
            villager.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
        }
        villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        if (owner != null) {
            // The claim passes straight from the owner to this villager; the owner is out of a job.
            owner.getBrain().eraseMemory(MemoryModuleType.JOB_SITE);
            unemploy(owner);
        } else if (!alreadyHere && !take(poi, pos)) {
            return new Result(Outcome.TAKEN, null);
        }

        if (changing) {
            // A new trade from scratch: vanilla clears the offers itself when the profession changes.
            if (Jobs.hasTraded(villager)) {
                villager.setVillagerXp(0);
                villager.setVillagerData(villager.getVillagerData().setLevel(1));
            }
            villager.setVillagerData(villager.getVillagerData().setProfession(job));
            villager.refreshBrain(level);
        }
        villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, target);
        villager.setData(ChatterAttachments.ASSIGNED_JOB, Jobs.id(job).toString());
        villager.removeData(ChatterAttachments.AWAITING_JOB);
        level.broadcastEntityEvent(villager, (byte) 14); // the happy sparkles vanilla shows on getting a job
        return new Result(owner != null ? Outcome.BUMPED : Outcome.ASSIGNED, owner);
    }

    /** Out of a job: no profession, no trades (even a veteran's), and no taking a new job until a player gives one. */
    static void unemploy(Villager villager) {
        villager.removeData(ChatterAttachments.ASSIGNED_JOB);
        villager.setVillagerXp(0);
        villager.setVillagerData(villager.getVillagerData().setLevel(1).setProfession(VillagerProfession.NONE));
        villager.setOffers(new net.minecraft.world.item.trading.MerchantOffers());
        villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        villager.setData(ChatterAttachments.AWAITING_JOB, true);
        villager.refreshBrain((ServerLevel) villager.level());
    }

    /** Vanilla's jobless villagers claim any free workstation they find; one awaiting assignment lets go of it. */
    static void holdOffClaims(Villager villager) {
        if (villager.getVillagerData().getProfession() != VillagerProfession.NONE) {
            villager.removeData(ChatterAttachments.AWAITING_JOB); // given a job some other way (a command, another mod)
            return;
        }
        for (MemoryModuleType<GlobalPos> memory : List.of(MemoryModuleType.POTENTIAL_JOB_SITE, MemoryModuleType.JOB_SITE)) {
            Optional<GlobalPos> claimed = villager.getBrain().getMemory(memory);
            if (claimed.isEmpty()) continue;
            ServerLevel level = villager.level().getServer().getLevel(claimed.get().dimension());
            if (level != null) level.getPoiManager().release(claimed.get().pos());
            villager.getBrain().eraseMemory(memory);
        }
    }

    private static boolean take(PoiManager poi, BlockPos pos) {
        return poi.take(h -> true, (h, p) -> p.equals(pos), pos, 1).isPresent();
    }

    // ------------------------------------------------------------------ keeping an assigned job

    /** Vanilla makes a novice jobless when its workstation goes; an assigned villager gets its job back instead. */
    public static void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) return;
        if (villager.hasData(ChatterAttachments.AWAITING_JOB) && villager.getData(ChatterAttachments.AWAITING_JOB)) holdOffClaims(villager);
        if (villager.tickCount % 40 == 0 && villager.hasData(ChatterAttachments.ASSIGNED_JOB)) keep(villager);
    }

    public static void keep(Villager villager) {
        String id = villager.getData(ChatterAttachments.ASSIGNED_JOB);
        if (id.isEmpty() || !ChatterConfig.KEEP_ASSIGNED_JOB.get()) return;
        VillagerProfession want = Jobs.byId(id);
        VillagerProfession now = villager.getVillagerData().getProfession();
        if (want == null || now == want) return;
        if (now != VillagerProfession.NONE) {
            // Something else gave them another job (a command, another mod): that one wins.
            villager.removeData(ChatterAttachments.ASSIGNED_JOB);
            return;
        }
        villager.setVillagerData(villager.getVillagerData().setProfession(want));
        villager.refreshBrain((ServerLevel) villager.level());
    }

    // ------------------------------------------------------------------ requests and picking in the world

    public static void onRequest(ServerPlayer player, JobNetwork.JobRequest req) {
        if (!ChatterConfig.JOB_ASSIGNMENT.get() || !(player.level().getEntity(req.entityId()) instanceof Villager villager)) return;
        int radius = ChatterConfig.JOB_PICK_RADIUS.get();
        if (!villager.isAlive() || villager.distanceToSqr(player) > (radius + 8.0) * (radius + 8.0)) return;
        Pick pick = PICKING.get(player.getUUID());
        boolean picking = pick != null && pick.villager == villager;
        boolean talking = ConversationManager.isTalkingTo(player, villager);
        if (!picking && !talking) return;

        switch (req.action()) {
            case JobNetwork.CANCEL -> PICKING.remove(player.getUUID());
            case JobNetwork.PICK -> {
                if (Jobs.byId(req.profession()) == null) return;
                // The talk closes; the villager waits where it is while you look around.
                ConversationManager.endScripted(player);
                PICKING.put(player.getUUID(), new Pick(villager, player.server.getTickCount() + (ChatterConfig.JOB_PICK_SECONDS.get() + 5) * 20));
            }
            case JobNetwork.ASSIGN -> {
                VillagerProfession job = Jobs.byId(req.profession());
                if (job == null || req.pos().distSqr(villager.blockPosition()) > (radius + 2.0) * (radius + 2.0)) return;
                PICKING.remove(player.getUUID());
                Result result = assign(villager, job, req.pos());
                Component line = reply(villager, job, result);
                if (talking) {
                    List<String> next = new ArrayList<>(ConversationManager.villagerOptions(villager));
                    next.add(DialogueAction.GOODBYE);
                    PacketDistributor.sendToPlayer(player, new ChatterNetwork.DialogueLine(villager.getId(), "job", next,
                            new ChatterNetwork.Extras(Map.of(), Optional.of(line))));
                } else {
                    player.displayClientMessage(Component.translatable("villagechatter.job.said", villager.getName(), line), true);
                }
            }
            default -> {}
        }
    }

    /** Variants per reply (lang keys villagechatter.job.reply.&lt;kind&gt;.0..n-1). */
    private static final Map<String, Integer> VARIANTS = Map.of("assigned", 5, "bumped", 5, "kid", 4, "nitwit", 5, "locked", 4, "taken", 3);

    /** What the villager says about it. */
    static Component reply(Villager villager, VillagerProfession job, Result result) {
        return switch (result.outcome()) {
            case ASSIGNED, ALREADY -> variant(villager, "assigned", Jobs.name(job), Jobs.blockName(job));
            case BUMPED -> variant(villager, "bumped", result.previousOwner().getName(), Jobs.blockName(job));
            case CANT_WORK -> variant(villager, villager.isBaby() ? "kid" : "nitwit");
            case LOCKED -> variant(villager, "locked");
            case WRONG_BLOCK, TAKEN -> variant(villager, "taken");
        };
    }

    private static Component variant(Villager villager, String kind, Object... args) {
        return Component.translatable("villagechatter.job.reply." + kind + "." + villager.getRandom().nextInt(VARIANTS.get(kind)), args);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (PICKING.isEmpty()) return;
        int now = event.getServer().getTickCount();
        double range = ChatterConfig.JOB_PICK_RADIUS.get() + 8.0;
        Iterator<Map.Entry<UUID, Pick>> it = PICKING.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Pick> entry = it.next();
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            Villager villager = entry.getValue().villager;
            if (player == null || !villager.isAlive() || villager.level() != player.level() || now > entry.getValue().until
                    || villager.distanceToSqr(player) > range * range) {
                it.remove();
                continue;
            }
            ConversationManager.holdStill(villager, player);
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PICKING.remove(event.getEntity().getUUID());
    }
}
