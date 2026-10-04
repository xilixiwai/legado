# AGENTS.md — 开发约束

## 签名(硬性约束)

- 本项目 debug 开发 APK(applicationId `io.legado.app.debug`)使用**项目专用开发签名** `keystore/legado-dev.jks`(alias `legado-dev`),不依赖开发机器默认 Android debug.keystore。任何修改 signingConfigs 的行为必须保持项目专用开发签名稳定。
- 正式 release 签名与开发签名完全分离:release 密钥只存在于 GitHub Secrets(`.github/workflows/release.yml` 的 `RELEASE_KEY_STORE` 等),release 密钥及其密码不得提交仓库。
- `.github/workflows/legado.jks` 仅用于 CI 测试构建签名(test.yml 的原包名/共存包),与开发签名、release 签名互不通用,不得替换或删除。
- 开发签名密码通过用户级 `~/.gradle/gradle.properties` 的 `DEV_STORE_PASSWORD` 或同名环境变量提供(参见 `gradle.properties.example` 与 `keystore/README.md`),不得写入仓库内任何文件。
- `.gitignore` 中已排除 `app/key.jks`、`app/legado.jks`(CI/临时签名文件防泄漏);`keystore/legado-dev.jks` 是项目规定的开发签名资产,**必须**保持被 Git 跟踪。
