package dev.villagechatter.api;

import dev.villagechatter.ChatterConfig;
import dev.villagechatter.life.Grime;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.ItemStack;

/**
 * Speech checks for every mod in the pack.
 * <ul>
 *   <li>How a check is shown on a dialogue option: a Skyrim-style tag ([Persuade], [Bribe], [Intimidate]...) colored
 *   by difficulty like Fallout 4 (yellow easy, orange medium, red hard), or, with speechCheckShowsPercent on, the
 *   exact chance like Fallout 3 and New Vegas ([Persuade 45%]). Build the label on the server and send it as the
 *   option's label.</li>
 *   <li>Charisma, Kingdom Come style: what you wear (fine or enchanted gear impresses, heavy armor intimidates), how
 *   clean you are (blood and grime put people off, but blood helps a threat) and being a Hero of the Village. Use
 *   {@link #adjust} to fold it into any check's base chance.</li>
 * </ul>
 */
public final class SpeechCheck {
    private SpeechCheck() {}

    public enum Skill { PERSUADE, BRIBE, INTIMIDATE, THREATEN, BARTER, IMPRESS }

    public static final int EASY = 0xF2D24B, MEDIUM = 0xF29B30, HARD = 0xE0453A;
    /** At or above this chance a check is easy; at or above MEDIUM_AT, medium; below, hard. */
    public static final double EASY_AT = 0.6, MEDIUM_AT = 0.3;

    /** "[Persuade] It was an honest mistake." with the tag in the difficulty color (or the exact chance). */
    public static Component label(Skill skill, double chance, Component text) {
        return tag(skill, chance).append(" ").append(text);
    }

    public static MutableComponent tag(Skill skill, double chance) {
        String key = "villagechatter.check." + skill.name().toLowerCase(java.util.Locale.ROOT);
        double c = Math.max(0, Math.min(1, chance));
        MutableComponent tag = ChatterConfig.SPEECH_CHECK_PERCENT.get()
                ? Component.translatable("villagechatter.check.tag_percent", Component.translatable(key), Math.round(c * 100))
                : Component.translatable("villagechatter.check.tag", Component.translatable(key));
        return tag.withStyle(s -> s.withColor(TextColor.fromRgb(color(c))));
    }

    public static int color(double chance) {
        return chance >= EASY_AT ? EASY : chance >= MEDIUM_AT ? MEDIUM : HARD;
    }

    // ------------------------------------------------------------------ charisma

