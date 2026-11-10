# 文档索引

当前稳定版 **1.2.0** 已发布到 Maven Central，提供核心 `multiurlmanager` 和可选网络制品 `multiurlmanager-okhttp`。第一次接入从 [README 快速接入](../README.md#快速接入)开始，按步骤完成配置、初始化、打开页面和切换验证；英文入口见 [English README](../README_EN.md)。

## 接入与使用

| 你想做什么 | 阅读入口 |
| --- | --- |
| 在自己的 App 中跑通第一个示例 | [README 快速接入](../README.md#快速接入) |
| 了解 JSON、业务展示名、默认环境和代码配置 | [配置](configuration.md) |
| 让 Retrofit / OkHttp 请求随保存的地址切换 | [可选网络路由](network-routing.md)；手动更新网络对象见[页面与网络](ui-and-network.md#网络对象何时更新) |
| 让正式包只使用生产环境 | [固定环境模式](configuration.md#固定环境模式120)与[源码集打包](configuration.md#debug--release-打包使用哪份配置) |
| 在代码中查询、修改、保存或监听 | [API](api.md)，包含线程约定与 Java 调用 |
| 理解页面草稿、变更摘要、返回结果与主题 | [页面与网络](ui-and-network.md) |
| 从旧版本升级，了解已有数据会怎样 | [迁移](migration-1.1.md)；该文重点覆盖 1.0.x 到 1.1+ 的数据变化 |
| 排查初始化、地址未生效、保存或构建问题 | [常见问题](../TROUBLESHOOTING.md) |

## 参与开发与维护

- [开发](development.md)：工具链、本地测试、真实 AAR / R8 消费验证。
- [CI 与发布](ci-and-release.md)：检查门槛、产物身份、发布与失败恢复；[1.2.0 发布记录](ci-and-release.md#120-上传验收记录)包含 CI、公开制品与签名核验结果。
- [贡献要求](../CONTRIBUTING.md)与[更新记录](../CHANGELOG.md)：提交变更前的要求，以及各版本实际变化。
- [1.2.0 实现方案](plans/v1.2.0-implementation-plan.md)与[1.1.0 历史方案](plans/v1.1.0-implementation-plan.md)：设计范围与验收记录；接入时以当前配置和 API 文档为准。
