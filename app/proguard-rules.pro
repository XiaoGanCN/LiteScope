# LiteScope keeps no reflection-based entry points; the rules below are a safety net
# for the (currently disabled) minifier.
-keep class com.litescope.service.ScopeService { *; }
-keep class com.litescope.service.ScopeTileService { *; }
-keep class com.litescope.ui.MainActivity { *; }
-dontwarn org.jetbrains.annotations.**
