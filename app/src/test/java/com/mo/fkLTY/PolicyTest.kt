package com.mo.fkLTY

import com.mo.fkLTY.engine.Policy
import com.mo.fkLTY.engine.RuleToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy 与旧模块 a/m.d 逐行对位的行为测试(01 号文档 §2.4 三规则)。
 */
class PolicyTest {

    private val on = RuleToggles(
        protocol = true,
        textSearch = true,
        heytapHttp = true,
        anyBrowserCard = false,
    )

    private fun evaluate(pkg: String? = null, textType: Int? = null, data: String? = null) =
        Policy.evaluate(pkg, textType, data, on)

    @Test
    fun `rule1 extracts url param`() {
        val scene = evaluate(
            data = "heytapbrowser://webpage?url=https%3A%2F%2Fexample.com%2Ftest",
        )
        assertEquals("https://example.com/test", scene?.extractedUrl)
        assertEquals("rule1_protocol", scene?.rule)
    }

    @Test
    fun `rule1 accepts undecoded url`() {
        val scene = evaluate(data = "heytapbrowser://webpage?url=https://example.com/a?b=1")
        assertEquals("https://example.com/a?b=1", scene?.extractedUrl)
    }

    @Test
    fun `rule1 prefix is case insensitive`() {
        val scene = evaluate(data = "HeyTapBrowser://webpage?url=https://example.com")
        assertEquals("https://example.com", scene?.extractedUrl)
    }

    @Test
    fun `rule1 requires webpage host`() {
        assertNull(evaluate(data = "heytapbrowser://other?url=https://example.com"))
    }

    @Test
    fun `rule1 without query fails`() {
        assertNull(evaluate(data = "heytapbrowser://webpage"))
    }

    @Test
    fun `rule1 with non-http url fails`() {
        assertNull(evaluate(data = "heytapbrowser://webpage?url=ftp://example.com/file"))
    }

    @Test
    fun `rule1 short-circuits later rules when unparseable`() {
        // 协议前缀命中但解析失败 → 即使 textType==29 也不干预(与旧实现一致)
        assertNull(evaluate(textType = 29, data = "heytapbrowser://garbage"))
    }

    @Test
    fun `rule2 matches text search cards`() {
        val scene = evaluate(textType = 29)
        assertNull(scene?.extractedUrl)
        assertEquals("rule2_text_search", scene?.rule)
    }

    @Test
    fun `rule2 does not match other text types`() {
        assertNull(evaluate(textType = 2, data = "intent://whatever"))
    }

    @Test
    fun `rule3 matches heytap http cards`() {
        val scene = evaluate(pkg = "com.heytap.browser", data = "https://example.com/page")
        assertNull(scene?.extractedUrl)
        assertEquals("rule3_heytap_http", scene?.rule)
    }

    @Test
    fun `rule3 ignores other packages`() {
        assertNull(evaluate(pkg = "com.other.app", data = "https://example.com/page"))
    }

    @Test
    fun `rule3 ignores non-http data`() {
        assertNull(evaluate(pkg = "com.heytap.browser", data = "intent://example.com#Intent;end"))
    }

    @Test
    fun `rule4 disabled by default`() {
        assertNull(evaluate(pkg = "com.microsoft.emmx", data = "https://example.com/page"))
    }

    @Test
    fun `rule4 matches other browser when enabled`() {
        val on4 = on.copy(anyBrowserCard = true)
        val scene = Policy.evaluate(
            "com.microsoft.emmx", null, "https://example.com/page", on4,
        ) { pkg -> pkg == "com.microsoft.emmx" }
        assertEquals("rule4_browser_card", scene?.rule)
    }

    @Test
    fun `rule4 requires browser callback`() {
        val on4 = on.copy(anyBrowserCard = true)
        assertNull(
            Policy.evaluate(
                "com.some.random.app", null, "https://example.com/page", on4,
            ) { false },
        )
    }

    @Test
    fun `toggles can disable rules`() {
        val off = RuleToggles(protocol = false, textSearch = false, heytapHttp = false, anyBrowserCard = false)
        assertNull(Policy.evaluate("com.heytap.browser", 29, "heytapbrowser://webpage?url=https://example.com", off))
    }

    @Test
    fun `null and blank data never matches`() {
        assertNull(evaluate())
        assertNull(evaluate(data = "   "))
        assertFalse(Policy.isValidHttpUrl(null))
        assertFalse(Policy.isValidHttpUrl("  "))
        assertFalse(Policy.isValidHttpUrl("not a url"))
        assertTrue(Policy.isValidHttpUrl("HTTPS://Example.COM"))
    }

    // ---- 包装型私有协议(复制口令卡的真实形态) ----

    private val qklink =
        "qklink://www.uc.cn/b20b84fd735a8dd3f7541129bacc4e9a?action=open_url&url=https%3a%2f%2fpan.quark.cn%2fs%2febb0e2cb19e0"

    @Test
    fun `qklink card yields the nested page url`() {
        assertEquals("https://pan.quark.cn/s/ebb0e2cb19e0", Policy.extractNestedUrl(qklink))
        // extractHttpUrl 也要能一路走到内嵌地址
        assertEquals("https://pan.quark.cn/s/ebb0e2cb19e0", Policy.extractHttpUrl(qklink))
    }

    @Test
    fun `qklink data itself is not a valid http url`() {
        // 之所以旧版本失败:isValidHttpUrl 只认 http/https,于是整张卡被判成"没有网址"
        assertFalse(Policy.isValidHttpUrl(qklink))
    }

    @Test
    fun `double encoded nested url is decoded`() {
        val wrapped =
            "someapp://open?url=https%253a%252f%252fpan.quark.cn%252fs%252fabc123"
        assertEquals("https://pan.quark.cn/s/abc123", Policy.extractNestedUrl(wrapped))
    }

    @Test
    fun `nested url without whitelisted param name still accepted`() {
        val wrapped = "someapp://open?jump=https%3A%2F%2fexample.com%2Fa%3Fb%3D1"
        assertEquals("https://example.com/a?b=1", Policy.extractNestedUrl(wrapped))
    }

    @Test
    fun `plain https passes through extractHttpUrl unchanged`() {
        assertEquals("https://pan.quark.cn/s/ebb0e2cb19e0", Policy.extractHttpUrl("https://pan.quark.cn/s/ebb0e2cb19e0"))
        assertEquals("https://example.com/p", Policy.extractHttpUrl("heytapbrowser://webpage?url=https%3A%2F%2Fexample.com%2Fp"))
    }

    @Test
    fun `extractNestedUrl is null when nothing embedded`() {
        assertNull(Policy.extractNestedUrl(null))
        assertNull(Policy.extractNestedUrl("qklink://www.uc.cn/abc"))
        assertNull(Policy.extractNestedUrl("https://pan.quark.cn/s/abc"))
        assertNull(Policy.extractNestedUrl("qklink://www.uc.cn/abc?action=open_url"))
        // 内嵌值不是 http(s) 时不采纳
        assertNull(Policy.extractNestedUrl("qklink://x/y?url=ftp%3A%2F%2Fexample.com"))
    }
}
