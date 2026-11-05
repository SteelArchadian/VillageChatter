package dev.villagechatter.quests.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Stored on a villager. {@code offer} is the id of the quest they want help with ("" = none) and {@code count}
 * how many items an errand offer asks for (fixed when the offer appears, so the story can say it);
 * {@code nextOfferTime} is the game time before which they won't come up with a new request;
 * {@code lastQuest} is the last quest someone took from them, so they don't ask for the same thing twice in a row.
 */
public record GiverData(String offer, long nextOfferTime, int count, String lastQuest) {
    public static final GiverData NONE = new GiverData("", 0L, 0, "");

    public static final Codec<GiverData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("offer", "").forGetter(GiverData::offer),
            Codec.LONG.optionalFieldOf("next_offer_time", 0L).forGetter(GiverData::nextOfferTime),
            Codec.INT.optionalFieldOf("count", 0).forGetter(GiverData::count),
            Codec.STRING.optionalFieldOf("last_quest", "").forGetter(GiverData::lastQuest)
    ).apply(i, GiverData::new));

    public boolean hasOffer() {
        return !offer.isEmpty();
    }

    public GiverData withOffer(String offer, int count) {
        return new GiverData(offer, nextOfferTime, count, lastQuest);
    }

    /** Offer used up or withdrawn; nothing new before {@code nextOfferTime}. */
    public GiverData cleared(long nextOfferTime, String lastQuest) {
        return new GiverData("", nextOfferTime, 0, lastQuest);
    }
}
