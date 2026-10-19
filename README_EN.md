# MultiUrlManager

[中文](README.md) · [Detailed documentation](docs/README.md) · [Changelog](CHANGELOG.md)

An Android library for managing base URLs across services and environments. Maintain development, testing and production configurations, choose a URL for each service, and commit the complete configuration with one Save. The core has no Retrofit or OkHttp dependency.

- **Separate environments and services**: switch a complete environment or change one service's selected candidate independently.
- **Draft editing**: add, edit, delete, environment switching and reset stay in a draft until Save succeeds. Cancel leaves the active configuration untouched.
- **Explicit save outcomes**: snapshot revisions detect conflicts; failed storage writes never report success; listeners receive complete before/after states.
- **Legacy integration**: 1.0.x fixed-array JSON and major entry points remain available, and migration preserves saved addresses. Behavior changes are explained below.
- **Native Android screen**: light/dark modes, English/Chinese resources, theme customization and Activity Result support.

The **1.1.0 release candidate has been uploaded to Central and reached `VALIDATED`; it has not been publicly published**. Candidate commit `063c92c` passed [the complete CI run and API 24/36 cloud matrix](https://github.com/logan0817/MultiUrlManager/actions/runs/39211596505); the preceding implementation passed Android 16 physical-device checks. See the [upload verification record](docs/ci-and-release.md#110-上传验收记录) for the tested files, signing identity and deployment. Evaluate the APIs below through the local Maven repository.

## Screenshots

English screenshots from an Android 16 (API 36) physical device, captured at 1220 × 2712. The dashboard uses a regular screenshot; scroll in the app to see the remaining scenarios.

<p>
  <img src="docs/assets/home-device-en.png" alt="Sample dashboard in English on an Android 16 physical device" width="240" />
  <img src="docs/assets/editor-device-en.png" alt="Multi-environment editor in English on an Android 16 physical device" width="240" />
  <img src="docs/assets/editor-night-device-en.png" alt="Dark configuration editor in English on an Android 16 physical device" width="240" />
</p>

Use **Add** in the toolbar to open the address form and **Save** at the bottom to commit the draft. Tap a candidate to select it; long press to edit, delete or copy. Reset restores the current environment's defaults in the draft and takes effect only after saving.

## Installation

Requires Android **API 24+**. The library uses Java 17 bytecode. This repository uses JDK 17, Gradle Wrapper 8.14.3, AGP 8.13.0 and compileSdk/targetSdk 36. The host also needs AndroidX Activity/Fragment dependencies for Activity Result; a standard AppCompat project can use the example below.

Generate the current local Maven candidate from the repository root:

```sh
bash ./gradlew :library-android:publishReleasePublicationToCiRepository
```

Add the generated directory to the host's `settings.gradle.kts`, replacing the path with its actual absolute location:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("/path/to/MultiUrlManager/build/ci/maven")
            content { includeGroup("io.github.logan0817") }
        }
    }
}
```

Add the dependency to the host app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.logan0817:multiurlmanager:1.1.0")
    // The entry Activity below directly uses AppCompat.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
```

The `1.1.0` dependency above currently requires the local Maven repository. After publication and verification of public downloads, remove the local repository to resolve it from Maven Central. A standalone [consumer fixture](fixtures/consumer) resolves the actual AAR/POM; see [development instructions](docs/development.md) for build and validation commands.

## Quick start

### 1. Add a fixed configuration file

Create `src/main/assets/base_urls_config.json` in the host app module. This complete example defines order and sign-in services in two environments. A fresh installation starts in production; development also offers an order backup.

```json
{
  "schemaVersion": 1,
  "defaultEnvironment": "production",
  "environments": [
    {
      "id": "production",
      "name": "Production",
      "requiredKeys": ["order", "login"],
      "urls": [
        {"configKey": "order", "url": "https://order.example.com/", "select": true, "remark": "Production order service"},
        {"configKey": "login", "url": "https://login.example.com/", "select": true, "remark": "Production sign-in service"}
      ]
    },
    {
      "id": "dev",
      "name": "Development",
      "requiredKeys": ["order", "login"],
      "urls": [
        {"configKey": "order", "url": "https://order.dev.example.com/", "select": true, "remark": "Development order service"},
        {"configKey": "order", "url": "https://order-backup.dev.example.com/", "select": false, "remark": "Development order backup"},
        {"configKey": "login", "url": "https://login.dev.example.com/", "select": true, "remark": "Development sign-in service"}
      ]
    }
  ]
}
```

