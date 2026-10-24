package com.mo.fkLTY.engine

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mo.fkLTY.hook.HookLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 应用替换:流体云打算打开 App A 时,改成打开 App B。
 *
 * 与「URL 规则」的区别——这是"复制口令"这类卡片的解法:
 * 口令卡里没有网址,只有一段口令文本,metis 已经判定"这段口令属于夸克",直接把目标包名
 * 写死成 com.quark.browser。此时按 URL 匹配永远匹配不到,只能按**目标包名**做替换。
 *
 * 典型用法:com.quark.browser(夸克) → com.quark.clouddrive(夸克网盘)。
 */
data class PackageSwap(
    val from: String,
    val to: String,
    val enabled: Boolean = true,
) {
    companion object {
        fun decodeAll(json: String?): List<PackageSwap> {
            if (json.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(json)
                buildList {
                    for (i in 0 until array.length()) {
                        val o = array.optJSONObject(i) ?: continue
                        val from = o.optString("from").trim()
                        val to = o.optString("to").trim()
                        if (from.isBlank() || to.isBlank() || from == to) continue
                        add(PackageSwap(from, to, o.optBoolean("enabled", true)))
                    }
                }
            }.getOrDefault(emptyList())
        }

        fun encodeAll(list: List<PackageSwap>): String {
            val array = JSONArray()
            list.forEach { swap ->
                array.put(JSONObject().apply {
                    put("from", swap.from)
                    put("to", swap.to)
                    put("enabled", swap.enabled)
                })
            }
            return array.toString()
        }
    }
}

object PackageSwapEngine {

    /** 简洁的 JSON 缓存:远程偏好在 hook 端只读,但每次 startActivity 都解析一遍太浪费 */
    private var cachedJson: String? = null
    private var cached: List<PackageSwap> = emptyList()

    fun load(prefs: android.content.SharedPreferences): List<PackageSwap> {
        val json = prefs.getString(RedirectPrefs.KEY_PACKAGE_SWAPS, null)
        if (json != cachedJson) {
            cachedJson = json
            cached = PackageSwap.decodeAll(json)
        }
        return cached
    }

    /**
     * 改写方案。从"最精确"到"最宽松"排序,逐个探测,第一个能被目标 App 接住的就用。
     *
     * @param probe       探测用 Intent(副本,不影响真身)
     * @param mutate      命中后如何改写真身(包名由调用方统一写)
     * @param viaLauncher 该方案本质是"只把 App 拉起来",用 getLaunchIntentForPackage 判断可行性,
     *                    不用 queryIntentActivities(后者受包可见性影响会误判)
     */
    private class Plan(
        val label: String,
        val probe: Intent,
        val mutate: (Intent) -> Unit,
        val viaLauncher: Boolean = false,
    ) {
        fun resolvable(context: Context, target: String): Boolean =
            if (viaLauncher) {
                BrowserAvailability.isLaunchable(context, target)
            } else {
                BrowserAvailability.handlersForIntent(context, probe).contains(target)
            }
    }

