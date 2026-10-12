# 第三方声明 / Third-Party Notices

agenT 本体以 [MIT](LICENSE) 开源。下表列出当前源码的主要组件与素材；依赖声明以 [版本目录](gradle/libs.versions.toml) 和 [app/build.gradle.kts](app/build.gradle.kts) 为准。组件及其传递依赖的版权和许可归各自上游，分发时应保留相应包内声明。

## 随 Release APK 使用

| 组件 / 素材 | 当前用途 | 上游许可 |
| --- | --- | --- |
| Kotlin 标准库与协程 | Kotlin 运行时、异步和状态流 | Apache License 2.0 |
| AndroidX / Jetpack Compose | Core、Lifecycle、Activity、Compose UI / Material 3 / adaptive、Material Icons、Biometric、Fragment、Browser | Apache License 2.0 |
| Google Material Symbols Rounded | 本地导航与主要操作矢量，无运行时网络依赖 | [Apache License 2.0](https://github.com/google/material-design-icons/blob/master/LICENSE) |

[Apache License 2.0 全文](https://www.apache.org/licenses/LICENSE-2.0)。Android 平台提供的 `org.json` 是系统 API；下表的 JSON-java 是 JVM 测试依赖，不随 Release APK 打包。

## 仅开发 / 测试使用

| 组件 | 用途 | 当前依赖的上游许可 |
| --- | --- | --- |
| JUnit 4.13.2 | JVM 单元测试 | [Eclipse Public License 1.0](https://github.com/junit-team/junit4/blob/r4.13.2/LICENSE-junit.txt) |
| JSON-java 20250517 | JVM JSON 测试 | [Public Domain](https://github.com/stleary/JSON-java/blob/20250517/LICENSE) |
| Robolectric 4.17 | Android JVM 环境与原生图形 | [MIT；部分 Android 派生文件为 Apache 2.0](https://github.com/robolectric/robolectric/blob/robolectric-4.17/LICENSE) |
| Roborazzi 1.76.0 | Compose 截图验收 | [Apache License 2.0](https://github.com/takahirom/roborazzi/blob/1.76.0/LICENSE) |
| Compose 测试与 UI tooling | Debug / JVM 验收 | Apache License 2.0 |
| sharp | 可选 Node 静态图标预览 | [Apache License 2.0](https://github.com/lovell/sharp/blob/main/LICENSE)；二进制依赖另有包内声明 |

上述开发工具不属于应用的 Release 运行依赖。依赖或素材变化时同步本页及 [未发布变更记录](CHANGELOG.md#未发布)，不得将开发工具的许可错误归入另一组件的许可章节。