`id` is the stable environment identifier, `name` is its display name, and `configKey` is the service key used by code. Each service can have at most one selected candidate in an environment. Keys in `requiredKeys` must have a selection when saving that environment. These reserved example domains must be replaced with your own addresses; the library itself does not make requests.

Only valid HTTP(S) base URLs are accepted. Credentials, query strings and fragments are rejected; hosts and default ports are normalized and a trailing `/` is added. Missing files, unknown schema versions, duplicate addresses or multiple selections for one service fail explicitly. See [configuration](docs/configuration.md) and the [JSON Schema](docs/schemas/configuration-v1.schema.json) for all fields and code-based configuration.

### 2. Initialize in Application

```kotlin
package com.example.demo

import android.app.Application
import com.logan.multiurlmanager.library.BaseUrlManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        BaseUrlManager.builder(this).build()
    }
}
```

Register the Application in the host's `AndroidManifest.xml`. If your app already has one, initialize in its existing `onCreate()`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:name="com.example.demo.App"
        android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
        <activity
            android:name="com.example.demo.SettingsEntryActivity"
            android:exported="false" />
    </application>
</manifest>
```

Initialization reads the fixed JSON and persisted state and is needed once per process. Reinitialization with different definitions fails. Initialization can access disk; do not repeat it for every request or page refresh. The library registers its configuration Activity through manifest merging with `exported="false"`; no duplicate declaration is necessary.

### 3. Read active URLs

```kotlin
import com.logan.multiurlmanager.library.BaseUrlManager

val manager = checkNotNull(BaseUrlManager.instance) { "Initialize BaseUrlManager in Application first" }
val orderUrl: String? = manager.getUrl("order")
val loginUrl: String? = manager.getUrl("login")

// Read a complete, consistent mapping from one snapshot for a network factory.
val snapshot = manager.snapshot()
val environmentId: String = snapshot.environmentId
val effectiveUrls: Map<String, String> = snapshot.effectiveUrls()

// Fallback is explicit and is never persisted into the configuration.
val optionalUrl: String? = manager.getBaseUrl("files", "https://files.example.com/")?.url
```

`instance` is null before initialization. A service without a selection returns null. `snapshot()` returns an isolated copy; edits affect the manager only after an explicit save. Do not update configuration by mutating the deprecated `dynamicUrls` collection.

### 4. Open the built-in screen

This Activity can serve as a host configuration entry. Register it in the host manifest and open it from your own button or developer menu.

```kotlin
package com.example.demo

import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.logan.multiurlmanager.library.BaseUrlManagerContract

class SettingsEntryActivity : AppCompatActivity() {
    private val settings = registerForActivityResult(BaseUrlManagerContract()) { result ->
        if (result != null) {
            Toast.makeText(
                this,
                "Environment: ${result.environmentId}; changed: ${result.changed}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(Button(this).apply {
            text = "Manage base URLs"
            setOnClickListener { settings.launch(null) }
        })
    }
}
```

Cancel returns null. On a successful save, `BaseUrlManagerResult` contains `environmentId`, `revision`, `changed` and the current environment's selected `urls`. Screen operations edit a draft and become active together after Save. Editing only an unselected candidate can still produce `changed=true`; network listeners depend on a change to the effective mapping or current environment.

## Edit and save from code

Use `saveAsync` on the UI thread. The library writes on its background queue and dispatches the callback on the main thread. This function switches a snapshot to the production environment defined above and handles all three failure reasons:

```kotlin
import android.content.Context
import android.widget.Toast
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.SaveResult

