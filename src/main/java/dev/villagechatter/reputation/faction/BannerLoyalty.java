package dev.villagechatter.reputation.faction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

import com.mojang.logging.LogUtils;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.VillageChatter;
import dev.villagechatter.api.Factions;
import dev.villagechatter.api.Factions.Faction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.PatrollingMonster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Wearing the pillagers' colours (Factions v2, item 5 revised). Ways to wear them: an ominous shield in either hand,
 * the ominous banner on your head (right-click it in the air with your head bare), a helmet crested with the banner
 * (helmet + banner in a crafting grid), or the banner in a Curios head slot when Curios is installed.
 * <ul>
 *   <li>Outpost pillagers read your standing as at least Tolerated while you wear them, so they talk and keep the
 *   truce. Patrols and raids go by your true standing (see {@link FactionBook#truce}).</li>
 *   <li>A pillager who sees you below Tolerated without them attacks, and you lose a little standing (once per
 *   outpost or patrol per cooldown).</li>
 *   <li>A villager who sees you wearing them costs you village standing (once per village per cooldown).</li>
 * </ul>
 */
public final class BannerLoyalty {
    private BannerLoyalty() {}

    private static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, VillageChatter.MODID);
    private static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, VillageChatter.MODID);

    /** On a helmet: the ominous banner is fixed on top. */
    public static final java.util.function.Supplier<DataComponentType<Unit>> CREST = COMPONENTS.registerComponentType("ominous_crest",
            b -> b.persistent(Unit.CODEC).networkSynchronized(net.minecraft.network.codec.StreamCodec.unit(Unit.INSTANCE)));
    public static final java.util.function.Supplier<RecipeSerializer<CrestRecipe>> CREST_RECIPE =
            SERIALIZERS.register("banner_crest", () -> new SimpleCraftingRecipeSerializer<>(CrestRecipe::new));

    /** How far (blocks) sightings reach. */
    static final int SIGHT = 16;

    public static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
        SERIALIZERS.register(modBus);
    }

    // ------------------------------------------------------------------ what counts

    /** The ominous banner's pattern on a banner or shield (any base colour). */
    public static boolean isOminous(ItemStack stack, HolderLookup.Provider registries) {
        if (!(stack.getItem() instanceof BannerItem) && !(stack.getItem() instanceof ShieldItem)) return false;
        BannerPatternLayers layers = stack.get(DataComponents.BANNER_PATTERNS);
        if (layers == null || layers.layers().isEmpty()) return false;
        return Objects.equals(layers, Raid.getLeaderBannerInstance(registries.lookupOrThrow(Registries.BANNER_PATTERN))
                .get(DataComponents.BANNER_PATTERNS));
    }

    public static boolean hasCrest(ItemStack stack) {
        return stack.has(CREST.get());
    }

    /** Wearing the colours, in any of the four ways (with banner loyalty on). */
    public static boolean wearing(ServerPlayer player) {
        if (!ChatterConfig.BANNER_LOYALTY.get()) return false;
        var registries = player.registryAccess();
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (head.getItem() instanceof BannerItem && isOminous(head, registries)) return true;
        if (hasCrest(head)) return true;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (held.getItem() instanceof ShieldItem && isOminous(held, registries)) return true;
        }
        return curiosHead(player, s -> s.getItem() instanceof BannerItem && isOminous(s, registries) || hasCrest(s));
    }

    private static boolean curiosBroken;

    /** A Curios slot holding it (soft dependency, through Curios' public API). */
    public static boolean curiosHead(LivingEntity entity, Predicate<ItemStack> match) {
        if (curiosBroken || !ModList.get().isLoaded("curios")) return false;
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Optional<?> inventory = (Optional<?>) api.getMethod("getCuriosInventory", LivingEntity.class).invoke(null, entity);
            if (inventory.isEmpty()) return false;
            Class<?> handler = Class.forName("top.theillusivec4.curios.api.type.capability.ICuriosItemHandler");
            Optional<?> found = (Optional<?>) handler.getMethod("findFirstCurio", Predicate.class).invoke(inventory.get(), match);
            return found.isPresent();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            curiosBroken = true;
            LogUtils.getLogger().warn("VillageChatter: Curios API not as expected, banners in Curios slots won't count", e);
            return false;
        }
    }

    /** A wild patrol (or a raid): these go by your true standing whatever you wear. */
    static boolean wild(Raider raider) {
        return raider.hasActiveRaid() || raider.isPatrolLeader() || (raider instanceof PatrollingMonster p && p.hasPatrolTarget());
    }

    // ------------------------------------------------------------------ putting the banner on

    /** Right-click with the ominous banner in the air and a bare head: it goes on your head, like a captain's. */
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (wear(player, event.getItemStack())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    static boolean wear(ServerPlayer player, ItemStack stack) {
        if (!ChatterConfig.BANNER_LOYALTY.get() || !(stack.getItem() instanceof BannerItem)
                || !isOminous(stack, player.registryAccess()) || !player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()) {
            return false;
        }
        player.setItemSlot(EquipmentSlot.HEAD, stack.copyWithCount(1));
        if (!player.getAbilities().instabuild) stack.shrink(1);
        player.level().playSound(null, player.blockPosition(), SoundEvents.ARMOR_EQUIP_LEATHER.value(), player.getSoundSource(), 1.0F, 1.0F);
        return true;
    }

    /** Helmet + ominous banner in a crafting grid: the helmet wears the banner. */
    public static final class CrestRecipe extends CustomRecipe {
        public CrestRecipe(CraftingBookCategory category) {
            super(category);
        }

        @Override
        public boolean matches(CraftingInput input, Level level) {
            return !assemble(input, level.registryAccess()).isEmpty();
        }

        @Override
        public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
            ItemStack helmet = ItemStack.EMPTY;
            boolean banner = false;
            for (int i = 0; i < input.size(); i++) {
                ItemStack s = input.getItem(i);
                if (s.isEmpty()) continue;
                if (s.getItem() instanceof ArmorItem armor && armor.getEquipmentSlot() == EquipmentSlot.HEAD && !hasCrest(s) && helmet.isEmpty()) {
                    helmet = s;
                } else if (s.getItem() instanceof BannerItem && isOminous(s, registries) && !banner) {
                    banner = true;
                } else {
                    return ItemStack.EMPTY;
                }
            }
            if (helmet.isEmpty() || !banner) return ItemStack.EMPTY;
            ItemStack out = helmet.copyWithCount(1);
            out.set(CREST.get(), Unit.INSTANCE);
            return out;
        }

        @Override
        public boolean canCraftInDimensions(int width, int height) {
            return width * height >= 2;
        }

        @Override
        public RecipeSerializer<?> getSerializer() {
            return CREST_RECIPE.get();
        }
    }

    // ------------------------------------------------------------------ being seen

    /** "player|place" -> game time the loss last landed. */
    private static final Map<String, Long> SEEN = new HashMap<>();

    /** Marks a raider VC set on a player on purpose, so Encounters' holdups leave it alone. */
    public static final String ORDERED_ATTACK = "villagechatter_ordered_attack";

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 40 == 0 && FactionBook.enabled()
                && ChatterConfig.BANNER_LOYALTY.get() && !player.isSpectator() && !player.isCreative()) {
            sightings(player);
        }
    }

    /** Who sees you now, and what it costs. */
    static void sightings(ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        var box = player.getBoundingBox().inflate(SIGHT);
        if (!wearing(player)) {
            if (FactionBook.tier(FactionBook.pillager(player)).ordinal() >= Factions.Tier.TOLERATED.ordinal()) return;
            for (Raider raider : player.serverLevel().getEntitiesOfClass(Raider.class, box,
                    r -> r.isAlive() && !r.isNoAi() && r.hasLineOfSight(player))) {
                if (raider.getTarget() == null) {
                    raider.addTag(ORDERED_ATTACK);
                    raider.setTarget(player);
                }
                if (once(player, "pillager|" + placeOf(raider), now)) {
                    FactionBook.adjust(player, Faction.PILLAGER, -ChatterConfig.BANNER_UNSEEN_LOSS.get());
                }
            }
            return;
        }
        List<Villager> villagers = player.serverLevel().getEntitiesOfClass(Villager.class, box,
                v -> v.isAlive() && !v.isBaby() && v.hasLineOfSight(player));
        for (Villager villager : villagers) {
            var home = dev.villagechatter.reputation.Villages.center(player.serverLevel(), villager);
            if (once(player, "village|" + (home.getX() >> 6) + "," + (home.getZ() >> 6), now)) {
                FactionBook.adjust(player, Faction.VILLAGE, -ChatterConfig.BANNER_VILLAGE_LOSS.get());
            }
        }
    }

    /** The outpost or patrol a pillager belongs to: its patrol leader, else roughly where it stands. */
    private static String placeOf(Raider raider) {
        if (raider instanceof PatrollingMonster p && p.hasPatrolTarget()) {
            return "patrol@" + (p.getPatrolTarget().getX() >> 6) + "," + (p.getPatrolTarget().getZ() >> 6);
        }
        return (raider.getBlockX() >> 6) + "," + (raider.getBlockZ() >> 6);
    }

    private static boolean once(ServerPlayer player, String place, long now) {
        String key = player.getUUID() + "|" + place;
        Long last = SEEN.get(key);
        if (last != null && now - last < ChatterConfig.BANNER_SIGHTING_COOLDOWN.get()) return false;
        SEEN.put(key, now);
        if (SEEN.size() > 1024) SEEN.values().removeIf(t -> now - t >= ChatterConfig.BANNER_SIGHTING_COOLDOWN.get());
        return true;
    }

    /** For tests: forget who has been seen. */
    static void forget(ServerPlayer player) {
        SEEN.keySet().removeIf(k -> k.startsWith(player.getUUID().toString()));
    }
}
