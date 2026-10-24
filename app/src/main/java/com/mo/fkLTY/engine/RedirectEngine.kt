package com.mo.fkLTY.engine

import android.content.Context
import android.content.Intent
import com.mo.fkLTY.hook.HookLog
import com.mo.fkLTY.hook.ReflectedTextIntent

/**
 * 重定向执行体。两条入口共用同一套判定:
 *  - apply():      从 TextIntent bean 入口(主 hook / 冗余 hook),可同时改写 bean 的包名;
 *  - applyToIntent():从 ContextImpl.startActivity 入口(兜底 hook),只改 Intent 本身。
 *
 * 判定顺序:应用替换 → 原有浏览器规则 → 都不命中则完全不动。
 */
object RedirectEngine {

    /** 卡片入口(TextIntent bean) */
    fun apply(context: Context, bean: ReflectedTextIntent, source: String) {
        val intent = bean.intent ?: return
        val prefs = HookLog.prefs() ?: run {
            HookLog.once("prefs", "Remote preferences unavailable; redirection disabled")
            return
        }
        HookLog.card("card[$source]: textType=${bean.textType}, pkg=${bean.packageName}, " + IntentInspector.describe(intent))
        decide(context, intent, bean.packageName, bean.textType, prefs, source) { target ->
            bean.setPackageName(target)
        }
    }

    /**
     * 启动入口(ContextImpl.startActivity)。返回 true 表示 Intent 已被就地改写。
     * 只在"带网页数据"或"目标包在替换表里"时继续,其余直接放行,避免刷日志也避免影响宿主。
     */
    fun applyToIntent(context: Context, intent: Intent, source: String): Boolean {
        val prefs = HookLog.prefs() ?: return false
        val pkg = intent.`package` ?: intent.component?.packageName

        // ---- 兜底修复:包名已经是替换目标,但 data 目标接不住(metis 用 bean 包名重建了 Intent) ----
        if (pkg != null && PackageSwapEngine.repairIfNeeded(context, intent, pkg, prefs, source)) return true

        // ---- 「去商店安装源 App」的卡片:卸载源 App 后 metis 不再产出正常跳转意图 ----
        // 必须放在下面的 hasWebData 预筛之前:商店意图既不是网页数据,目标包也不是源包,
        // 用原来的条件一定漏过去,点击就直接进了应用商店。
        if (PackageSwapEngine.redirectInstallPrompt(context, intent, prefs, source)) return true

        val data = intent.dataString
        // 便宜的预筛,避免对每一次 startActivity 都跑完整解析:
        // http(s) 直链、heytapbrowser:// 协议卡、以及 qklink:// 这类把地址藏在 url= 参数里的包装协议
        val hasWebData = data != null && (
            data.startsWith("http", ignoreCase = true) ||
                data.startsWith("heytapbrowser", ignoreCase = true) ||
                data.contains("url=", ignoreCase = true)
            )
        val swapHit = pkg != null && PackageSwapEngine.load(prefs).any { it.enabled && it.from == pkg }
        if (!hasWebData && !swapHit) return false

        HookLog.card("launch[$source]: pkg=$pkg, " + IntentInspector.describe(intent))
        return decide(context, intent, pkg, null, prefs, source) { intent.setPackage(it) }
    }

    /**
     * 统一判定 + 改写。
     *
     * @param pkg      当前目标包(TextIntent 走 bean 字段,startActivity 走 intent.package)
     * @param textType TextIntent 的 textType;startActivity 入口没有,传 null
     * @param onPackage 目标包名的写回方式(bean 字段 / Intent.setPackage)
     * @return true 表示已改写
     */
    private fun decide(
        context: Context,
        intent: Intent,
        pkg: String?,
        textType: Int?,
        prefs: android.content.SharedPreferences,
        source: String,
        onPackage: (String) -> Unit,
    ): Boolean {
        // 只为留痕:能解出真实网址,说明内嵌参数解析(qklink:// 之类)正常工作
        IntentInspector.extractUrl(intent)?.let { HookLog.card("card[$source]: url=$it") }

        // ---- 0. 应用替换(口令卡这类"没有网址、只有目标 App"的场景) ----
        if (PackageSwapEngine.apply(context, intent, pkg, prefs, source, onPackage)) return true

        // ---- 1. 原有浏览器重定向 ----
        val target = prefs.getString(RedirectPrefs.KEY_TARGET, null)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            HookLog.card("card[$source]: browser redirection disabled (no target), stop")
            return false
        }
        if (pkg == target) return false

        val toggles = RedirectPrefs.toggles(prefs)
        val scene = Policy.evaluate(pkg, textType, intent.dataString, toggles) { candidate ->
            BrowserAvailability.isBrowserPackage(context, candidate)
        }
        if (scene == null) {
            HookLog.card("card[$source]: no browser rule matched (pkg=$pkg, textType=$textType, data=${intent.dataString})")
            return false
        }
        if (!BrowserAvailability.isAvailable(context, target)) {
            HookLog.once("browser:$target", "Selected browser is unavailable: $target (rule=${scene.rule}, via=$source)")
            return false
        }

        onPackage(target)
        intent.component = null
        intent.selector = null
        intent.setPackage(target)
        scene.extractedUrl?.let { url ->
            intent.action = Intent.ACTION_VIEW
            intent.data = android.net.Uri.parse(url)
        }
        HookLog.log(
            "Redirected package=$target, rule=${scene.rule}, via=$source, action=${intent.action}, data=${intent.dataString}"
        )
        return true
    }
}
