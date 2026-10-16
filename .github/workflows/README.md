# PenAi CI（.github/workflows）

PenAi **本地不构建、不产安装包**（工程红线）。所有编译、测试、签名发布均由
GitHub Actions 在云端 runner 完成。本目录说明两个 workflow 的用途、发布链路
所需的 Secrets，以及如何在自己的机器上复现云端构建。

## 文件清单

| 文件 | 用途 | 触发条件 |
| --- | --- | --- |
| `build.yml` | 云端构建：Debug APK（full + fdroid 双风味）+ JVM 单元测试 | push / pull_request 到 `main` 与 `feature/**` |
| `release.yml` | 云端发布：签名 Full Release APK → 附带到 GitHub Release | push tag `v*`（如 `v1.0.0`） |

root 目录的 `NOTICE` 为第三方许可证归属清单（Phase 7a 从基座 ZorvAI 完整继承
并补齐缺口），与 CI 无直接耦合，一并由本阶段维护。

## build.yml 关键设计

- **运行环境**：`ubuntu-24.04`；JDK 17（`actions/setup-java@v4`，temurin）。
- **Android SDK 组件**（`android-actions/setup-android-sdk@v3`，与工程构建约束
  逐项对齐——规划书 §2.1 / `app/build.gradle.kts`）：
  - `platforms;android-36`（compileSdk 36）
  - `build-tools;36.0.0`
  - `ndk;27.0.12077973`（NDK r27——**必装**：full 风味与 `:mnn` / `:llama` /
    `:terminal-core` 均含 native 构建，缺 NDK 连配置期都过不去）
  - `cmake;3.22.1`（`:mnn` / `:llama` 的 `externalNativeBuild` 显式锁定版本；
    `:app` / `:aidl-aci-browser` 未锁版本，AGP 默认解析同为 3.22.1）
- **构建任务**：`./gradlew :app:assembleDebug`（full + fdroid 两风味）与
  `./gradlew :app:testDebugUnitTest`（与规划书 §9 的回归口径一致：JVM 单测）。
- **缓存策略**（双层兜底）：
  - `actions/cache@v4`：`~/.gradle/caches` + `~/.gradle/wrapper`，key 取依赖
    指纹（所有 `*.gradle.kts` / `gradle-wrapper.properties` / `libs.versions.toml`），
    冷启动全量快照；
  - `gradle/actions/setup-gradle@v4`：按内容寻址的依赖解析缓存 + 构建缓存。
- **镜像策略**：不改写任何仓库地址。Gradle 依赖 / 插件走
  `settings.gradle.kts` 里的阿里云镜像优先（`maven.aliyun.com` 的
  gradle-plugin / public / google 在前，`google()` / `mavenCentral()` /
  `gradlePluginPortal()` 兜底，`FAIL_ON_PROJECT_REPOS` 模式）；Gradle 发行包走
  `gradle-wrapper.properties` 的阿里云 macports 镜像。保留该策略是为了与国内
  本地环境口径一致（本地直连 Maven Central 会超时），GitHub runner 上国内外
  网络均可解析。
- **产物**：artifact `penai-debug-apk`（两个风味的 debug APK）与
  `penai-unit-test-reports`（`app/build/reports/tests` + `app/build/test-results`），
  保留 30 天。
- **超时预估**：`timeout-minutes: 90`。冷缓存（首次 / 依赖指纹变化）含
  MNN + llama.cpp + app 三处 NDK 原生编译约 30~50 分钟；命中依赖缓存约
  10~20 分钟。同一分支新提交会自动取消旧任务（concurrency group）。

## release.yml 关键设计

- **触发**：push tag `v*`。产物附带到同名 GitHub Release（tag 尚未预创建
  Release 时自动创建）。
- **签名链路**（与基座 `AGENTS.md` 红线一致，未碰任何密钥材料）：
  1. Secrets 注入凭证环境变量 → 变量名与基座 `scripts/sign.sh` /
     `scripts/lib/common.sh` **完全对齐**：`ZORV_KEYSTORE` / `ZORV_KEY_ALIAS` /
     `ZORV_STORE_PASS` / `ZORV_KEY_PASS`。common.sh 的解析优先级为
     「调用方环境变量（CI 注入）> keystore.properties > `~/.zorvai-signing/env.sh`」，
     即 CI 注入优先生效；
  2. 解码密钥库到 runner 临时目录（`chmod 600`，不落项目目录、不进 git），
     并按 `app/build.gradle.kts` `signingConfigs` 的配置期要求生成
     `keystore.properties`（已被 `.gitignore` 忽略）；
  3. `./gradlew :app:assembleFullRelease`——Gradle 直签，与基座
     v1.0.86+ 已发布包同链路（fdroid 风味无 release 变体，宿主只有 full）；
  4. `scripts/sign.sh <apk路径>`：幂等——已同签名则跳过，并做最终
     「APK 证书指纹 == 密钥库证书指纹」一致性校验；万一未签则自动
     zipalign + apksigner 补签到 `outputs/apk/full/release/signed/`；
  5. `sha256sum` 生成 `SHA256SUMS.txt`，随 APK 一起上传 artifact 并
     `gh release upload --clobber` 附带到 Release。
