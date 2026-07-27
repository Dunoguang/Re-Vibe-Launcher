# Re-Vibe-Launcher 🚀

基于 Vibe Launcher 重构的新一代桌面启动器。

> 重新出发，全新架构。

## 技术栈

- **前端**: WebView + WebBridge + Vite + Three.js
- **后端**: Kotlin (Native) + Android SDK
- **最低支持**: Android 8.1 (API 27)
- **目标版本**: Android 17+ (API 37)

## 项目结构

```
Re-Vibe-Launcher/
├── app/                        # Android 应用模块
│   ├── build.gradle.kts        # 应用构建配置
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/         # 前端静态资源
│       │   ├── java/           # Kotlin 源码
│       │   └── res/            # 资源文件
├── pack/                       # 前端源码（Vite + Three.js）
├── build.gradle.kts            # 根构建配置
├── settings.gradle.kts         # Gradle 设置
└── gradle.properties           # Gradle 属性
```

## 构建

```bash
# 构建前端
cd pack
npm install
npm run build

# 构建 APK
cd ..
./gradlew :app:assembleRelease
```

## 许可证

MIT
