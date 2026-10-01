package com.flydreamkm.keygate.payload;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 -> 服务端：认证响应（私钥对 nonce 的 Ed25519 签名）
 */
public record AuthResponsePayload(byte[] signature) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AuthResponsePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("keygate", "auth_response"));

    public static final StreamCodec<FriendlyByteBuf, AuthResponsePayload> CODEC = StreamCodec.of(
            (buf, value) -> buf.writeByteArray(value.signature),
            buf -> new AuthResponsePayload(buf.readByteArray(128))
    );

    @Override
    public CustomPacketPayload.Type<AuthResponsePayload> type() {
        return ID;
    }
}
