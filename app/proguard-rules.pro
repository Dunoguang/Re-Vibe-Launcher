# Re-Vibe-Launcher ProGuard 规则（移植自原项目）

# Keep all @JavascriptInterface methods (JsBridge + all modules)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep 所有桥接模块类（控制中心 NativeBridge 依赖）
-keep class com.dng.revibe.launcher.JsBridge { *; }
-keep class com.dng.revibe.launcher.JsBridge$** { *; }
-keep class com.dng.revibe.launcher.PermissionModule { *; }
-keep class com.dng.revibe.launcher.ShellModule { *; }
-keep class com.dng.revibe.launcher.ShizukuModule { *; }
-keep class com.dng.revibe.launcher.AdminModule { *; }
-keep class com.dng.revibe.launcher.FloatWindowModule { *; }
-keep class com.dng.revibe.launcher.WifiModule { *; }
-keep class com.dng.revibe.launcher.SystemModule { *; }
-keep class com.dng.revibe.launcher.MediaModule { *; }
-keep class com.dng.revibe.launcher.InfoModule { *; }
-keep class com.dng.revibe.launcher.FloatWindow { *; }
-keep class com.dng.revibe.launcher.FloatWindow$ControlBridge { *; }
-keep class com.dng.revibe.launcher.MainActivity { *; }

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
