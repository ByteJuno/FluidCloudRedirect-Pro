package com.mo.fkLTY

import com.mo.fkLTY.engine.StoreLink
import com.mo.fkLTY.engine.StoreLink.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「引导安装」意图识别。
 *
 * 覆盖的是真机现象：卸载夸克后，卡片点击发出的是商店意图，
 * 不是"打开夸克"，所以按 from 包名匹配的应用替换完全不命中。
 */
class StoreLinkTest {

    private val from = "com.quark.browser"

    @Test
    fun `market intent without explicit package is an install prompt`() {
        assertEquals(
            Verdict.INSTALL_PROMPT,
            StoreLink.classify(
                targetPackage = null,
                dataString = "market://details?id=$from",
                scheme = "market",
                host = "details",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `explicit store package with market data is an install prompt`() {
        assertEquals(
            Verdict.INSTALL_PROMPT,
            StoreLink.classify(
                targetPackage = "com.heytap.market",
                dataString = "oaps://mk/detail?id=$from",
                scheme = "oaps",
                host = "mk",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `store page on http host is an install prompt`() {
        assertEquals(
            Verdict.INSTALL_PROMPT,
            StoreLink.classify(
                targetPackage = null,
                dataString = "https://i.market.heytap.com/appDetail?pkgName=$from",
                scheme = "https",
                host = "i.market.heytap.com",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `package name only in extras is still detected`() {
        assertEquals(
            Verdict.INSTALL_PROMPT,
            StoreLink.classify(
                targetPackage = "com.heytap.market",
                dataString = null,
                scheme = null,
                host = null,
                blobs = listOf("pkgName=$from", "from=fluid_cloud"),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `store intent for an unrelated app is left alone`() {
        assertEquals(
            Verdict.NONE,
            StoreLink.classify(
                targetPackage = null,
                dataString = "market://details?id=com.other.app",
                scheme = "market",
                host = "details",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `normal launch aimed at the source package is not stolen`() {
        assertEquals(
            Verdict.NONE,
            StoreLink.classify(
                targetPackage = from,
                dataString = "qklink://www.uc.cn/abc?action=open_url&url=https%3A%2F%2Fpan.quark.cn%2Fs%2Fx",
                scheme = "qklink",
                host = "www.uc.cn",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `plain share link without any package mention is left alone`() {
        assertEquals(
            Verdict.NONE,
            StoreLink.classify(
                targetPackage = "com.heytap.browser",
                dataString = "https://pan.quark.cn/s/ebb0e2cb19e0",
                scheme = "https",
                host = "pan.quark.cn",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `non store page mentioning the package is only logged`() {
        assertEquals(
            Verdict.MENTIONS_ONLY,
            StoreLink.classify(
                targetPackage = "com.heytap.browser",
                dataString = "https://news.example.com/a?id=$from",
                scheme = "https",
                host = "news.example.com",
                blobs = emptyList(),
                fromPackage = from,
            ),
        )
    }

    @Test
    fun `store predicates accept the common oplus and google packages`() {
        assertEquals(true, StoreLink.isStorePackage("com.heytap.market"))
        assertEquals(true, StoreLink.isStorePackage("com.heytap.market".uppercase()))
        assertEquals(true, StoreLink.isStorePackage("com.android.vending"))
        assertEquals(false, StoreLink.isStorePackage("com.quark.clouddrive"))
        assertEquals(false, StoreLink.isStorePackage(null))
    }

    @Test
    fun `scheme matching is case insensitive and rejects lookalikes`() {
        assertEquals(true, StoreLink.isStoreScheme("MARKET"))
        assertEquals(false, StoreLink.isStoreScheme("markets"))
        assertEquals(false, StoreLink.isStoreScheme(null))
    }
}
