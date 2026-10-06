package com.yuejian.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuejian.model.SourceAnchor

@Composable internal fun ConversationSources(
    sources: List<SourceAnchor>, focusedId: String?, enabled: Boolean,
    onLocate: (SourceAnchor) -> Unit, onRemove: (SourceAnchor) -> Unit,
    loadImage: suspend (SourceAnchor) -> Bitmap?
) {
    var viewing by remember { mutableStateOf<SourceAnchor?>(null) }
    var removing by remember { mutableStateOf<SourceAnchor?>(null) }
    Column(Modifier.fillMaxWidth().heightIn(max=200.dp).verticalScroll(rememberScrollState()),
        verticalArrangement=Arrangement.spacedBy(6.dp)) {
        sources.forEachIndexed { index, source ->
            Surface(shape=MaterialTheme.shapes.small,
                color=if(source.id==focusedId)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp)) {
                    Text("${index+1}. 第 ${source.pageIndex+1} 页 · ${if(source.kind=="text")"文字" else "图片"}${if(source.conversationAnchorId==null)" · 原始资料" else ""}",
                        style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if(source.kind=="text")source.quote else "圈选图片 · 点击查看原图",
                        maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall,
                        modifier=Modifier.fillMaxWidth().clickable { viewing=source })
                    Row {
                        TextButton(onClick={viewing=source},contentPadding=PaddingValues(horizontal=4.dp)) { Text("查看") }
                        TextButton(onClick={onLocate(source)},contentPadding=PaddingValues(horizontal=8.dp)) { Text("定位") }
                        TextButton(onClick={removing=source},enabled=enabled,
                            contentPadding=PaddingValues(horizontal=8.dp)) { Text("移除") }
                    }
                }
            }
        }
    }
    viewing?.let { source ->
        var bitmap by remember(source.id) { mutableStateOf<Bitmap?>(null) }
        var imageFailed by remember(source.id) { mutableStateOf(false) }
        LaunchedEffect(source.id) {
            if(source.kind=="region")try { bitmap=loadImage(source);imageFailed=bitmap==null }
            catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(_: Exception) { imageFailed=true }
        }
        AlertDialog(onDismissRequest={viewing=null},title={Text("第 ${source.pageIndex+1} 页 · 资料")},text={
            if(source.kind=="text")SelectionContainer {
                Text(source.quote,Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState()))
            } else bitmap?.let { Image(it.asImageBitmap(),"所选资料原图",Modifier.fillMaxWidth().heightIn(max=300.dp)) }
                ?: Text(if(imageFailed)"原图读取失败，请重新打开" else "正在读取原图…")
        },confirmButton={TextButton(onClick={viewing=null}) { Text("关闭") }})
    }
    removing?.let { source ->
        AlertDialog(onDismissRequest={removing=null},title={Text("移除这份补充资料？")},text={
            Text("将从该问答的后续上下文中移除这份资料，并移除页面标记。已有回答保留，可能仍引用这份资料。")
        },confirmButton={TextButton(enabled=enabled,onClick={removing=null;onRemove(source)}) { Text("移除") }},
            dismissButton={TextButton(onClick={removing=null}) { Text("取消") }})
    }
}
