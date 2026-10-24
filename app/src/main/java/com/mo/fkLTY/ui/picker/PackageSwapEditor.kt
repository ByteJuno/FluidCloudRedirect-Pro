package com.mo.fkLTY.ui.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mo.fkLTY.engine.PackageSwap

/**
 * 应用替换编辑器。
 *
 * 解决的是"复制口令"这类场景:口令卡里没有网址,metis 已经认定这段口令属于某个 App,
 * 并把目标包名写死。此时只能按目标包名替换,不能按 URL 匹配。
 * 最典型的用法:夸克(com.quark.browser) → 夸克网盘(com.quark.clouddrive)。
 */
@Composable
fun PackageSwapEditor(
    swaps: List<PackageSwap>,
    apps: List<PickerViewModel.BrowserEntry>,
    onSave: (List<PackageSwap>) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (swaps.isEmpty()) {
            Text(
                "用于「复制口令」这类卡片:口令里没有网址,流体云直接认定要打开哪个 App。" +
                    "在这里把那个 App 换成你想要的 App 即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        swaps.forEach { swap ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${labelOf(apps, swap.from)} → ${labelOf(apps, swap.to)}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${swap.from} → ${swap.to}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = swap.enabled,
                            onCheckedChange = { enabled ->
                                onSave(swaps.map { if (it.from == swap.from) it.copy(enabled = enabled) else it })
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onSave(swaps.filterNot { it.from == swap.from }) }) { Text("删除") }
                    }
                }
            }
        }
        OutlinedButton(onClick = { showEditor = true }, modifier = Modifier.fillMaxWidth()) {
            Text("＋ 添加应用替换")
        }
    }

    if (showEditor) {
        SwapEditDialog(
            apps = apps,
            onDismiss = { showEditor = false },
            onConfirm = { swap ->
                onSave(swaps.filterNot { it.from == swap.from } + swap)
                showEditor = false
            },
        )
    }
}

private fun labelOf(apps: List<PickerViewModel.BrowserEntry>, pkg: String): String =
    apps.firstOrNull { it.pkg == pkg }?.label ?: pkg

@Composable
private fun SwapEditDialog(
    apps: List<PickerViewModel.BrowserEntry>,
    onDismiss: () -> Unit,
    onConfirm: (PackageSwap) -> Unit,
) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(0) } // 0=不在选择, 1=选源, 2=选目标
    val valid = from.contains('.') && to.contains('.') && from != to

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加应用替换") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("流体云想打开【A】时,改为打开【B】。", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picking = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text("选择 A(原本要打开的 App)" + if (from.isNotBlank()) ":${labelOf(apps, from)}" else "")
                }
                OutlinedTextField(
                    value = from,
                    onValueChange = { from = it.trim() },
                    label = { Text("A 的包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { picking = 2 }, modifier = Modifier.fillMaxWidth()) {
                    Text("选择 B(改成打开的 App)" + if (to.isNotBlank()) ":${labelOf(apps, to)}" else "")
                }
                OutlinedTextField(
                    value = to,
                    onValueChange = { to = it.trim() },
                    label = { Text("B 的包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = {
                        from = "com.quark.browser"
                        to = "com.quark.clouddrive"
                    },
                ) { Text("填入夸克 → 夸克网盘") }
                Text(
                    "匹配到后会逐级降级:① 原始链接原样给目标 App;② 若目标不认这个私有协议," +
                        "就从链接里的 url= 参数挖出真实网页地址当普通链接打开;③ 再不行就把数据清空、" +
                        "单纯把目标 App 拉到前台(它会自己读剪贴板里的口令)。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = { onConfirm(PackageSwap(from, to)) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (picking != 0) {
        AlertDialog(
            onDismissRequest = { picking = 0 },
            title = { Text(if (picking == 1) "选择 A(原本要打开的)" else "选择 B(改成打开的)") },
            text = {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(apps, key = { it.pkg }) { app ->
                        Card(
                            onClick = {
                                if (picking == 1) from = app.pkg else to = app.pkg
                                picking = 0
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                AppIcon(app.icon, size = 36.dp)
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        app.pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = 0 }) { Text("关闭") } },
        )
    }
}
