# 项目专用开发签名(legado-dev.jks)

## 1. 用途

仅用于给 **debug 开发包**(applicationId `io.legado.app.debug`)签名,保证同一签名在任何开发机上可覆盖安装升级,不依赖开发机器默认的 `~/.android/debug.keystore`。

- **不用于 release 构建**(release 签名走 GitHub Secrets,见 `.github/workflows/release.yml`)
- **不用于 CI 测试构建**(CI 测试签名使用 `.github/workflows/legado.jks`,互不通用)
- 不包含任何正式 release 密钥

## 2. keystore 路径

`keystore/legado-dev.jks`(随仓库分发,新机器 clone 后即有)

## 3. alias

`legado-dev`

## 4. 证书指纹

- SHA-256:`C9:D8:39:0B:7D:A7:72:8B:5B:B4:44:06:92:96:6A:B9:B6:7D:D7:68:AA:8F:98:41:B7:D2:65:D6:62:48:93:BE`
- SHA-1:`CB:A3:FB:A2:96:30:BF:77:54:1B:43:80:25:92:CA:3F:7E:66:F5:FE`
- 有效期至 2056-09-26

## 5. 如何在新电脑恢复

```bash
git clone <本仓库>          # keystore/legado-dev.jks 随仓库而来
# 配置密码(见下一节),然后:
./gradlew assembleAppDebug
```

## 6. 如何配置密码

密码**不保存在仓库中**,由每位开发者自行保存(如密码管理器)。配置方式二选一:

- 用户级 `~/.gradle/gradle.properties`(Windows 为 `C:\Users\<你>\.gradle\gradle.properties`)加入:
  `DEV_STORE_PASSWORD=<你的开发签名密码>`
- 或设置环境变量 `DEV_STORE_PASSWORD`

变量名与说明见仓库根目录 `gradle.properties.example`。可选覆盖项:`DEV_STORE_FILE`(keystore 路径,默认用仓库内文件)、`DEV_KEY_ALIAS`(默认 `legado-dev`)。

## 7. 如何构建 debug APK

```bash
./gradlew assembleAppDebug
# 产物: app/build/outputs/apk/app/debug/legado_app_<版本>_debug 命名目录下
```

缺少 `DEV_STORE_PASSWORD` 时构建会直接报错提示,不会静默回退到机器默认 debug.keystore。

## 8. 如何验证 APK 签名

```bash
# 签名指纹(应等于第 4 节 SHA-256)
<SDK>/build-tools/<版本>/apksigner verify --print-certs app/build/outputs/apk/app/debug/*.apk

# applicationId(应为 io.legado.app.debug)
<SDK>/build-tools/<版本>/aapt dump badging app/build/outputs/apk/app/debug/*.apk | head -1
```

## 9. 为什么不能重新生成随机 debug.keystore

Android 的包管理器在覆盖安装时校验**包名 + 签名证书**,且证书一致性是单向门槛:签名不同就永远无法覆盖安装。随机生成的 debug.keystore 每台机器、每次重建都不同(本项目手机上的旧 `io.legado.app.debug` 就因此无法被新环境构建的包覆盖升级)。只有固定使用同一个 keystore 文件,签名才稳定。

## 10. 如果签名文件/密码丢失怎么办

- **keystore 文件丢失**:可从本仓库重新获取(它随仓库分发)。
- **密码丢失**:无法恢复,该 keystore 报废。只能重新生成一份新开发签名,并卸载手机上的旧 App 后重装(会丢失本地数据,请先在 App 内做书源/书架/进度备份导出)。因此请务必把 `DEV_STORE_PASSWORD` 备份到个人密码管理器。
