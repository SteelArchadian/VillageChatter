package dev.villagechatter.life.job;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The jobs a player can hand out, shared by both sides: every registered profession (modded ones too) with a
 * workstation, its job-block icon taken from the profession's POI block states, and its display name.
 */
public final class Jobs {
    private Jobs() {}

    private static List<VillagerProfession> assignable;

    /** Every profession except "none" and "nitwit" that has at least one workstation block. Registries are frozen by now. */
    public static List<VillagerProfession> assignable() {
        if (assignable == null) {
            List<VillagerProfession> out = new ArrayList<>();
            for (VillagerProfession p : BuiltInRegistries.VILLAGER_PROFESSION) {
                if (p != VillagerProfession.NONE && p != VillagerProfession.NITWIT && !poiTypes(p).isEmpty()) out.add(p);
            }
            assignable = List.copyOf(out);
        }
        return assignable;
    }

    public static List<Holder<PoiType>> poiTypes(VillagerProfession profession) {
        List<Holder<PoiType>> out = new ArrayList<>();
        BuiltInRegistries.POINT_OF_INTEREST_TYPE.holders().forEach(h -> {
            if (profession.heldJobSite().test(h)) out.add(h);
        });
        return out;
    }

    /** The profession whose workstation this POI is, or null for beds, bells and the like. */
    @Nullable
    public static VillagerProfession forPoi(Holder<PoiType> poi) {
        for (VillagerProfession p : assignable()) if (p.heldJobSite().test(poi)) return p;
        return null;
    }

    /** The job block as an item (a lectern for a librarian), or an emerald if it has no item form. */
    public static ItemStack icon(VillagerProfession profession) {
        for (Holder<PoiType> poi : poiTypes(profession)) {
            for (BlockState state : poi.value().matchingStates()) {
                if (state.getBlock().asItem() != Items.AIR) return new ItemStack(state.getBlock());
            }
        }
        return new ItemStack(Items.EMERALD);
    }

    /** The job block's name, e.g. "Lectern". */
    public static Component blockName(VillagerProfession profession) {
        ItemStack icon = icon(profession);
        return icon.is(Items.EMERALD) ? name(profession) : icon.getHoverName();
    }

    /** "Librarian": vanilla's own villager-name key, which modded professions also fill in. */
    public static Component name(VillagerProfession profession) {
        return Component.translatable(EntityType.VILLAGER.getDescriptionId() + "." + id(profession).getPath());
    }

    public static ResourceLocation id(VillagerProfession profession) {
        return BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession);
    }

    @Nullable
    public static VillagerProfession byId(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? null : BuiltInRegistries.VILLAGER_PROFESSION.getOptional(key).orElse(null);
    }

    /** Kids and nitwits can't take a job at all. */
    public static boolean canWork(Villager villager) {
        return !villager.isBaby() && villager.getVillagerData().getProfession() != VillagerProfession.NITWIT;
    }

    /** Vanilla locks the job of a villager who has traded (any experience or level above novice). */
    public static boolean hasTraded(Villager villager) {
        return villager.getVillagerXp() > 0 || villager.getVillagerData().getLevel() > 1;
    }
}
