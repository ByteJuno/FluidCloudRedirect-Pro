package com.mo.fkLTY.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** 目标应用可用性校验(旧模块 isApplicationAvailable 思路:存在 + enabled) */
object BrowserAvailability {

    fun isAvailable(context: Context, packageName: String): Boolean = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        info.enabled
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }

    /** 规则 4 用:pkg 是否能解析为浏览器(ACTION_VIEW + https + BROWSABLE + MATCH_DEFAULT_ONLY) */
    fun isBrowserPackage(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return try {
            context.packageManager
                .queryIntentActivitiesCompat(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo?.packageName == packageName }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 目标 App 是否有可启动的入口 Activity。
     *
     * 判断"能不能把它拉起来"用这个比 queryIntentActivities 可靠:后者在 Android 11+
     * 受包可见性影响,可能返回空列表而被误判成"不支持",于是应用替换被静默跳过。
     */
    fun isLaunchable(context: Context, packageName: String): Boolean = try {
        context.packageManager.getLaunchIntentForPackage(packageName) != null
    } catch (_: Throwable) {
        false
    }

    /**
     * 系统上所有能接收这个 Intent 的包名(去重)。
     *
     * 用 flags=0(不加 MATCH_DEFAULT_ONLY),避免目标 App 的非默认 activity 被过滤掉而误判为不支持。
     * 应用替换靠它判断"目标 App 认不认这个 URI";认不认都要有个结论,所以不能只看有没有匹配到。
     */
    fun handlersForIntent(context: Context, intent: Intent): List<String> = try {
        context.packageManager
            .queryIntentActivitiesCompat(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 统一封装 queryIntentActivities:API 33+ 必须走 ResolveInfoFlags 重载。
     * 原实现在 minSdk 29 上直接用 ResolveInfoFlags,Android 10~12 会抛
     * NoSuchMethodError 并被 catch 吞掉,导致规则 4 在这些系统上静默失效。
     */
    internal fun PackageManager.queryIntentActivitiesCompat(
        intent: Intent,
        flags: Int,
    ) = if (Build.VERSION.SDK_INT >= 33) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, flags)
    }
}
