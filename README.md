# ModGateKey — 私有分发服务器密钥验证器

> 基于 ModGate（GPL-3.0）思路爆改：把「MOD 列表哈希校验」替换为「Ed25519 密钥对挑战-响应验证」，
> 用于**只允许持有私钥的玩家进入服务器**（整合包/服务器私有分发场景）。
>
> 适用版本：Minecraft **26.2** · Fabric Loader ≥ 0.19.5 · Java ≥ 25

## 工作原理

```
玩家连接（进入 PLAY 阶段）
        │
        ▼
服务端下发 32 字节随机挑战 nonce ──────────► 客户端
        │                                    │ 用 modgatekey/private_key.bin 签名
        │◄────────── 回传 Ed25519 签名 ──────┘
        ▼
服务端用本地公钥验签
   ├─ 通过 → 放行，日志记录
   ├─ 签名不符 → 【立即踢出，零延迟】
   └─ 超时未响应（默认 3000ms，没装 MOD / 没放私钥）→ 【立即踢出】
```

## 密钥设计（按需求规格实现）

| 项目 | 服务端 | 客户端 |
|---|---|---|
| 文件夹 | 运行目录下 `modgatekey/`（首启自动创建） | 游戏目录下 `modgatekey/`（首启自动创建同名文件夹） |
| 密钥对生成 | **首次启动自动生成** Ed25519 密钥对 | **绝不生成** |
| 私钥来源 | 本地生成 `private_key.bin` | **管理员私下分发，玩家手动放入** |
| 指令/控制台读取 | ❌ MOD 不注册任何指令，密钥无指令/控制台获取途径 | ❌ 同左 |
| 验证失败处理 | 立即断开（无宽限期、无延迟） | — |

文件明细：

```
modgatekey/
├── private_key.bin   # Ed25519 私钥（PKCS#8，服务端会尽力收紧为仅属主可读）
└── public_key.bin    # Ed25519 公钥（X.509，仅服务端持有）
```

## 分发流程

1. 服务端装好 MOD 启动一次 → 自动生成 `modgatekey/` 密钥对
2. 服务端把 `modgatekey/private_key.bin` **私下**发给受信任玩家
3. 玩家把 `private_key.bin` 放进自己游戏目录的 `modgatekey/` 文件夹
4. 玩家进服自动完成验证；没放私钥/私钥不对 → 进服瞬间被踢

> ⚠️ 注意：任何拿到 `private_key.bin` 的人都能进服，请只通过可信渠道分发。
> 私钥泄露时：删除服务端 `modgatekey/` 后重启生成新密钥对，旧私钥即刻全部失效。

## 服务端 / 客户端安装

- 两端都需要：Fabric Loader ≥ 0.19.5 + Fabric API（26.2 版）+ 本 MOD jar
- 单人游戏/集成服务器环境验证器自动跳过，不会把自己踢出自己的世界

## 构建

本地构建（需要 JDK 25）：

```bash
./gradlew build
# 产物: build/libs/modgatekey-<version>.jar
```

CI 构建：每次 push 自动触发 GitHub Actions，产物在 Actions 运行页的 **Artifacts** 里下载。

## 调参

`ModGateKey.java` 中的常量：

```java
private static final long AUTH_TIMEOUT_MS = 3000;  // 挑战响应超时
```

## 与原版 ModGate 的区别

| | ModGate | ModGateKey |
|---|---|---|
| 验证内容 | MOD 列表 SHA-256 | Ed25519 私钥签名 |
| 未安装 MOD 的玩家 | 10 秒宽限后踢出 | 3 秒超时即踢 |
| 验证失败 | 延迟踢出 | **立即踢出** |
| 管理指令 | `/modgate` 系列 | **无（刻意移除，防密钥泄露）** |

## License

GPL-3.0-or-later（沿袭 ModGate 的许可证）
