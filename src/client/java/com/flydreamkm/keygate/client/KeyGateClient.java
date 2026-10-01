package com.flydreamkm.keygate.client;

import com.flydreamkm.keygate.KeyManager;
import com.flydreamkm.keygate.KeyGate;
import com.flydreamkm.keygate.payload.AuthChallengePayload;
import com.flydreamkm.keygate.payload.AuthResponsePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PrivateKey;

/**
 * KeyGate 客户端入口
 *
 * 行为：
 *  - 启动时只创建 keygate/ 同名文件夹，绝不生成密钥对
 *  - 私钥文件 private_key.bin 由服务器管理员私下分发，玩家手动放入
 *  - 收到服务端挑战 → 读私钥签名 → 回传；无私钥则回传空签名（必定失败被踢）
 */
public class KeyGateClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        try {
            KeyManager.ensureClientFolder(gameDir);
        } catch (IOException e) {
            KeyGate.LOGGER.error("[KeyGate] 客户端文件夹创建失败", e);
        }

        ClientPlayNetworking.registerGlobalReceiver(AuthChallengePayload.ID, (payload, context) -> {
            byte[] signature = new byte[0];
            PrivateKey key = KeyManager.loadClientPrivateKey(gameDir);
            if (key != null) {
                try {
                    signature = KeyManager.sign(key, payload.nonce());
                } catch (Exception e) {
                    KeyGate.LOGGER.error("[KeyGate] 签名失败", e);
                }
            } else {
                KeyGate.LOGGER.warn("[KeyGate] 未找到私钥文件，本次连接将被服务器拒绝");
            }
            ClientPlayNetworking.send(new AuthResponsePayload(signature));
        });

        KeyGate.LOGGER.info("[KeyGate] 客户端已就绪");
    }
}
