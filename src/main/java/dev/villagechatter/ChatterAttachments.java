package dev.villagechatter;

import java.util.function.Supplier;

import com.mojang.serialization.Codec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Per-entity data saved with the entity. */
public final class ChatterAttachments {
    private ChatterAttachments() {}

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, VillageChatter.MODID);

    /** Pet follow distance: 0 = close, 1 = normal (vanilla), 2 = loose. */
    public static final Supplier<AttachmentType<Integer>> FOLLOW_MODE = ATTACHMENTS.register("follow_mode",
            () -> AttachmentType.builder(() -> 1).serialize(Codec.INT).build());

    /** Pet was sent home and is waiting there. */
    public static final Supplier<AttachmentType<Boolean>> AT_HOME = ATTACHMENTS.register("at_home",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).build());

    /** Close friends' surprise gifts: when each villager might next have one for this player. */
    public static final Supplier<AttachmentType<dev.villagechatter.life.Kindness.Ledger>> FRIEND_GIFTS = ATTACHMENTS.register(
            "friend_gifts", () -> AttachmentType.builder(dev.villagechatter.life.Kindness.Ledger::new)
                    .serialize(dev.villagechatter.life.Kindness.Ledger.CODEC).copyOnDeath().build());

    /** The profession a player gave this villager ("About your job"); empty if vanilla picked it. Kept when its workstation breaks. */
    public static final Supplier<AttachmentType<String>> ASSIGNED_JOB = ATTACHMENTS.register("assigned_job",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());

    /** Lost their workstation to a villager a player assigned: jobless, and they don't take a job until a player gives them one. */
    public static final Supplier<AttachmentType<Boolean>> AWAITING_JOB = ATTACHMENTS.register("awaiting_job",
            () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).build());

    /** Faction standings: the villages this player knows, word on the road, and the pillagers. */
    public static final Supplier<AttachmentType<dev.villagechatter.reputation.faction.FactionBook.Book>> FACTIONS = ATTACHMENTS.register(
            "factions", () -> AttachmentType.builder(dev.villagechatter.reputation.faction.FactionBook.Book::new)
                    .serialize(dev.villagechatter.reputation.faction.FactionBook.Book.CODEC).copyOnDeath().build());

    /** How grubby the player is: {grime, blood}, 0 to 100 (life.Grime). Washed off by dying, too. */
    public static final Supplier<AttachmentType<java.util.List<Double>>> GRIME = ATTACHMENTS.register("grime",
            () -> AttachmentType.<java.util.List<Double>>builder(() -> java.util.List.of(0.0, 0.0)).serialize(Codec.DOUBLE.listOf()).build());

    /** Charity to beggars (life.Charity): {day, gifts that day counted for reputation, blessed until (game time)}. */
    public static final Supplier<AttachmentType<java.util.List<Long>>> CHARITY = ATTACHMENTS.register("charity",
            () -> AttachmentType.<java.util.List<Long>>builder(() -> java.util.List.of(-1L, 0L, 0L)).serialize(Codec.LONG.listOf()).build());

    /** Wrongs piglins or pillagers saw that haven't been told yet, or can still be undone (reputation.faction.Witnessed). */
    public static final Supplier<AttachmentType<java.util.List<dev.villagechatter.reputation.faction.Witnessed.Offense>>> OFFENSES = ATTACHMENTS.register(
            "offenses", () -> AttachmentType.<java.util.List<dev.villagechatter.reputation.faction.Witnessed.Offense>>builder(() -> java.util.List.of())
                    .serialize(dev.villagechatter.reputation.faction.Witnessed.Offense.LIST).copyOnDeath().build());

    /** The Speech skill, 0 to 100 (life.Speech). Kept through death. */
    public static final Supplier<AttachmentType<Double>> SPEECH = ATTACHMENTS.register("speech",
            () -> AttachmentType.builder(() -> 0.0).serialize(Codec.DOUBLE).copyOnDeath().build());

    /** Piglin timers (life.PiglinNature): out-of-Nether ticks on piglins, cure ticks on zombified ones. */
    public static final Supplier<AttachmentType<java.util.List<Integer>>> PIGLIN_NATURE = ATTACHMENTS.register("piglin_nature",
            () -> AttachmentType.<java.util.List<Integer>>builder(() -> java.util.List.of(0, 0)).serialize(dev.villagechatter.life.PiglinNature.CODEC).build());

    /** Who is curing this zombified piglin (a player UUID, or empty). */
    public static final Supplier<AttachmentType<String>> PIGLIN_CURER = ATTACHMENTS.register("piglin_curer",
            () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());

    /** The player's sabotage job and the day each side last offered one (reputation.faction.Sabotage). */
    public static final Supplier<AttachmentType<dev.villagechatter.reputation.faction.Sabotage.Book>> SABOTAGE = ATTACHMENTS.register(
            "sabotage", () -> AttachmentType.builder(dev.villagechatter.reputation.faction.Sabotage.Book::empty)
                    .serialize(dev.villagechatter.reputation.faction.Sabotage.Book.CODEC).copyOnDeath().build());

    static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }
}