- **超时预估**：`timeout-minutes: 120`（release 只构建 full 风味，仍含三处
  NDK 编译）。
- **权限**：作业级 `contents: write`（gh release 创建/上传必需）。

### 需要的 Secrets 清单（只列名，不列值）

在仓库 **Settings → Secrets and variables → Actions → Secrets** 配置：

| Secret 名 | 用途 |
| --- | --- |
| `ZORV_KEYSTORE_BASE64` | 签名密钥库（`.jks`，PKCS12）文件内容的 base64——`base64 -w0 penai-release.jks` 生成 |
| `ZORV_KEY_ALIAS` | keystore 内 key 的别名（基座默认 `zorvai`） |
| `ZORV_STORE_PASS` | keystore store 口令 |
| `ZORV_KEY_PASS` | keystore key 口令 |

说明：密钥库**文件路径**不是 Secret——workflow 将解码后的文件写到
`$RUNNER_TEMP/penai-signing/penai-release.jks` 并通过环境变量 `ZORV_KEYSTORE`
注入，与基座 `scripts/sign.sh` 的凭证变量名对齐。

注意：

- **workflow 内不写死任何密钥/口令**，全部经上述 Secrets 注入；release.yml
  第一步会预检 Secrets 是否配齐，缺任何一个直接 fail fast。
- 若 Phase 0 签名审计结论是换新签名密钥（例如 PenAi 专属 `penai-release.jks`），
  只需替换 Secret 值、变量名不变；换钥后请把新证书 SHA-256 指纹（允许输出）
  登记到 `AGENTS.md` 的「本仓库既有事实」表——宿主与插件同签名是信任来源，
  换钥匙会导致老用户无法覆盖安装。
- release.yml **不需要** build.yml 的 CI 占位 `keystore.properties` 机制——
  它直接写入真实凭证（同样被 `.gitignore` 忽略，不进 git）。

## 本地复现云端构建（用户环境）

前置：JDK 17（temurin 推荐）、Android SDK 的下列组件。用命令行工具装：

```bash
sdkmanager "cmdline-tools;latest" \
           "platforms;android-36" \
           "build-tools;36.0.0" \
           "ndk;27.0.12077973" \
           "cmake;3.22.1"
```

环境变量：`ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）指向 SDK 目录。
镜像无需另配——`settings.gradle.kts`（阿里云依赖镜像）与
`gradle-wrapper.properties`（阿里云发行包镜像）已内置；若本地在海外且阿里云
不可达，可临时在 `settings.gradle.kts` 上调换镜像顺序（提交前改回）。

```bash
# 1) 云端 build.yml 的等价物
./gradlew :app:assembleDebug :app:testDebugUnitTest
# 产物：app/build/outputs/apk/{full,fdroid}/debug/*.apk
# 报告：app/build/reports/tests/、app/build/test-results/

# 2) 云端 release.yml 的等价物（签名走仓库脚本，凭证二选一）
#    a) 环境变量方式（与 CI 一致，推荐）：
export ZORV_KEYSTORE=/path/to/penai-release.jks
export ZORV_KEY_ALIAS=<别名>
export ZORV_STORE_PASS=<store口令>
export ZORV_KEY_PASS=<key口令>
./gradlew :app:assembleFullRelease
scripts/sign.sh "$(find app/build/outputs/apk/full/release -maxdepth 1 -name '*.apk' | head -n1)"

#    b) keystore.properties 方式（本仓库默认来源，文件已被 .gitignore 忽略）：
#       在仓库根写 keystore.properties（storeFile / storePassword / keyAlias /
#       keyPassword 四行），然后 ./gradlew :app:assembleFullRelease
```

### 关于「占位 keystore.properties」：为什么本地/CI 都必须有

`app/build.gradle.kts` 的 `signingConfigs` 在**配置期**强制解析
`storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四个键
（`keystoreProperties["storeFile"] as String`，文件缺失时是 NPE）。也就是说
`keystore.properties` 不存在时，**连 `assembleDebug` 都会配置失败**，不只是
release。因此：

- CI（build.yml）：在 runner 上现场生成一份「随机口令 + 不存在的
  `storeFile` 路径」的占位文件——debug 变体不使用 release 签名配置，配置期
  能过即可；该文件不进 git、不含任何真实密钥；
- 本地只跑 debug / 单测：同样可以先造一份占位 `keystore.properties`
  （四行任意非空值，例如 `storeFile=placeholder.jks`），不必真去生成密钥；
  要跑 release 再换真实凭证。

## 维护约定

- 本目录文件由 Phase 7a 分支独占（见 `ENGINEERING_CONVENTIONS.md` §4）；
  SDK 组件版本、Gradle / AGP / Kotlin 版本变更时必须同步更新本 README 与
  workflow 中的版本号（compileSdk 36 / build-tools 36.0.0 / NDK 27.0.12077973 /
  CMake 3.22.1 / JDK 17）。
- 发现构建失败：以 Actions 日志回流驱动修复（规划书 §8.3-4），禁止在本地
  绕过 CI 私下发包。
- 新增 Secrets 只加名不加值；任何口令不得写入 workflow、`gradle.properties`
  或提交信息（基座 AGENTS.md 红线，优先级高于本 README）。
