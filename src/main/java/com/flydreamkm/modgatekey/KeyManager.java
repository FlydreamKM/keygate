package com.flydreamkm.modgatekey;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * ModGateKey 密钥管理器
 *
 * 职责：
 *  - 服务端：首次启动在指定文件夹生成 Ed25519 密钥对（公钥+私钥）
 *  - 客户端：只创建同名文件夹，绝不生成密钥，私钥靠人工分发放入
 *  - 提供签名/验签能力
 *
 * 注意：本类不注册任何指令，密钥不存在任何经指令/控制台读取的途径，
 *       唯一获取方式是直接读取服务器上的密钥文件。
 */
public final class KeyManager {

    /** 密钥文件夹名（服务端运行目录 / 客户端游戏目录下的同名文件夹） */
    public static final String KEY_DIR_NAME = "modgatekey";
    public static final String PRIVATE_KEY_FILE = "private_key.bin";
    public static final String PUBLIC_KEY_FILE = "public_key.bin";

    private KeyManager() {}

    /**
     * 服务端初始化：确保密钥文件夹存在，且内含完整密钥对（缺则生成）。
     * 仅在 dedicated server 上调用。
     */
    public static void ensureServerKeyPair(Path gameDir) throws IOException {
        Path dir = gameDir.resolve(KEY_DIR_NAME);
        Files.createDirectories(dir);
        Path priv = dir.resolve(PRIVATE_KEY_FILE);
        Path pub = dir.resolve(PUBLIC_KEY_FILE);
        if (Files.exists(priv) && Files.exists(pub)) {
            ModGateKey.LOGGER.info("[ModGateKey] 已加载既有密钥对: {}", dir);
            return;
        }
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair kp = gen.generateKeyPair();
            Files.write(priv, kp.getPrivate().getEncoded());   // PKCS#8
            Files.write(pub, kp.getPublic().getEncoded());     // X.509
            // 尽力收紧私钥权限（POSIX 系统生效，Windows 静默忽略）
            try {
                priv.toFile().setReadable(false, false);
                priv.toFile().setReadable(true, true);
            } catch (Exception ignored) {}
            ModGateKey.LOGGER.info("[ModGateKey] 首次启动，已生成新密钥对: {}", dir);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 Ed25519", e);
        }
    }

    /**
     * 客户端初始化：只创建同名文件夹，不生成任何密钥。
     * 私钥由服务器管理员私下分发，玩家手动放入此文件夹。
     */
    public static void ensureClientFolder(Path gameDir) throws IOException {
        Path dir = gameDir.resolve(KEY_DIR_NAME);
        Files.createDirectories(dir);
        ModGateKey.LOGGER.info("[ModGateKey] 客户端密钥文件夹: {} (请将服务器分发的 {} 放入此目录)", dir, PRIVATE_KEY_FILE);
    }

    /** 读取服务端公钥（验签用） */
    public static PublicKey loadServerPublicKey(Path gameDir) {
        try {
            byte[] raw = Files.readAllBytes(gameDir.resolve(KEY_DIR_NAME).resolve(PUBLIC_KEY_FILE));
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(raw));
        } catch (Exception e) {
            ModGateKey.LOGGER.error("[ModGateKey] 无法读取服务端公钥", e);
            return null;
        }
    }

    /** 读取客户端私钥（签名用），文件不存在返回 null */
    public static PrivateKey loadClientPrivateKey(Path gameDir) {
        Path priv = gameDir.resolve(KEY_DIR_NAME).resolve(PRIVATE_KEY_FILE);
        if (!Files.exists(priv)) {
            return null;
        }
        try {
            byte[] raw = Files.readAllBytes(priv);
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(raw));
        } catch (Exception e) {
            ModGateKey.LOGGER.error("[ModGateKey] 私钥文件损坏或格式错误: {}", priv, e);
            return null;
        }
    }

    /** 用私钥对挑战 nonce 签名 */
    public static byte[] sign(PrivateKey key, byte[] nonce) throws Exception {
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(nonce);
        return sig.sign();
    }

    /** 用公钥验证签名 */
    public static boolean verify(PublicKey key, byte[] nonce, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(nonce);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }
}
