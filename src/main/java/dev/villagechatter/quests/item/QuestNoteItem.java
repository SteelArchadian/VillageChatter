package dev.villagechatter.quests.item;

import java.util.List;

import dev.villagechatter.quests.QuestRegistries;
import dev.villagechatter.quests.quest.NoteData;
import dev.villagechatter.quests.quest.QuestText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** The villager's note: who asked, what for, where, and how long you have. Use it to read it in chat. */
public class QuestNoteItem extends Item {
    public QuestNoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        NoteData note = stack.get(QuestRegistries.NOTE.get());
        return note == null ? super.getName(stack) : Component.translatable("item.villagequests.quest_note.named", note.title());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        NoteData note = stack.get(QuestRegistries.NOTE.get());
        if (note == null) {
            tooltip.add(Component.translatable("villagequests.note.blank").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.addAll(lines(note, context.level()));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        NoteData note = stack.get(QuestRegistries.NOTE.get());
        if (note == null) return InteractionResultHolder.pass(stack);
        if (!level.isClientSide()) {
            player.sendSystemMessage(note.title().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
            lines(note, level).forEach(player::sendSystemMessage);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    private static List<Component> lines(NoteData note, Level level) {
        Component from = Component.translatable("villagequests.note.from", note.giver()).withStyle(ChatFormatting.GRAY);
        Component where = note.kind().startsWith("village")
                ? Component.translatable("villagequests.note.village").withStyle(ChatFormatting.GRAY)
                : "build".equals(note.kind())
                ? Component.translatable("villagequests.note.build", note.giver(), QuestText.coords(note.target())).withStyle(ChatFormatting.GRAY)
                : "job".equals(note.kind())
                ? Component.translatable("villagequests.note.job", note.giver(), QuestText.coords(note.target())).withStyle(ChatFormatting.GRAY)
                : "errand".equals(note.kind())
                ? Component.translatable("villagequests.note.bring_to", note.giver(), QuestText.coords(note.target())).withStyle(ChatFormatting.GRAY)
                : Component.translatable("villagequests.note.where", QuestText.kindName(note.kind()), QuestText.roughCoords(note.target()))
                        .withStyle(ChatFormatting.GRAY);
        Component due;
        if (level == null) {
            due = Component.empty();
        } else {
            long left = note.deadline() - level.getGameTime();
            due = left > 0
                    ? Component.translatable("villagequests.note.due", QuestText.duration(left)).withStyle(ChatFormatting.YELLOW)
                    : Component.translatable("villagequests.note.overdue").withStyle(ChatFormatting.RED);
        }
        Component hint = Component.translatable("villagequests.note.hint", note.giver()).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
        return List.of(from, where, due, hint);
    }
}
