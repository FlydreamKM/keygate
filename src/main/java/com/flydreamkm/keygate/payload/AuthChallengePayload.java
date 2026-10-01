package com.flydreamkm.keygate.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 -> 客户端：认证挑战（随机 nonce）
 */
public record AuthChallengePayload(byte[] nonce) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AuthChallengePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("keygate", "auth_challenge"));

    public static final StreamCodec<FriendlyByteBuf, AuthChallengePayload> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeByteArray(value.nonce),
            buf -> new AuthChallengePayload(buf.readByteArray(64))
    );

    @Override
    public CustomPacketPayload.Type<AuthChallengePayload> type() {
        return ID;
    }
}
