/*
 * This file is part of Bisq.
 *
 * bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.core.account.sign;

import bisq.network.p2p.storage.P2PDataStorage;
import bisq.network.p2p.storage.payload.CapabilityRequiringPayload;
import bisq.network.p2p.storage.payload.DateTolerantPayload;
import bisq.network.p2p.storage.payload.LazyProcessedPayload;
import bisq.network.p2p.storage.payload.PersistableNetworkPayload;

import bisq.common.app.Capabilities;
import bisq.common.app.Capability;
import bisq.common.crypto.Hash;
import bisq.common.proto.persistable.PersistableEnvelope;
import bisq.common.util.Utilities;

import io.bisq.generated.protobuffer.PB;

import com.google.protobuf.ByteString;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

// Supports signatures made from EC key (arbitrators) and signature created with DSA key.
@Slf4j
@Value
public class SignedWitness implements LazyProcessedPayload, PersistableNetworkPayload, PersistableEnvelope,
        DateTolerantPayload, CapabilityRequiringPayload {
    private static final long TOLERANCE = TimeUnit.DAYS.toMillis(1);

    private final boolean signedByArbitrator;
    private final byte[] witnessHash;
    private final byte[] signature;
    private final byte[] signerPubKey;
    private final byte[] witnessOwnerPubKey;
    private final long date;
    //TODO should we add trade amount?

    transient private final byte[] hash;

    public SignedWitness(boolean signedByArbitrator,
                         byte[] witnessHash,
                         byte[] signature,
                         byte[] signerPubKey,
                         byte[] witnessOwnerPubKey,
                         long date) {
        this.signedByArbitrator = signedByArbitrator;
        this.witnessHash = witnessHash;
        this.signature = signature;
        this.signerPubKey = signerPubKey;
        this.witnessOwnerPubKey = witnessOwnerPubKey;
        this.date = date;

        byte[] data = Utilities.concatenateByteArrays(witnessHash, signature);
        data = Utilities.concatenateByteArrays(data, signerPubKey);
        // Date is not added to hash to avoid that the same account with same sigs can be stored multiple
        // times if date would differ
        hash = Hash.getSha256Ripemd160hash(data);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public PB.PersistableNetworkPayload toProtoMessage() {
        final PB.SignedWitness.Builder builder = PB.SignedWitness.newBuilder()
                .setSignedByArbitrator(signedByArbitrator)
                .setWitnessHash(ByteString.copyFrom(witnessHash))
                .setSignature(ByteString.copyFrom(signature))
                .setSignerPubKey(ByteString.copyFrom(signerPubKey))
                .setWitnessOwnerPubKey(ByteString.copyFrom(witnessOwnerPubKey))
                .setDate(date);
        return PB.PersistableNetworkPayload.newBuilder().setSignedWitness(builder).build();
    }

    public PB.SignedWitness toProtoSignedWitness() {
        return toProtoMessage().getSignedWitness();
    }

    public static SignedWitness fromProto(PB.SignedWitness proto) {
        return new SignedWitness(proto.getSignedByArbitrator(),
                proto.getWitnessHash().toByteArray(),
                proto.getSignature().toByteArray(),
                proto.getSignerPubKey().toByteArray(),
                proto.getWitnessOwnerPubKey().toByteArray(),
                proto.getDate());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public boolean isDateInTolerance() {
        // We don't allow older or newer then 1 day.
        // Preventing forward dating is also important to protect against a sophisticated attack
        return Math.abs(new Date().getTime() - date) <= TOLERANCE;
    }

    @Override
    public boolean verifyHashSize() {
        return hash.length == 20;
    }

    // Pre 1.0.1 version don't know the new message type and throw an error which leads to disconnecting the peer.
    @Override
    public Capabilities getRequiredCapabilities() {
        return new Capabilities(Capability.SIGNED_ACCOUNT_AGE_WITNESS);
    }

    @Override
    public byte[] getHash() {
        return hash;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public P2PDataStorage.ByteArray getHashAsByteArray() {
        return new P2PDataStorage.ByteArray(hash);
    }

    @Override
    public String toString() {
        return "SignedWitness{" +
                ",\n     signedByArbitrator=" + signedByArbitrator +
                ",\n     witnessHash=" + Utilities.bytesAsHexString(witnessHash) +
                ",\n     signature=" + Utilities.bytesAsHexString(signature) +
                ",\n     signerPubKey=" + Utilities.bytesAsHexString(signerPubKey) +
                ",\n     witnessOwnerPubKey=" + Utilities.bytesAsHexString(witnessOwnerPubKey) +
                ",\n     date=" + date +
                ",\n     hash=" + Utilities.bytesAsHexString(hash) +
                "\n}";
    }
}
