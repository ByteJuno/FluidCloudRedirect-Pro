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
     *
     * @param preferredUrl 已知的更好落地地址(目前只有"引导安装"路径从剪贴板捞到分享链接时会传)。
     *                     有它时插到最前面,优先级高于上面三级——因为原始 intent 里的 data
     *                     是个 market:// 商店链接,对目标 App 毫无意义。
     */
    private fun plans(intent: Intent, target: String, preferredUrl: String? = null): List<Plan> {
        val original = intent.dataString
        return buildList {
            if (preferredUrl != null) {
                val uri = Uri.parse(preferredUrl)
                add(
                    Plan(
                        label = "shared-link:$preferredUrl",
                        probe = Intent(Intent.ACTION_VIEW, uri).setPackage(target),
                        mutate = {
                            runCatching { it.categories?.clear() }
                            it.action = Intent.ACTION_VIEW
                            it.type = null        // setType 内部会把 data 置空,统一先清 type
                            it.data = uri
                        },
                    )
                )
            }

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
    private fun pickPlan(
        context: Context,
        intent: Intent,
        target: String,
        preferredUrl: String? = null,
    ): Plan? = plans(intent, target, preferredUrl).firstOrNull { it.resolvable(context, target) }

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

    /**
     * 「去商店安装源 App」的拦截。
     *
     * 为什么需要:卸载夸克之后 metis 不再产出 `package=com.quark.browser` 的跳转意图,
     * 卡片变成"去安装夸克",点击发出的是 `market://details?id=com.quark.browser` 这类商店意图。
     * 此时目标包是商店、不是源包,按 from 匹配的应用替换天然不命中,点击就落到应用商店。
     * 这里把"引导安装源 App"的意图接住,改成打开替换目标。
     *
     * 落地优先级:剪贴板里的分享地址 > 原始数据原样 > 内嵌地址 > 只启动 App。
     * 剪贴板往往还留着用户刚复制的口令,能用就用,这样能直接落到分享页而不只是拉起 App。
     *
     * @return true 表示 Intent 已被改写
     */
    fun redirectInstallPrompt(
        context: Context,
        intent: Intent,
        prefs: android.content.SharedPreferences,
        source: String,
    ): Boolean {
        val swaps = load(prefs).filter { it.enabled && it.from != it.to }
        if (swaps.isEmpty()) return false
        // 便宜预筛:既没有 data 也没有 extras/clip,不可能提到任何包名,直接放行
        if (intent.data == null && intent.extras == null && intent.clipData == null) return false

        val target = intent.`package` ?: intent.component?.packageName
        val data = intent.data
        val blobs by lazy { intentBlobs(intent) }

        for (swap in swaps) {
            val verdict = StoreLink.classify(
                targetPackage = target,
                dataString = intent.dataString,
                scheme = data?.scheme,
                host = data?.host,
                blobs = blobs,
                fromPackage = swap.from,
            )
            when (verdict) {
                StoreLink.Verdict.NONE -> continue

                StoreLink.Verdict.MENTIONS_ONLY -> {
                    // 提到了源包名但不是商店意图:留痕但不动,避免误伤正常跳转
                    HookLog.card(
                        "install-miss[$source]: mentions ${swap.from} but not a store intent; " +
                            IntentInspector.describe(intent)
                    )
                    continue
                }

                StoreLink.Verdict.INSTALL_PROMPT -> {
                    if (swap.to == target) return false
                    if (!BrowserAvailability.isAvailable(context, swap.to)) {
                        HookLog.once(
                            "install-missing:${swap.to}",
                            "Install-prompt for ${swap.from} seen, but swap target ${swap.to} is missing; skip",
                        )
                        continue
                    }
                    HookLog.card(
                        "install-prompt[$source]: ${swap.from} missing, redirect to ${swap.to}; " +
                            IntentInspector.describe(intent)
                    )
                    val shared = ClipboardShare.findUrl(context)
                    if (shared != null) {
                        HookLog.log("install-prompt[$source]: clipboard still holds a share link, use it")
                    }
                    val plan = pickPlan(context, intent, swap.to, shared) ?: run {
                        HookLog.once(
                            "install-unresolved:${swap.to}",
                            "Install-prompt redirect failed: ${swap.to} unreachable; skip",
                        )
                        return false
                    }
                    intent.component = null
                    intent.selector = null
                    plan.mutate(intent)
                    intent.setPackage(swap.to)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    HookLog.log(
                        "Redirected install-prompt ${swap.from} -> ${swap.to}, via=$source, " +
                            "plan=${plan.label}, action=${intent.action}, data=${intent.dataString}"
                    )
                    return true
                }
            }
        }
        return false
    }

    /** 收集 Intent 里所有可能承载包名的文本(clipData + extras),用于识别引导安装意图 */
    private fun intentBlobs(intent: Intent, limit: Int = 12): List<String> {
        val out = ArrayList<String>(limit)
        runCatching {
            intent.clipData?.let { clip ->
                for (i in 0 until minOf(clip.itemCount, 4)) {
                    val item = clip.getItemAt(i) ?: continue
                    val text = item.text?.toString() ?: item.uri?.toString() ?: continue
                    out += text.take(400)
                }
            }
            intent.extras?.let { bundle ->
                for (key in bundle.keySet()) {
                    if (out.size >= limit) break
                    when (val value = runCatching { bundle.get(key) }.getOrNull()) {
                        is CharSequence -> out += value.toString().take(400)
                        is Uri -> out += value.toString().take(400)
                        else -> Unit
                    }
                }
            }
        }
        return out
    }
}