fun selectProduction(context: Context) {
    val manager = checkNotNull(BaseUrlManager.instance)
    val draft = manager.snapshot().copy(environmentId = "production")
    manager.saveAsync(draft) { result ->
        val message = when (result) {
            is SaveResult.Success ->
                "Saved ${result.snapshot.environmentId}; changed: ${result.changed}"
            is SaveResult.Failure -> when (result.reason) {
                SaveResult.Reason.VALIDATION -> "Invalid configuration: ${result.message}"
                SaveResult.Reason.CONFLICT -> "Configuration changed. Reload before editing again."
                SaveResult.Reason.STORAGE -> "Storage failed. Confirm stored state before retrying."
            }
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
```

- **VALIDATION**: correct the URL, required service selection or environment information before saving again.
- **CONFLICT**: the draft revision is stale. Preserve user input, read a fresh snapshot and ask the user to confirm any merge instead of overwriting the newer state.
- **STORAGE**: the write outcome is uncertain and subsequent writes are blocked. Successfully call `refreshData()` on a background thread to confirm persisted state, or restart and confirm it before editing again. The built-in screen offers Reload; a failed read preserves the draft and protection state.

An unchanged save returns `Success(changed=false)`. Synchronous `save`, `select`, `switchEnvironment`, `restoreDefaults` and legacy write APIs access disk and belong on a background thread. Legacy write methods throw on failure. See [API documentation](docs/api.md) for complete snapshot editing and threading rules.

## Observe changes and update network services

Listeners receive complete before/after snapshots on the main thread in commit order. A notification occurs only when the current environment or effective URL mapping changes. This object maintains a replaceable mapping and explicitly releases its listener:

```kotlin
import com.logan.multiurlmanager.library.BaseUrlManager
import com.logan.multiurlmanager.library.UrlChangeListener
import java.io.Closeable

class ActiveUrls(private val manager: BaseUrlManager) : Closeable {
    @Volatile
    var urls: Map<String, String> = manager.snapshot().effectiveUrls()
        private set

    private val listener = UrlChangeListener { _, current ->
        urls = current.effectiveUrls()
    }

    init {
        manager.addListener(listener)
        urls = manager.snapshot().effectiveUrls()
    }

    override fun close() {
        manager.removeListener(listener)
    }
}
```

Create the object when its owner begins observing and call `close()` when that lifecycle ends. An Activity observing only while visible can create it in `onStart()` and close it in `onStop()`; an application-level network factory can retain it for its own lifetime. Registration does not replay the current state, so the example explicitly reads a snapshot.

**Saving configuration does not update an existing Retrofit, OkHttp or Service instance.** Read the complete mapping in a listener or Activity Result callback, rebuild changed services, and replace the references owned by the host. In-flight requests keep their original URL. Cross-environment tokens, cookies, databases and business caches are also the host's responsibility. The compiled, network-framework-independent [integration example](app/src/main/java/com/logan/multiurlmanager/NetworkIntegrationExample.kt) demonstrates replacing services. See [UI and network behavior](docs/ui-and-network.md) for details.

## Debug, Release and legacy upgrades

The library reads the final `assets/base_urls_config.json` packaged in the host APK. It does not infer the environment from its own library build type, and fixed addresses do not need to be defined in Kotlin.

| Host source layout | Packaged configuration and fresh-install environment |
| --- | --- |
| Only `src/main/assets/base_urls_config.json` | Debug and Release use the same file and its `defaultEnvironment`; Release does not automatically switch to production |
| Add `src/release/assets/base_urls_config.json` | Release replaces the entire main asset with this file; environment arrays are not merged |
| Add `src/debug/assets/base_urls_config.json` | Debug uses this override; other variants follow their own source sets |

Initial environment priority is **explicit `setDefaultEnvironment` / `setFileConfigKey` > JSON `defaultEnvironment` > legacy fallback `release_defaults`**. The selected environment must exist. Do not add the new field directly to legacy array JSON without a schemaVersion.

The repository demo's main asset contains dev/test/production and defaults to dev. Its release asset contains only production and defaults to production. These defaults apply to first initialization: **installing an update preserves persisted state**, does not force production and does not delete saved environments. A production host requiring a locked production environment needs its own runtime and migration policy.

On upgrade from 1.0.x, valid saved addresses—including defaults automatically written by the old version—move into the active `legacy` environment (or the first available `legacy-1` style ID if the host already uses that name). New defaults do not overwrite those selections or rename old service keys. Legacy `debug_defaults` / `release_defaults` arrays, `setFileConfigKey`, Activity launch and result-parsing entry points remain supported. The `dynamicUrls` getter now returns a copy, so mutating it has no effect. See [migration](docs/migration-1.1.md) for clearing, storage failure, default merging, backups and downgrade limits.

## Common APIs

| API | Purpose |
| --- | --- |
| `builder(context).build()` | Initialize from JSON; `setEnvironments` / `setDefaultProvider` are also supported |
| `getUrl(key)` / `getBaseUrl(key, fallback)` | Read the selection, optionally with an explicit fallback |
| `snapshot()` / `snapshot().effectiveUrls()` | Obtain isolated complete state / the current environment's effective mapping |
| `saveAsync(snapshot, callback)` | Persist on a background queue and deliver the result on the main thread |
| `save(snapshot)` | Synchronously commit with revision, validation and storage checks |
| `select(key, url)` / `switchEnvironment(id)` | Synchronous convenience commits returning `SaveResult` |
| `restoreDefaults()` | Synchronously restore current-environment defaults |
| `defaultEnvironment(id)` | Read a copy of an environment's defaults without changing active state |
| `addListener` / `removeListener` | Subscribe / unsubscribe to effective configuration changes |
| `BaseUrlManagerContract` | Activity Result entry point for the built-in screen |

See the [Java example](app/src/main/java/com/logan/multiurlmanager/JavaIntegrationExample.java) and [API reference](docs/api.md) for Java and legacy APIs. The library targets **single-process** configuration management and does not synchronize writes across processes.

## Customize the screen theme

In the host's `res/values/styles.xml`, inherit the library theme:

```xml
<resources>
    <style name="AppUrlManagerTheme" parent="BaseUrlManagerTheme">
        <item name="base_url_toolbar_bg_color">#EEF2FF</item>
        <item name="base_url_page_title_text_color">#172554</item>
    </style>
</resources>
```

Override the library Activity's theme in the host manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <activity
            android:name="com.logan.multiurlmanager.library.BaseUrlManagerActivity"
            android:theme="@style/AppUrlManagerTheme"
            tools:replace="android:theme" />
    </application>
</manifest>
```

Provide matching dark colors in `values-night`. The status-bar safe area and toolbar share `base_url_toolbar_bg_color`; system-bar icons adapt to the actual background. See [attrs.xml](library-android/src/main/res/values/attrs.xml) for all attributes. Existing `base_url_btn_add_*` names now style the bottom Save button, preserving theme compatibility.

## More documentation and contributing

Detailed reference pages are currently maintained in Chinese:

| Topic | Contents |
| --- | --- |
| [Configuration](docs/configuration.md) | JSON fields, code configuration, validation, build source sets and default updates |
| [API](docs/api.md) | Snapshots, saving, threads, listeners, Java and binary compatibility boundaries |
| [UI and network](docs/ui-and-network.md) | Drafts, themes, results and when network changes take effect |
| [Migration](docs/migration-1.1.md) | Legacy state, collection behavior, backups and downgrade limits |
| [Development](docs/development.md) | Toolchain, tests and real AAR/R8 consumer |
| [CI and releases](docs/ci-and-release.md) | Required checks, artifact identity, publication and recovery |
| [Troubleshooting](TROUBLESHOOTING.md) | Initialization, storage, builds and shrinking |
| [Contributing](CONTRIBUTING.md) / [Changelog](CHANGELOG.md) | Change requirements and development version status |

When reporting an issue, include the library version, Android version, build type, sanitized configuration, reproduction steps and logs. Do not include keys, real tokens or private service credentials. Bug and feature-request templates are available; read the contribution guide before submitting changes.

Based on [jenly1314/BaseUrlManager](https://github.com/jenly1314/BaseUrlManager), extended for multiple services and environments. Licensed under [MIT](LICENSE).
