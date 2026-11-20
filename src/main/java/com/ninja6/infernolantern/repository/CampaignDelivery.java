package com.ninja6.infernolantern.repository;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Durable provenance for an unavailable item in one exact recipient slot. */
public record CampaignDelivery(UUID award, UUID tower, UUID operation, UUID recipient, UUID session,
                               int slot, ResourceBytes staged, ResourceBytes active) {
    public static final String KIND = "campaign-award-delivery";
    private static final String SOURCE = "admin-campaign:";

    public CampaignDelivery {
        Objects.requireNonNull(award);
        Objects.requireNonNull(tower);
        Objects.requireNonNull(operation);
        Objects.requireNonNull(recipient);
        Objects.requireNonNull(session);
        if (slot < 0 || slot >= 36 || staged.size() == 0 || active.size() == 0 || staged.equals(active)
                || (long) staged.size() + active.size() > MAX_PAYLOAD / 2)
            throw new IllegalArgumentException("Campaign slot evidence");
    }

    public static String source(String campaign) {
        identifier(campaign);
        return SOURCE + campaign;
    }

    static boolean campaignSource(String source) { return source.startsWith(SOURCE); }

    static boolean matches(Award award) {
        return campaignSource(award.sourceKey()) && award.id().equals(awardId(award.sourceKey().substring(SOURCE.length()), award.recipient()));
    }

    public static UUID awardId(String campaign, UUID recipient) {
        return id(source(campaign) + "/" + Objects.requireNonNull(recipient));
    }

    static UUID towerId(UUID award, long index) { return id("admin-campaign-tower:" + award + "/" + index); }

    private static UUID id(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }

    Document encode() {
        var encoder = Base64.getEncoder();
        return new Document(1, String.join("|", "campaign-delivery-v1", award.toString(), tower.toString(),
                operation.toString(), recipient.toString(), session.toString(), Integer.toString(slot),
                encoder.encodeToString(staged.copy()), encoder.encodeToString(active.copy())));
    }

    static CampaignDelivery decode(Document document) {
        var part = document.content().split("\\|", -1);
        if (document.version() != 1 || part.length != 9 || !part[0].equals("campaign-delivery-v1"))
            throw new IllegalArgumentException("Campaign delivery document");
        var decoder = Base64.getDecoder();
        CampaignDelivery result = new CampaignDelivery(UUID.fromString(part[1]), UUID.fromString(part[2]),
                UUID.fromString(part[3]), UUID.fromString(part[4]), UUID.fromString(part[5]), Integer.parseInt(part[6]),
                new ResourceBytes(decoder.decode(part[7])), new ResourceBytes(decoder.decode(part[8])));
        if (!result.encode().equals(document)) throw new IllegalArgumentException("Noncanonical campaign delivery");
        return result;
    }
}