    /**
     * 构造降级链。
     *
     * 为什么需要降级:流体云的口令卡给的是各家私有协议,例如
     *   qklink://www.uc.cn/<hash>?action=open_url&url=https%3a%2f%2fpan.quark.cn%2fs%2fxxx
     * 夸克浏览器认 qklink://,但夸克网盘不认——只探测原始 data 就会一直判定"目标接不住"然后放弃,
     * 这正是 1.2.0 在真机上失败的原因(日志:"Swap target com.quark.clouddrive cannot handle
     * this intent; skip")。实际情况是:网盘虽然不认 qklink://,但它认 https://pan.quark.cn/…,
     * 而且再退一步,什么 URI 都不给、单纯把它启动起来也能让它自己读剪贴板里的口令。
     *
     * 三级降级:
     *  1. keep-data     原始 data 原样给(目标认识这个私有协议时最精确)
     *  2. nested-url    从 ?url= 里挖出真实 https 地址,当普通链接打开(最理想)
     *  3. launcher      清空 data,ACTION_MAIN + LAUNCHER,只把 App 拉起来
     */
    private fun plans(intent: Intent, target: String): List<Plan> {
        val original = intent.dataString
        return buildList {
            if (original != null) {
                add(
                    Plan(
                        label = "keep-data",
                        probe = Intent(intent).apply {
                            component = null
                            selector = null
                            setPackage(target)
                        },
                        mutate = { },
                    )
                )
            }

            val nested = Policy.extractHttpUrl(original)
            if (nested != null && nested != original) {
                val uri = Uri.parse(nested)
                add(
                    Plan(
                        label = "nested-url:$nested",
                        probe = Intent(Intent.ACTION_VIEW, uri).setPackage(target),
                        mutate = {
                            it.action = Intent.ACTION_VIEW
                            // 注意顺序:Intent.setType() 内部会把 data 置空,必须先清 type 再设 data
                            it.type = null
                            it.data = uri
                        },
                    )
                )
            }

            add(
                Plan(
                    label = "launcher",
                    probe = Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_LAUNCHER)
                        .setPackage(target),
                    mutate = {
                        runCatching { it.categories?.clear() }
                        it.action = Intent.ACTION_MAIN
                        it.type = null        // setType 内部会把 data 置空,统一先清 type
                        it.data = null
                        it.addCategory(Intent.CATEGORY_LAUNCHER)
                    },
                    viaLauncher = true,
                )
            )
        }
    }

    /** 在降级链里挑第一个目标 App 接得住的方案 */
    private fun pickPlan(context: Context, intent: Intent, target: String): Plan? =
        plans(intent, target).firstOrNull { it.resolvable(context, target) }

    /**
     * 兜底修复:包名**已经是替换目标**,但身上带的 data 目标接不住。
     *
     * 为什么需要:metis 可能不是直接复用我们改过的那个 Intent,而是在后续步骤用
     * bean.getPackageName() 重新拼一个 Intent、把原始 data(如 qklink://)贴回去。
     * 那样包名换成功了,启动时却因为 scheme 不认识抛 ActivityNotFoundException,
     * 用户看到的就是"点了没反应"。这里在 startActivity 出口再检查一次并修复。
     *
     * @return true 表示已改写
     */
    fun repairIfNeeded(
        context: Context,
        intent: Intent,
        pkg: String,
        prefs: android.content.SharedPreferences,
        source: String,
    ): Boolean {
        val data = intent.dataString ?: return false
        if (load(prefs).none { it.enabled && it.to == pkg }) return false

        // 目标自己能接住这个 data → 什么都不用做
        val current = Intent(intent).apply {
            component = null
            selector = null
            setPackage(pkg)
        }
        if (BrowserAvailability.handlersForIntent(context, current).contains(pkg)) return false

        val plan = pickPlan(context, intent, pkg) ?: run {
            HookLog.once("repair-fail:$pkg", "Swapped target $pkg cannot handle data=$data and no fallback works; via=$source")
            return false
        }

        intent.component = null
        intent.selector = null
        plan.mutate(intent)
        intent.setPackage(pkg)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        HookLog.log("Repaired intent for $pkg, plan=${plan.label}, via=$source, action=${intent.action}, data=${intent.dataString}")
        return true
    }

    /**
     * 尝试按包名替换。按 [plans] 逐级探测,只要有一级能被目标 App 接住就改写。
     *
     * @return true 表示已改写
     */
    fun apply(
        context: Context,
        intent: Intent,
        entryPkg: String?,
        prefs: android.content.SharedPreferences,
        source: String,
        onPackage: (String) -> Unit,
    ): Boolean {
        val pkg = entryPkg ?: return false
        val swap = load(prefs).firstOrNull { it.enabled && it.from == pkg } ?: return false
        if (swap.to == pkg) return false

        if (!BrowserAvailability.isAvailable(context, swap.to)) {
            HookLog.once("swap-missing:${swap.to}", "Swap target not installed: ${swap.to} (from ${swap.from}); skip")
            return false
        }

        // 先用副本探测,能解析再改真身——避免改完才发现宿主抛 ActivityNotFoundException
        val tried = ArrayList<String>(3)
        var chosen: Plan? = null
        for (plan in plans(intent, swap.to)) {
            if (plan.resolvable(context, swap.to)) {
                chosen = plan
                break
            }
            tried += plan.label
        }
        val plan = chosen ?: run {
            HookLog.once(
                "swap-unresolved:${swap.from}->${swap.to}",
                "Swap target ${swap.to} cannot handle any fallback (tried ${tried.joinToString()}); skip",
            )
            return false
        }
        if (tried.isNotEmpty()) {
            HookLog.log("Swap fallback for ${swap.from} -> ${swap.to}: skipped ${tried.joinToString()}, use ${plan.label}")
        }

        onPackage(swap.to)
        intent.component = null
        intent.selector = null
        plan.mutate(intent)
        intent.setPackage(swap.to)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        HookLog.log(
            "Swapped package ${swap.from} -> ${swap.to}, via=$source, plan=${plan.label}, " +
                "action=${intent.action}, data=${intent.dataString}"
        )
        return true
    }
}
