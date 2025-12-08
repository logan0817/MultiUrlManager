如果您想查看英文文档，请点击这里：[English Document](./README_EN.md)

# 本项目参考 jenly1314 开源库 [BaseUrlManager](https://github.com/jenly1314/BaseUrlManager)
如果你的项目BaseUrlManager能满足需求，可以直接使用BaseUrlManager。

由于实际开发中，每个环境会有多个域名需要配置， BaseUrlManager框架能满足一个域名的控制，但是我一个环境就有十来个域名需要控制。所以参考BaseUrlManager有了MultiUrlManager

# MultiUrlManager

MultiUrlManager for Android 的设计初衷主要用于开发时，每个环境有多个域名都需要打包APK的场景，通过BaseUrlManager提供的BaseUrl动态设置入口，只需打一
次包，即可轻松随意的切换不同的开发环境和测试环境的不同多个域名。在打生产环境包时，关闭BaseUrl动态设置入口即可。

## 效果展示
<img src="GIF.gif" width="350" />

> 你也可以直接下载 [演示App](https://raw.githubusercontent.com/logan0817/MultiUrlManager/master/app/release/app-release.apk) 体验效果

## 引入

### Gradle:

1. 在Project的 **build.gradle** 或 **setting.gradle** 中添加远程仓库

    ```gradle
    repositories {
        //...
        mavenCentral()
    }
    ```

2. 在Module的 **build.gradle** 中添加依赖项

    ```gradle
    implementation 'io.github.logan0817:multiurlmanager:1.0.1'
    ```
   

## 使用

集成步骤代码示例 （示例出自于[app](app)中）

Step.1 在您项目中的AndroidManifest.xml中通过配置meta-data来自定义全局配置
```xml
    <!-- 在你项目中添加注册如下配置可以覆盖默认样式，也可以直接使用默认样式 -->
    <activity
        android:name="com.logan.multiurlmanager.library.BaseUrlManagerActivity"
        android:exported="false"
        android:theme="@style/BaseUrlManagerTheme"
        android:windowSoftInputMode="adjustPan" />
```

Step.2 在您项目Application的onCreate方法中初始化BaseUrlManager

### 方式一：使用base_urls_config.json配置
```java
    //初始化BaseUrlManager ，默认加载 base_urls_config.json 配置[参考demo内容修改即可]
    //默认数据可以有多个环境，默认提供了两个key：DEBUG_CONFIG_KEY、RELEASE_CONFIG_KEY代表两个环境。
    // 你也可以参考在config文件中添加自定义 CUSTOM_CONFIG_KEY，可以通过setFileConfigKey来使用对应环境来实现打包时候默认数据切换。
    BaseUrlManager.builder(this)
            .setFileConfigKey(BaseUrlConfigLoader.DEBUG_CONFIG_KEY)
//            .setFileConfigKey(BaseUrlConfigLoader.RELEASE_CONFIG_KEY)
//            .setFileConfigKey("CUSTOM_CONFIG_KEY")
            .build()

    //获取baseUrl
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("customKey")

```

### 方式二：使用代码配置，base_urls_config.json会失效。代码配置优先级高
```java
    //初始化BaseUrlManager
    BaseUrlManager.builder(this)
        .setDefaultProvider {
            listOf(
                    BaseUrl(configKey = "videoApiDomain", url = "https://www.douyin.com/", select = true, remark = "douyin Environment"),
                    BaseUrl(configKey = "videoApiDomain", url = "https://www.kuaishou.com//", select = false, remark = "kuaishou Environment"),
                    BaseUrl(configKey = "mailDomain", url = "https://mail.google.com/", select = true, remark = "mail google Environment"),
                    BaseUrl(configKey = "mailDomain", url = "https://mail.qq.com/", select = false, remark = "mail qq Environment")
               )
            }
            .build()

    //获取baseUrl
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("customKey")
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")

```

Step.3 提供动态配置BaseUrl的入口（通过Intent跳转到BaseUrlManagerActivity界面）

写法
```JAVA
    BaseUrlManagerActivity.startBaseUrlManager(this, SET_BASE_URL_REQUEST_CODE)
```

Step.4 当配置改变了baseUrl时，在Activity或Fragment的onActivityResult方法中重新获取baseUrl即可
```java
    //方式1：通过BaseUrlManager获取baseUrl
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("customKey")
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
    //方式2：通过data直接获取baseUrls
    val baseUrls = BaseUrlManagerActivity.parseActivityResult(data)
    baseUrls?.find { it.configKey = "mailDomain" }?.url

```

更多使用详情，请查看[app](app)中的源码使用示例或直接查看 [API帮助文档](https://jitpack.io/com/github/logan0817/MultiUrlManager/latest/javadoc/)

## 相关推荐
- [BaseUrlManager](https://github.com/jenly1314/BaseUrlManager) 只需打一次包，即可轻松随意的切换不同的开发环境或测试环境。
