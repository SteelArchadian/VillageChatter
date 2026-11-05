package dev.villagechatter.quests;

import java.util.function.Supplier;

import dev.villagechatter.quests.item.QuestNoteItem;
import dev.villagechatter.quests.quest.Discounts;
import dev.villagechatter.quests.quest.GiverData;
import dev.villagechatter.quests.quest.Goodwill;
import dev.villagechatter.quests.quest.GuardianData;
import dev.villagechatter.quests.quest.HeirloomData;
import dev.villagechatter.quests.quest.NoteData;
import dev.villagechatter.quests.quest.PlayerQuests;
import dev.villagechatter.quests.quest.Rumors;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class QuestRegistries {
    private QuestRegistries() {}

    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(QuestsModule.NAMESPACE);
    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, QuestsModule.NAMESPACE);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, QuestsModule.NAMESPACE);

    // ---- items
    /** No longer handed out (the journal tracks quests); kept registered so notes in older worlds still load. */
    public static final DeferredItem<QuestNoteItem> QUEST_NOTE =
            ITEMS.registerItem("quest_note", QuestNoteItem::new, new Item.Properties().stacksTo(1));

    // ---- item components
    /** What a quest note says: synced to the client for the tooltip. */
    public static final Supplier<DataComponentType<NoteData>> NOTE = COMPONENTS.registerComponentType("note",
            b -> b.persistent(NoteData.CODEC).networkSynchronized(NoteData.STREAM_CODEC));
    /** Marks the item a guardian dropped, so its giver recognizes it. The item itself is an ordinary item. */
    public static final Supplier<DataComponentType<HeirloomData>> HEIRLOOM = COMPONENTS.registerComponentType("heirloom",
            b -> b.persistent(HeirloomData.CODEC).networkSynchronized(HeirloomData.STREAM_CODEC));
    /** Marks a courier package (or the reply letter) with its quest, so it can't be mixed up with ordinary items. */
    public static final Supplier<DataComponentType<java.util.UUID>> PARCEL = COMPONENTS.registerComponentType("parcel",
            b -> b.persistent(net.minecraft.core.UUIDUtil.CODEC).networkSynchronized(net.minecraft.core.UUIDUtil.STREAM_CODEC));

    // ---- entity attachments
    public static final Supplier<AttachmentType<PlayerQuests>> PLAYER_QUESTS = ATTACHMENTS.register("quests",
            () -> AttachmentType.builder(PlayerQuests::new).serialize(PlayerQuests.CODEC).copyOnDeath().build());
    public static final Supplier<AttachmentType<Goodwill>> GOODWILL = ATTACHMENTS.register("goodwill",
            () -> AttachmentType.builder(Goodwill::new).serialize(Goodwill.CODEC).copyOnDeath().build());
    public static final Supplier<AttachmentType<Discounts>> DISCOUNTS = ATTACHMENTS.register("discounts",
            () -> AttachmentType.builder(Discounts::new).serialize(Discounts.CODEC).copyOnDeath().build());
    /** Courier trips: the name of the specific villager each package is for, once known. */
    public static final Supplier<AttachmentType<dev.villagechatter.quests.quest.Courier.Names>> COURIER_NAMES =
            ATTACHMENTS.register("courier_names", () -> AttachmentType.builder(dev.villagechatter.quests.quest.Courier.Names::new)
                    .serialize(dev.villagechatter.quests.quest.Courier.Names.CODEC).copyOnDeath().build());
    public static final Supplier<AttachmentType<Rumors.Told>> RUMORS_TOLD = ATTACHMENTS.register("rumors_told",
            () -> AttachmentType.builder(Rumors.Told::new).serialize(Rumors.Told.CODEC).copyOnDeath().build());
    /** A wandering trader's rumors (on the trader). */
    public static final Supplier<AttachmentType<dev.villagechatter.quests.quest.TraderRumors.Stock>> TRADER_RUMORS = ATTACHMENTS.register(
            "trader_rumors", () -> AttachmentType.builder(dev.villagechatter.quests.quest.TraderRumors.Stock::new)
                    .serialize(dev.villagechatter.quests.quest.TraderRumors.Stock.CODEC).build());
    public static final Supplier<AttachmentType<dev.villagechatter.reputation.Gifts.Given>> GIFTS_GIVEN = ATTACHMENTS.register("gifts_given",
            () -> AttachmentType.builder(dev.villagechatter.reputation.Gifts.Given::new).serialize(dev.villagechatter.reputation.Gifts.Given.CODEC).copyOnDeath().build());
    public static final Supplier<AttachmentType<GiverData>> GIVER = ATTACHMENTS.register("quest_giver",
            () -> AttachmentType.builder(() -> GiverData.NONE).serialize(GiverData.CODEC).build());
    public static final Supplier<AttachmentType<GuardianData>> CAPTIVE = ATTACHMENTS.register("captive",
            () -> AttachmentType.builder(() -> GuardianData.NONE).serialize(GuardianData.CODEC).build());
    public static final Supplier<AttachmentType<GuardianData>> GUARDIAN = ATTACHMENTS.register("guardian",
            () -> AttachmentType.builder(() -> GuardianData.NONE).serialize(GuardianData.CODEC).build());

    static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        COMPONENTS.register(modEventBus);
        ATTACHMENTS.register(modEventBus);
    }
}
