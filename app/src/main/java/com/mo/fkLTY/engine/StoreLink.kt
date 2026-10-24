package com.mo.fkLTY.engine

/**
 * 「引导安装」意图的识别。
 *
 * 场景：卸载夸克（App A）之后，流体云的复制口令卡不再产出"打开夸克"的 Intent，
 * 而是换成一张"去安装夸克"的卡，点击后发出去的是商店意图：
 *
 *     action=android.intent.action.VIEW  data=market://details?id=com.quark.browser
 *
 * 这时 `intent.package` 是商店（或者干脆为 null，让系统去解析），
 * 按"目标包名 == from"匹配的应用替换永远不命中，点击就落到了应用商店——
 * 用户看到的现象就是"装夸克时能跳网盘，卸了夸克就跳商店"。
 *
 * 纯 JVM 逻辑，不依赖 android.content.Intent：调用方把字段拆开传进来，便于单测。
 */
object StoreLink {

    enum class Verdict {
        /** 与任何替换规则无关，完全不动 */
        NONE,

        /** 提到了源包名，但看着不像商店意图——只留痕，不改写（避免误伤正常网页跳转） */
        MENTIONS_ONLY,

        /** 明确的"引导去商店安装源 App"，应改写成打开替换目标 */
        INSTALL_PROMPT,
    }

    /** 常见应用商店。命中其中之一说明这次跳转的目的是"去装 App" */
    private val STORE_PACKAGES = setOf(
        "com.heytap.market",
        "com.oppo.market",
        "com.oplus.market",
        "com.coloros.market",
        "com.android.vending",
        "com.xiaomi.market",
        "com.huawei.appmarket",
        "com.bbk.appstore",
        "com.tencent.android.qqdownloader",
    )

    /** 商店私有协议。market:// 最常见，oaps:// 是 OPPO 自家那套 */
    private val STORE_SCHEMES = setOf(
        "market", "oaps", "oppomarket", "appmarket", "appmarketclient", "itms-apps", "samsungapps",
    )

    /** 商店网页域名特征，如 i.market.heytap.com */
    private val STORE_HOST_HINTS = arrayOf("market", "appstore", "appgallery")

    fun isStorePackage(pkg: String?): Boolean = pkg != null && pkg.lowercase() in STORE_PACKAGES

    fun isStoreScheme(scheme: String?): Boolean = scheme != null && scheme.lowercase() in STORE_SCHEMES

    fun isStoreHost(host: String?): Boolean =
        host != null && STORE_HOST_HINTS.any { host.lowercase().contains(it) }

    /** dataString 或任意 extras/clip 文本里是否出现了这个包名 */
    fun mentionsPackage(dataString: String?, blobs: List<String>, pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val needle = pkg.lowercase()
        if (dataString?.lowercase()?.contains(needle) == true) return true
        return blobs.any { it.lowercase().contains(needle) }
    }

    fun classify(
        targetPackage: String?,
        dataString: String?,
        scheme: String?,
        host: String?,
        blobs: List<String>,
        fromPackage: String,
    ): Verdict {
        if (fromPackage.isBlank()) return Verdict.NONE
        // 目标就是源包本身：属于正常跳转路径，交给应用替换处理，不在这里抢
        if (targetPackage == fromPackage) return Verdict.NONE
        if (!mentionsPackage(dataString, blobs, fromPackage)) return Verdict.NONE

        val storeish = isStoreScheme(scheme) || isStorePackage(targetPackage) || isStoreHost(host)
        return if (storeish) Verdict.INSTALL_PROMPT else Verdict.MENTIONS_ONLY
    }
}
