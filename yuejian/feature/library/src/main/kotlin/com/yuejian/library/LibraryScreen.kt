package com.yuejian.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.yuejian.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel class LibraryViewModel @Inject constructor(private val repository: DocumentRepository) : ViewModel() {
    val documents = repository.observeDocuments().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val navigation = Channel<ImportResult>(Channel.BUFFERED)
    val imported = navigation.receiveAsFlow()
    fun import(uri: String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { navigation.send(repository.importDocument(uri)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = when (e) {
                is SecurityException -> "无法读取文件；密码 PDF 请先解除密码后导入。"
                else -> e.message ?: "导入失败，请检查文件格式和可用空间。"
            } }
            finally { busy.value = false }
        }
    }
    suspend fun trash(id: String) = repository.moveToTrash(id)
    suspend fun restore(id: String) = repository.restore(id)
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit,
    vm: LibraryViewModel = hiltViewModel()) {
    val documents by vm.documents.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Document?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it.toString()) } }
    LaunchedEffect(vm) { vm.imported.collect { result -> onOpen(result.documentId) } }
    Scaffold(topBar = { TopAppBar(title = { Text("FoliKeep") }, actions = {
        TextButton(onClick = onSettings) { Text("设置") }
        Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*", "application/vnd.openxmlformats-officedocument.presentationml.presentation")) }, enabled = !busy,
            modifier = Modifier.padding(end = 20.dp)) { Text(if (busy) "正在导入…" else "导入资料") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }) { inset ->
        Column(Modifier.fillMaxSize().padding(inset).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("把阅读留在这里。", style = MaterialTheme.typography.headlineLarge)
            Text("本地书架 · ${documents.size} 份资料", modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, label = { Text("搜索文档标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val visible = documents.filter { it.title.contains(query, ignoreCase = true) }
            if (visible.isEmpty()) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(if (query.isBlank()) "你的第一本资料，从这里开始" else "没有匹配的文档", style = MaterialTheme.typography.titleLarge)
                    Text("支持 PDF 与图片 · 导入后可离线阅读", Modifier.padding(12.dp))
                    Text("PPTX 请先转换为 PDF", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyVerticalGrid(GridCells.Adaptive(200.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(visible, key = { it.id }) { document ->
                    OutlinedCard(onClick = { onOpen(document.id) }) {
                        AsyncImage(model = document.thumbnail, contentDescription = "${document.title}封面",
                            contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(180.dp).padding(12.dp))
                        Column(Modifier.padding(16.dp)) {
                            Text(document.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text("第 ${document.lastPageIndex+1} / ${document.pageCount} 页", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { pendingDelete = document }) { Text("移至回收站") }
                        }
                    }
                }
            }
        }
    }
    error?.let { message -> AlertDialog(onDismissRequest = { vm.error.value = null }, title = { Text("未能导入") },
        text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error.value = null }) { Text("知道了") } }) }
    pendingDelete?.let { document -> AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("移至回收站？") },
        text = { Text("资料的本地副本会保留，再次导入同一文件可恢复。") },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            pendingDelete = null
            scope.launch {
                vm.trash(document.id)
                if (snackbar.showSnackbar("已移至回收站", "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) vm.restore(document.id)
            }
        }) { Text("移入") } }) }
}
