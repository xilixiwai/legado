# AGENTS.md — 开发约束

## 签名(硬性约束)

- 本项目 debug 开发 APK(applicationId `io.legado.app.debug`)使用**项目专用开发签名** `keystore/legado-dev.jks`(alias `legado-dev`),不依赖开发机器默认 Android debug.keystore。任何修改 signingConfigs 的行为必须保持项目专用开发签名稳定。
- 正式 release 签名与开发签名完全分离:release 密钥只存在于 GitHub Secrets(`.github/workflows/release.yml` 的 `RELEASE_KEY_STORE` 等),release 密钥及其密码不得提交仓库。
- `.github/workflows/legado.jks` 仅用于 CI 测试构建签名(test.yml 的原包名/共存包),与开发签名、release 签名互不通用,不得替换或删除。
- 开发签名密码通过用户级 `~/.gradle/gradle.properties` 的 `DEV_STORE_PASSWORD` 或同名环境变量提供(参见 `gradle.properties.example` 与 `keystore/README.md`),不得写入仓库内任何文件。
- `.gitignore` 中已排除 `app/key.jks`、`app/legado.jks`(CI/临时签名文件防泄漏);`keystore/legado-dev.jks` 是项目规定的开发签名资产,**必须**保持被 Git 跟踪。

## 文档与临时文件治理

- **唯一状态文档**:`.workbuddy-ai/PROJECT_STATE.md`。现状、关键设计决策、已验证事项、未解决事项、最近提交都写这里;有实质进展就更新它,不要另开总报告。
- **普通修复不建永久报告**:改完直接更新状态文档,并在当次最终回复里交付结论。
- **单独报告只留给确有追溯价值的事**:复杂任务、重大架构变更、重要故障根因、专项验收。**同一主题只保留一份**,新过程并入既有文档,不按轮次堆积。
- **临时输出不进 `reports/`**:测试产物放 `build/`,真机日志放 `logs/`(可随时清空)。
- **不新建重复的 README / 索引 / 总报告**;已有规则文件能改就改,不要再建第二套规则。
- **报告里的测试结论必须标注口径**:写明「当时实跑」的日期与命令;未在真机验证的事项单列,不得把历史成功写成当前已验证。
- `.workbuddy-ai/`、`.workbuddy/`、`logs/` 默认是本地工作产物,不入库;确需入库时逐文件显式 `git add`,禁止 `git add .` / `git add -A` 把它们带进提交。