    /**
     * How impressive you look, roughly -25 (filthy, in rags) to 75 (spotless, in enchanted diamond): gear up to
     * 48, cleanliness -25 to +25 around a neutral middle.
     */
    public static double charisma(ServerPlayer player) {
        double gear = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            gear += finery(player.getItemBySlot(slot));
        }
        double clean = (50 - Grime.grime(player)) / 2;
        return gear * 2 + clean;
    }

    /** How fine one armor piece looks: gold shows off, diamond and netherite are rich, enchantments shimmer. */
    static double finery(ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armor)) return 0;
        var m = armor.getMaterial();
        double v = m == ArmorMaterials.NETHERITE ? 5 : m == ArmorMaterials.DIAMOND || m == ArmorMaterials.GOLD ? 4
                : m == ArmorMaterials.IRON || m == ArmorMaterials.CHAIN ? 2 : 1;
        if (stack.isEnchanted()) v += 1;
        return v;
    }

    /** Heavy armor and fresh blood: 0 to about 0.35. */
    public static double menace(ServerPlayer player) {
        return Math.min(0.25, player.getArmorValue() * 0.0125) + Math.min(0.1, Grime.blood(player) / 300);
    }

    /** A check with villagers (or anyone not a pillager or piglin): see {@link #adjust(Skill, double, ServerPlayer, Factions.Faction)}. */
    public static double adjust(Skill skill, double base, ServerPlayer player) {
        return adjust(skill, base, player, Factions.Faction.VILLAGE);
    }

    /**
     * A check's chance with who you're talking to folded in, as a multiplier (a check that can't work still can't).
     * Each side looks for different things (Factions v2 item 6):
     * <ul>
     *   <li>Villagers: finery and cleanliness persuade (x0.75 filthy and in rags, up to x2 at your finest); armor and
     *   blood intimidate (up to about x1.7). Being a Hero of the Village (+heroCheckBonus) and your standing with the
     *   villages help.</li>
     *   <li>Piglins: only gold impresses them (gold armor and gold in hand, up to x2); a threat works as with anyone.
     *   Their own meter helps.</li>
     *   <li>Pillagers: fine clothes count for half; menace counts for more (up to about x2). A Hero of the Village gets
     *   nowhere with them (x0.5). Their own meter helps.</li>
     * </ul>
     * A beggar's blessing (life.Charity) and the Speech skill (life.Speech) help with everyone. Bribes are about money
     * and nothing else.
     */
    public static double adjust(Skill skill, double base, ServerPlayer player, Factions.Faction audience) {
        if (skill == Skill.BRIBE) return Math.max(0, Math.min(1, base));
        double c = switch (skill) {
            case PERSUADE, IMPRESS, BARTER -> base * (1 + appeal(player, audience) / 100);
            default -> base * (1 + menace(player) * (audience == Factions.Faction.PILLAGER ? 3 : 2));
        };
        return Math.max(0, Math.min(1, c * help(player, audience)));
    }

    /** How appealing you look to this side, like charisma: villagers judge finery, piglins gold, pillagers half finery. */
    static double appeal(ServerPlayer player, Factions.Faction audience) {
        return switch (audience) {
            case VILLAGE -> charisma(player);
            case PILLAGER -> charisma(player) / 2;
            case PIGLIN -> goldShine(player);
        };
    }

    /** Gold on show, 0 to 100: 20 per gold armor piece, 20 for gold in hand. */
    public static double goldShine(ServerPlayer player) {
        double shine = 0;
        for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            if (player.getItemBySlot(slot).getItem() instanceof ArmorItem armor && armor.getMaterial() == ArmorMaterials.GOLD) shine += 20;
        }
        if (player.getMainHandItem().is(net.minecraft.tags.ItemTags.PIGLIN_LOVED) || player.getOffhandItem().is(net.minecraft.tags.ItemTags.PIGLIN_LOVED)) shine += 20;
        return shine;
    }

    /**
     * What helps every check but bribes: the Speech skill and a beggar's blessing with everyone; Hero of the Village
     * with villagers (and against you with pillagers); your standing with the side you're talking to (x0.75 to x1.25).
     */
    static double help(ServerPlayer player, Factions.Faction audience) {
        double h = dev.villagechatter.life.Speech.checkFactor(player);
        if (dev.villagechatter.life.Charity.blessed(player)) h *= 1 + ChatterConfig.CHARITY_BLESSING.get();
        boolean hero = player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE);
        if (hero && audience == Factions.Faction.VILLAGE) h *= 1 + ChatterConfig.HERO_CHECK_BONUS.get();
        if (hero && audience == Factions.Faction.PILLAGER) h *= 0.5;
        if (dev.villagechatter.reputation.faction.FactionBook.enabled()) {
            double standing = switch (audience) {
                case VILLAGE -> Factions.village(player);
                case PILLAGER -> dev.villagechatter.reputation.faction.FactionBook.pillagerSeen(player);
                case PIGLIN -> Factions.piglin(player);
            };
            h *= 1 + standing / 400;
        }
        return h;
    }

    /** Impressing a villager: charisma, then the usual help; 5% in rags and dirt, at most 80%. */
    public static double impress(ServerPlayer player) {
        return impress(player, Factions.Faction.VILLAGE);
    }

    /** Impressing someone on this side: what they find appealing, then the usual help; 5% to 80%. */
    public static double impress(ServerPlayer player, Factions.Faction audience) {
        return Math.max(0.05, Math.min(0.8, appeal(player, audience) / 125 * help(player, audience)));
    }
}
