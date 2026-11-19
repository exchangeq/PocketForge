# PocketForge 默认不开启混淆；保留加密与代理相关入口，便于后续开启 minify。
-keep class com.pocketforge.ai.core.** { *; }
-keep class com.pocketforge.ai.agent.** { *; }
-dontwarn org.json.**
