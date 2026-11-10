# 文档索引

当前正式版本为 1.2.0，已于 2026-11-10 发布到 Maven Central，包含核心和可选 OkHttp 两个制品。[1.2.0 方案](plans/v1.2.0-implementation-plan.md)记录范围与验收，[网络路由](network-routing.md)说明可选制品。[发布记录](ci-and-release.md#120-上传验收记录)包含对应 CI、部署 ID、公开文件与签名核验结果；1.1.0 历史记录继续保留。

建议先读 [接入入口](../README.md)，随后按需要阅读：

1. [配置](configuration.md)：环境、业务键、默认值、JSON 与校验。
2. [API](api.md)：状态、写入、监听、线程与 Java。
3. [页面与网络](ui-and-network.md)：草稿、结果、主题和网络生效。
4. [1.1 迁移](migration-1.1.md)：旧数据与有意行为变化。
5. [开发](development.md)：工具链和本地验证。
6. [CI 与发布](ci-and-release.md)：检查门槛、候选身份、发布恢复。

[实现方案](plans/v1.1.0-implementation-plan.md) 保存目标设计、验收清单与待验证项；它不能代替实际 API 文档。[更新记录](../CHANGELOG.md) 区分开发内容和已发布内容。[排障入口](../TROUBLESHOOTING.md) 按现象定位。
