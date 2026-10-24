# LSPosed 通过 META-INF/xposed/java_init.list 里的**类名字符串**反射加载入口，
# 所以这些类必须保留原名，否则模块装上也不会被加载。
-keep class com.mo.fkLTY.hook.MainHook { *; }

# Hook 实现类同样可能被框架按名字查找（以及 XposedBridge.hookAllMethods 传字符串匹配），
# 保留整个 hook 包，代价很小。
-keep class com.mo.fkLTY.hook.** { *; }

# 保留元数据与注解，避免 R8 裁掉 Xposed/序列化依赖的注解信息
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# 保留 libxposed 的服务实现（App 端通过它读写远程偏好）
-keep class io.github.libxposed.service.** { *; }
