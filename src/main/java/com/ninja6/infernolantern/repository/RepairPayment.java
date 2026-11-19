package com.ninja6.infernolantern.repository;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

import static com.ninja6.infernolantern.repository.RepositoryRecords.*;

/** Exact native slot evidence. Empty remainder has zero bytes. */
public record RepairPayment(UUID contributor, UUID session, int slot, String resource, int items,
                            ResourceBytes before, ResourceBytes remainder, ResourceBytes debit) {
    static final String KIND = "repair-payment";
    static final int RESOURCE_FORMAT = 1;
    private static final int HEADER_BYTES = 2 * Long.BYTES + 5 * Integer.BYTES + Short.BYTES;

    public RepairPayment {
        Objects.requireNonNull(contributor);
        Objects.requireNonNull(session);
        identifier(resource);
        Objects.requireNonNull(before);
        Objects.requireNonNull(remainder);
        Objects.requireNonNull(debit);
        if (items < 1 || slot < 0 || slot >= 36 || before.equals(remainder)
                || before.size() == 0 || debit.size() == 0
                || before.size() + remainder.size() + debit.size() + resource.length() + HEADER_BYTES > MAX_PAYLOAD) {
            throw new IllegalArgumentException("Exact repair payment required");
        }
    }

    Escrow escrow(Intent intent) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeLong(session.getMostSignificantBits());
                out.writeLong(session.getLeastSignificantBits());
                out.writeInt(slot);
                out.writeUTF(resource);
                out.writeInt(items);
                write(out, before);
                write(out, remainder);
                write(out, debit);
            }
            return new Escrow(intent.id(), intent.towerId(), intent.expectedDeploymentRevision(), contributor,
                    RESOURCE_FORMAT, new ResourceBytes(bytes.toByteArray()), PaymentPhase.RESERVED, HpPhase.NOT_APPLIED, 0);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(DataOutputStream out, ResourceBytes resource) throws IOException {
        byte[] bytes = resource.copy();
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
