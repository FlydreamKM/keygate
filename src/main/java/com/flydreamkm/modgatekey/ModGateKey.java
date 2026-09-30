package com.flydreamkm.modgatekey;

import com.flydreamkm.modgatekey.payload.AuthChallengePayload;
import com.flydreamkm.modgatekey.payload.AuthResponsePayload;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModGateKey 服务端入口 —— 私有分发服务器密钥验证器
 *
 * 工作流程（dedicated server 才启用，单人/集成服务器自动跳过）：
 *  1. 玩家连接建立（PLAY 阶段进入）→ 服务端下发 32 字节随机挑战 nonce
 *  2. 客户端用私钥对 nonce 签名并回传
 *  3. 服务端用公钥验签：
 *     - 验签失败 → 立刻踢出（无任何延迟/宽限）
 *     - 超时未响应（没装 MOD / 没放私钥）→ 到点立刻踢出
 *  4. 密钥只存放在服务器 modgatekey/ 文件夹，本 MOD 不注册任何指令，
 *     不存在经指令或控制台读取密钥的途径。
 */
public class ModGateKey implements ModInitializer {

    public static final String MOD_ID = "modgatekey";
    public static final Logger LOGGER = LoggerFactory.getLogger("ModGateKey");

    /** 等待客户端响应的最长毫秒数（超时视为验证失败，立即踢出） */
    private static final long AUTH_TIMEOUT_MS = 3000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 待验证连接：玩家 UUID -> 挑战信息 */
    private static final Map<UUID, PendingAuth> PENDING = new ConcurrentHashMap<>();

    private record PendingAuth(byte[] nonce, long deadlineMillis) {}

    private static PublicKey serverPublicKey;

    @Override
    public void onInitialize() {
        // 注册双向 payload（两端都需要注册类型）
        PayloadTypeRegistry.clientboundPlay().register(AuthChallengePayload.ID, AuthChallengePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AuthResponsePayload.ID, AuthResponsePayload.CODEC);

        // 仅专用服务器启用验证逻辑
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            LOGGER.info("[ModGateKey] 非专用服务器环境，验证器不启用");
            return;
        }

        Path gameDir = FabricLoader.getInstance().getGameDir();

        // 首次启动生成密钥对（仅落在指定文件夹，不提供任何指令/控制台读取途径）
        try {
            KeyManager.ensureServerKeyPair(gameDir);
        } catch (IOException e) {
            LOGGER.error("[ModGateKey] 密钥对初始化失败，验证器将无法工作！", e);
            return;
        }
        serverPublicKey = KeyManager.loadServerPublicKey(gameDir);
        if (serverPublicKey == null) {
            LOGGER.error("[ModGateKey] 公钥加载失败，验证器将无法工作！");
            return;
        }

        // 玩家进入 PLAY 阶段 → 下发挑战
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            byte[] nonce = new byte[32];
            RANDOM.nextBytes(nonce);
            UUID uuid = handler.getPlayer().getUUID();
            PENDING.put(uuid, new PendingAuth(nonce, System.currentTimeMillis() + AUTH_TIMEOUT_MS));
            ServerPlayNetworking.send(handler.getPlayer(), new AuthChallengePayload(nonce));
            LOGGER.info("[ModGateKey] 已向 {} 下发验证挑战", handler.getPlayer().getName().getString());
        });

        // 收到客户端签名 → 立即验签，不符直接踢
        ServerPlayNetworking.registerGlobalReceiver(AuthResponsePayload.ID, (payload, context) -> {
            UUID uuid = context.player().getUUID();
            context.server().execute(() -> {
                PendingAuth pending = PENDING.remove(uuid);
                if (pending == null) {
                    // 没有待验证记录却收到响应（重放/异常）→ 踢
                    context.player().connection.disconnect(
                            Component.literal("[ModGateKey] 验证状态异常，连接被拒绝"));
                    return;
                }
                boolean ok = KeyManager.verify(serverPublicKey, pending.nonce(), payload.signature());
                if (!ok) {
                    LOGGER.warn("[ModGateKey] {} 验证失败（签名不符），已踢出",
                            context.player().getName().getString());
                    context.player().connection.disconnect(
                            Component.literal("[ModGateKey] 密钥验证失败，连接被拒绝"));
                } else {
                    LOGGER.info("[ModGateKey] {} 验证通过",
                            context.player().getName().getString());
                }
            });
        });

        // 掉线清理
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                PENDING.remove(handler.getPlayer().getUUID()));

        // 超时巡查：没装 MOD / 没放私钥的连接，到点立刻踢
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<UUID, PendingAuth>> it = PENDING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, PendingAuth> entry = it.next();
                if (now >= entry.getValue().deadlineMillis()) {
                    it.remove();
                    var player = server.getPlayerList().getPlayer(entry.getKey());
                    if (player != null) {
                        LOGGER.warn("[ModGateKey] {} 验证超时（未安装 MOD 或未放入私钥），已踢出",
                                player.getName().getString());
                        player.connection.disconnect(
                                Component.literal("[ModGateKey] 验证超时：未安装 ModGateKey 或未放入私钥文件"));
                    }
                }
            }
        });

        LOGGER.info("[ModGateKey] 验证器已启用 (超时 {}ms)", AUTH_TIMEOUT_MS);
    }
}
