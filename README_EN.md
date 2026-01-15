
If you want to view the Chinese document, please click here: [中文文档](./README.md)

# This Project References jenly1314's Open Source Library [BaseUrlManager](https://github.com/jenly1314/BaseUrlManager)
If BaseUrlManager meets your project's needs, you can use it directly.

In practical development, multiple domains often need to be configured for each environment. The BaseUrlManager framework only supports controlling one domain, but I needed to control over a dozen domains in a single environment. Therefore, MultiUrlManager was created, referencing BaseUrlManager.

# MultiUrlManager

MultiUrlManager for Android is primarily designed for scenarios where **multiple domains** for each environment need to be bundled into the APK during development. By using the dynamic BaseUrl setting entry provided by BaseUrlManager, you only need to build the package once to easily and freely switch between different domains across various development and testing environments. For building the production environment package, simply disable the dynamic BaseUrl setting entry.

## Demo Effect
<img src="GIF.gif" width="350" />

> You can also directly download the [Demo App](https://raw.githubusercontent.com/logan0817/MultiUrlManager/master/app/release/app-release.apk) to experience the effect.

## Integration

### Gradle:

1. Add the remote repository to your Project's **build.gradle** or **setting.gradle**
   [![Maven Central](https://img.shields.io/maven-central/v/io.github.logan0817/multiurlmanager.svg?label=Latest%20Release)](https://central.sonatype.com/artifact/io.github.logan0817/multiurlmanager)

    ```gradle
    repositories {
        //...
        mavenCentral()
    }
    ```

2. Add the dependency to your Module's **build.gradle**

    ```gradle
    implementation 'io.github.logan0817:multiurlmanager:1.0.2' // Replace with the latest version shown by the badge above
    ```


## Usage

Integration steps and code example (Example taken from [app](app))

Step 1: Customize the style by configuring the theme in your project's AndroidManifest.xml.
```xml
    <!-- Add the following registration configuration to your project -->
    <activity
        android:name="com.logan.multiurlmanager.library.BaseUrlManagerActivity"
        android:exported="false"
        android:theme="@style/BaseUrlManagerTheme"
        android:windowSoftInputMode="adjustPan" />

        <!-- If you need to update the style, refer to android:theme="@style/AppBaseUrlManagerTheme" -->
```

Step.2 Initialize BaseUrlManager in your Application's onCreate method

### Method 1: Using base_urls_config.json configuration

    // Initialize BaseUrlManager, which defaults to loading the base_urls_config.json configuration [Modify according to the demo content]
    // The default data can contain multiple environments. Two keys are provided by default: DEBUG_CONFIG_KEY and RELEASE_CONFIG_KEY, representing two environments.
    // You can also add a custom CUSTOM_CONFIG_KEY in the config file and use setFileConfigKey to select the corresponding environment for default data switching during packaging.
        BaseUrlManager.builder(this)
            .setFileConfigKey(
                if (BuildConfig.DEBUG) {
                    BaseUrlConfigLoader.DEBUG_CONFIG_KEY
                    //setFileConfigKey("CUSTOM_CONFIG_KEY")
                } else {
                    BaseUrlConfigLoader.RELEASE_CONFIG_KEY
                    //setFileConfigKey("CUSTOM_CONFIG_KEY")
                }
            )
            .build()
    
       // Get baseUrl
       val videoApiDomainUrl = BaseUrlManager.instance?.getBaseUrl("videoApiDomain")
       val mailDomainBaseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
       val customKeyDmomainUrl = BaseUrlManager.instance?.getBaseUrl("customKey")

### Method 2: Using code configuration (base_urls_config.json will be ignored, code configuration has higher priority)

    // Initialize BaseUrlManager
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
    
    // Get baseUrl
       val videoApiDomainUrl = BaseUrlManager.instance?.getBaseUrl("videoApiDomain")
       val mailDomainBaseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
       val customKeyDmomainUrl = BaseUrlManager.instance?.getBaseUrl("customKey")

Step.3 Provide an entry point for dynamic BaseUrl configuration (Jump to BaseUrlManagerActivity via Intent)

Writing style
```JAVA
    BaseUrlManagerActivity.startBaseUrlManager(this, SET_BASE_URL_REQUEST_CODE)
```

Step.4 When the baseUrl configuration changes, retrieve the new baseUrl in the Activity or Fragment's onActivityResult method
```java
    // Method 1: Get baseUrl via BaseUrlManager
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("customKey")
    String baseUrl = BaseUrlManager.instance?.getBaseUrl("mailDomain")
    // Method 2: Get baseUrls directly from data
    val baseUrls = BaseUrlManagerActivity.parseActivityResult(data)
    baseUrls?.find { it.configKey = "mailDomain" }?.url

```

For more details on usage, please check the source code example in[app](app) or view the [API Documentation](https://jitpack.io/com/github/logan0817/MultiUrlManager/latest/javadoc/)

## Related Recommendation
- [BaseUrlManager](https://github.com/jenly1314/BaseUrlManager) Only one package build is required to easily and freely switch between different development or testing environments.
