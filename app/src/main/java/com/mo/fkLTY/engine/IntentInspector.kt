package com.mo.fkLTY.engine

import android.content.Intent
import android.net.Uri

/**
 * 卡片 Intent 的取证工具。
 *
 * 背景:流体云的卡片不止一种。网页卡片把地址放在 dataString 里,但"打开某应用"这类卡片
 * 可能把地址塞在 extras / clipData,甚至根本不带地址。只认 dataString 会漏掉一大类卡片,
 * 而且漏掉时没有任何日志——用户只看到"还是原来的浏览器"。
 *
 * 还有第二类:私有协议卡片。dataString 是 qklink://…?url=<百分号编码的真实地址>,
 * 本身不是 http(s),但内嵌参数就是落地页。这种情况交给 Policy.extractNestedUrl 处理。
 *
 * 这里做两件事:
 *  1. extractUrl:dataString → clipData → extras,逐层找第一个合法 http(s) 地址;
 *  2. describe:把整个 Intent 压成一行,配合"详细日志"开关输出,用来判定卡片类型。
 */
object IntentInspector {

    private val URL_REGEX = Regex("""https?://[^\s"'<>，。、）】]+""", RegexOption.IGNORE_CASE)

    /** 从 Intent 里尽力找出真实网页地址(含 qklink:// 这类包装型协议的内嵌地址) */
    fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null

        Policy.extractHttpUrl(intent.dataString)?.let { return it }

        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                val item = clip.getItemAt(i)
                findUrl(item.text?.toString() ?: item.uri?.toString())?.let { return it }
            }
        }

        intent.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                val value = runCatching { bundle.get(key) }.getOrNull()
                val text = when (value) {
                    is CharSequence -> value.toString()
                    is Uri -> value.toString()
                    is Array<*> -> value.joinToString(" ") { it?.toString().orEmpty() }
                    else -> null
                }
                findUrl(text)?.let { return it }
            }
        }

        return null
    }

    /** 从任意一段文本里抓第一个合法 http(s) 地址（含包装协议内嵌地址、百分号编码、正则兜底） */
    internal fun findUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        // 整段文本本身就是包装型协议
        Policy.extractHttpUrl(text)?.let { return it }
        // 值被整体百分号编码过 → 解一层再看
        val decoded = percentDecode(text)
        if (decoded != null) Policy.extractHttpUrl(decoded)?.let { return it }
        val raw = URL_REGEX.find(decoded ?: text)?.value ?: return null
        val cleaned = raw.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'', '，', '。')
        return cleaned.takeIf { Policy.isValidHttpUrl(it) }
    }

    /** 局部百分号解码:没有 % 或解码失败返回 null,不抛异常 */
    private fun percentDecode(text: String): String? {
        if (!text.contains('%')) return null
        return runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrNull()
    }

    /** 一行描述整个 Intent,用于详细日志 */
    fun describe(intent: Intent?): String {
        if (intent == null) return "intent=null"
        val clip = intent.clipData?.let { c ->
            (0 until c.itemCount).joinToString(" | ") { i ->
                val item = c.getItemAt(i)
                (item.text?.toString() ?: item.uri?.toString()).orEmpty().take(160)
            }
        } ?: "-"
        val extras = intent.extras?.let { b ->
            b.keySet().joinToString(", ") { key ->
                val v = runCatching { b.get(key) }.getOrNull()
                "$key=${v?.toString()?.take(160)}"
            }
        } ?: "-"
        return "action=${intent.action}, pkg=${intent.`package`}, " +
            "component=${intent.component?.flattenToShortString()}, " +
            "data=${intent.dataString}, flags=0x${Integer.toHexString(intent.flags)}, " +
            "clip=[$clip], extras={$extras}"
    }
}
