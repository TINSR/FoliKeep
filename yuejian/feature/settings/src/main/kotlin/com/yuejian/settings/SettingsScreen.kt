package com.yuejian.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import java.text.SimpleDateFormat
import java.util.Date
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yuejian.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel class SettingsViewModel @Inject constructor(
    private val preferences: ReadingPreferences, private val models: ModelSettings,
    private val provider: ModelProvider, private val backup: BackupRepository
) : ViewModel() {
    val dark=preferences.darkTheme.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    val scale=preferences.answerTextScale.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),1)
    val keepThinking=preferences.keepThinking.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),false)
    fun setKeepThinking(value: Boolean) { viewModelScope.launch { preferences.setKeepThinking(value) } }
    val config=models.config.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),ModelConfig())
    val busy=MutableStateFlow(false)
    val notice=MutableStateFlow<String?>(null)
    fun setDark(value: Boolean) { viewModelScope.launch { preferences.setDarkTheme(value) } }
    fun setScale(value: Int) { viewModelScope.launch { preferences.setAnswerTextScale(value) } }
    fun save(value: ModelConfig,key: String,test: Boolean=false) {
        if(busy.value)return
        busy.value=true;notice.value=null
        viewModelScope.launch {
            try {
                models.save(value,key.takeIf { it.isNotBlank() })
                notice.value=if(test)provider.testConnection(models.config.first()) else "配置已保存；API Key 仅加密保存在本机"
            } catch(e: CancellationException){throw e}
            catch(e: Exception){notice.value=e.message?.take(160) ?: "保存或连接失败，请重新输入密钥"}
            finally{busy.value=false}
        }
    }
    fun deleteKey() { viewModelScope.launch { models.deleteKey();notice.value="已删除本机 API Key" } }

    val backupStage = MutableStateFlow<String?>(null)
    val backupMessage = MutableStateFlow<String?>(null)
    val restorePlan = MutableStateFlow<RestorePlan?>(null)
    private var backupJob: Job? = null
    private var pendingRestoreUri: String? = null

    fun exportBackup(uri: String, appVersion: String) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupMessage.value = null
            try {
                val bytes = backup.exportBackup(uri, appVersion) { backupStage.value = it }
                backupMessage.value = "备份完成（${bytes / 1024} KB）。备份包含资料和对话，请妥善保存。"
            } catch (e: CancellationException) { backupMessage.value = "已取消备份"; throw e }
            catch (e: Exception) { backupMessage.value = "备份失败：${e.message?.take(160)}" }
            finally { backupStage.value = null }
        }
    }

    fun inspectRestore(uri: String) {
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            backupMessage.value = null
            try {
                backupStage.value = "正在预检备份包"
                val plan = backup.inspectBackup(uri)
                pendingRestoreUri = uri
                restorePlan.value = plan
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { backupMessage.value = "无法恢复该备份：${e.message?.take(160)}" }
            finally { backupStage.value = null }
        }
    }

    fun confirmRestore() {
        val uri = pendingRestoreUri ?: return
        restorePlan.value = null
        if (backupJob?.isActive == true) return
        backupJob = viewModelScope.launch {
            try {
                val result = backup.restoreBackup(uri)
                val tail = result.notes.joinToString(" ").ifBlank { "" }
                backupMessage.value = "恢复完成：书籍 ${result.documents}、标注 ${result.anchors}、对话 ${result.conversations}、消息 ${result.messages}；已存在跳过 ${result.skipped}。$tail"
            } catch (e: CancellationException) { backupMessage.value = "已取消恢复"; throw e }
            catch (e: Exception) { backupMessage.value = "恢复失败：${e.message?.take(160)}；原有资料未受影响" }
            finally { backupStage.value = null; pendingRestoreUri = null }
        }
    }

    fun dismissRestore() { restorePlan.value = null; pendingRestoreUri = null }
    fun cancelBackup() { backupJob?.cancel() }
}
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val dark by vm.dark.collectAsStateWithLifecycle()
    val scale by vm.scale.collectAsStateWithLifecycle()
    val keepThinking by vm.keepThinking.collectAsStateWithLifecycle()
    val saved by vm.config.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(ModelConfig()) }
    var key by remember { mutableStateOf("") } // Never save plaintext in SavedStateHandle/Bundle.
    var timeout by remember { mutableStateOf("90") }
    var ctxWindow by remember { mutableStateOf("65536") }
    var outLimit by remember { mutableStateOf("8192") }
    var priceIn by remember { mutableStateOf("") }
    var priceCached by remember { mutableStateOf("") }
    var priceOut by remember { mutableStateOf("") }
    var limitTurn by remember { mutableStateOf("") }
    var limitDaily by remember { mutableStateOf("") }
    LaunchedEffect(saved) {
        editing=saved;timeout=saved.timeoutSeconds.toString()
        ctxWindow=saved.contextWindow.toString();outLimit=saved.outputBudget.toString()
        priceIn=saved.inputPrice.takeIf { it>0 }?.toString() ?: ""
        priceCached=saved.cachedPrice.takeIf { it>0 }?.toString() ?: ""
        priceOut=saved.outputPrice.takeIf { it>0 }?.toString() ?: ""
        limitTurn=saved.turnLimitYuan.takeIf { it>0 }?.toString() ?: ""
        limitDaily=saved.dailyLimitYuan.takeIf { it>0 }?.toString() ?: ""
    }
    LaunchedEffect(busy,notice) { if(!busy && notice?.startsWith("配置已保存")==true)key="" }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val titles=listOf("阅读外观","模型接入","容量与费用","数据与隐私")
    val subtitles=listOf("主题、字号与思考记录","服务商、密钥与模型","上下文、输出与预算","备份恢复与本地存储")
    val scheme=MaterialTheme.colorScheme
    val typography=MaterialTheme.typography
    val built=editing.copy(timeoutSeconds=timeout.toIntOrNull()?:90,
        contextWindow=ctxWindow.toIntOrNull()?.coerceAtLeast(2048)?:editing.contextWindow,
        outputBudget=outLimit.toIntOrNull()?.coerceIn(256,32768)?:editing.outputBudget,
        inputPrice=priceIn.toDoubleOrNull()?:0.0,cachedPrice=priceCached.toDoubleOrNull()?:0.0,
        outputPrice=priceOut.toDoubleOrNull()?:0.0,
        turnLimitYuan=limitTurn.toDoubleOrNull()?:0.0,dailyLimitYuan=limitDaily.toDoubleOrNull()?:0.0)
    Scaffold(topBar={TopAppBar(title={Text("设置",style=typography.titleLarge)},
        navigationIcon={TextButton(onClick=onBack){Text("返回")}},
        actions={Text("FoliKeep",Modifier.padding(end=24.dp),style=typography.labelLarge,color=scheme.onSurfaceVariant)})}) { inset ->
        BoxWithConstraints(Modifier.padding(inset).fillMaxSize().imePadding()) {
            val wide=maxWidth>=840.dp
            Row(Modifier.fillMaxSize()) {
                if(wide) {
                    Column(Modifier.width(224.dp).fillMaxHeight().padding(horizontal=16.dp,vertical=24.dp),
                        verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("你的阅读空间",Modifier.padding(horizontal=12.dp,vertical=12.dp),
                            style=typography.titleMedium,fontWeight=FontWeight.SemiBold)
                        titles.forEachIndexed { index,title ->
                            Surface(onClick={section=index},shape=RoundedCornerShape(18.dp),
                                color=if(section==index)scheme.onSurface else scheme.surface,
                                contentColor=if(section==index)scheme.surface else scheme.onSurface,
                                modifier=Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                    Text(title,style=typography.titleSmall)
                                    Text(subtitles[index],style=typography.labelSmall,
                                        color=if(section==index)scheme.surface.copy(alpha=.72f) else scheme.onSurfaceVariant)
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text("资料与笔记保存在本机",Modifier.padding(12.dp),style=typography.labelSmall,color=scheme.onSurfaceVariant)
                    }
                    VerticalDivider(color=scheme.outlineVariant)
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if(!wide)Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        titles.forEachIndexed { index,title ->
                            FilterChip(selected=section==index,onClick={section=index},label={Text(title)},
                                colors=FilterChipDefaults.filterChipColors(selectedContainerColor=scheme.onSurface,
                                    selectedLabelColor=scheme.surface),shape=RoundedCornerShape(16.dp))
                        }
                    }
                    androidx.compose.runtime.key(section) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            .padding(horizontal=if(wide)28.dp else 16.dp,vertical=20.dp),
                            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            Column(Modifier.widthIn(max=840.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(titles[section],style=typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                                Text(subtitles[section],style=typography.bodyMedium,color=scheme.onSurfaceVariant)
                            }
                            when(section) {
                                0 -> {
                                    SettingsGroup("主题","选择阅读器界面的明暗风格") {
                                        Row(horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                                            ThemePreview("纯白",false,!dark,{vm.setDark(false)},Modifier.weight(1f))
                                            ThemePreview("纯黑",true,dark,{vm.setDark(true)},Modifier.weight(1f))
                                        }
                                    }
                                    SettingsGroup("回答显示","外观偏好修改后自动保存") {
                                        Row(verticalAlignment=Alignment.CenterVertically) {
                                            Text("回答字号",Modifier.weight(1f),style=typography.titleSmall)
                                            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                                                listOf(0 to "小",1 to "标准",2 to "大").forEach { (value,label) ->
                                                    FilterChip(selected=scale==value,onClick={vm.setScale(value)},label={Text(label)})
                                                }
                                            }
                                        }
                                        Text("同时作用于正文、公式、表格和代码，跟随系统字体缩放。",style=typography.bodySmall,color=scheme.onSurfaceVariant)
                                        HorizontalDivider(color=scheme.outlineVariant)
                                        SettingsToggle("保留思考记录","随回答保存在本机，重新打开可回看；不进入搜索与备份。关闭后仅本次查看有效。",
                                            keepThinking,vm::setKeepThinking)
                                    }
                                }
                                1 -> {
                                    SettingsGroup("接入你的 AI","选择预设填入接口，再配置自己的 API Key") {
                                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                            listOf("DeepSeek","GLM","MiMo 按量","MiMo 套餐","自定义").forEach { name ->
                                                val selected=if(name=="自定义")editing.name !in listOf("DeepSeek","GLM","MiMo 按量","MiMo 套餐") else editing.name==name
                                                FilterChip(selected=selected,enabled=!busy,onClick={
                                                    editing=when(name) {
                                                        "DeepSeek"->ModelConfig("DeepSeek","https://api.deepseek.com","deepseek-flash")
                                                        "GLM"->ModelConfig("GLM","https://open.bigmodel.cn/api/paas/v4","glm-5.3")
                                                        "MiMo 按量"->ModelConfig(name="MiMo 按量",baseUrl="https://api.xiaomimimo.com/v1",textModel="mimo-v2.6-pro",imageModel="mimo-v2.6-pro",imageEnabled=true)
                                                        "MiMo 套餐"->ModelConfig(name="MiMo 套餐",baseUrl="https://token-plan-cn.xiaomimimo.com/v1",textModel="mimo-v2.6-pro",imageModel="mimo-v2.6-pro",imageEnabled=true)
                                                        else->ModelConfig()
                                                    };key=""
                                                },label={Text(name)})
                                            }
                                        }
                                        OutlinedTextField(editing.name,{editing=editing.copy(name=it)},label={Text("提供商名称")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        OutlinedTextField(editing.baseUrl,{editing=editing.copy(baseUrl=it)},label={Text("HTTPS Base URL")},
                                            supportingText={Text("填写基础地址，不含 /chat/completions")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        OutlinedTextField(key,{key=it},label={Text(if(saved.keyPresent)"API Key · 已保存，留空保留" else "API Key")},
                                            visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        if(editing.name.startsWith("MiMo"))SettingsHint("按量与 Token Plan 使用不同地址和专属 Key，请按套餐页面填写。")
                                        if(saved.keyPresent)TextButton(enabled=!busy,onClick={key="";vm.deleteKey()}){Text("删除本机 API Key")}
                                    }
                                    SettingsGroup("模型与回答","更换接口地址时需要重新填写密钥") {
                                        OutlinedTextField(editing.textModel,{editing=editing.copy(textModel=it)},label={Text("文字模型 ID")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        SettingsToggle("图片模型","开启前请确认模型具备看图能力。",editing.imageEnabled,
                                            {editing=editing.copy(imageEnabled=it)},!busy)
                                        if(editing.imageEnabled)OutlinedTextField(editing.imageModel,{editing=editing.copy(imageModel=it)},label={Text("图片模型 ID")},
                                            supportingText={Text("可以与文字模型相同")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        HorizontalDivider(color=scheme.outlineVariant)
                                        val thinkingSupported=thinkingProvider(editing.baseUrl)!=null
                                        SettingsToggle("默认开启深度思考",if(thinkingSupported)"用于新建文字问答，思考也计入输出上限；图片与摘要请求关闭思考。"
                                            else "此开关目前适配 DeepSeek 与 MiMo 官方接口。",editing.thinkingMode=="enabled",
                                            {editing=editing.copy(thinkingMode=if(it)"enabled" else "disabled")},!busy&&thinkingSupported)
                                        SettingsToggle("流式回答","边生成边显示回答。",editing.streaming,{editing=editing.copy(streaming=it)},!busy)
                                        OutlinedTextField(timeout,{timeout=it.filter(Char::isDigit).take(3)},label={Text("读取超时 · 秒")},
                                            supportingText={Text("15–300 秒")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                    }
                                }
                                2 -> {
                                    SettingsGroup("模型容量","根据服务商公布的模型规格填写") {
                                        OutlinedTextField(ctxWindow,{ctxWindow=it.filter(Char::isDigit).take(6)},label={Text("上下文容量 · Token")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        OutlinedTextField(outLimit,{outLimit=it.filter(Char::isDigit).take(5)},label={Text("输出上限 · Token")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        SettingsHint("输出上限包含思考和最终回答，容量用于安排上下文预算。")
                                    }
                                    SettingsGroup("单价与预算","费用估算以服务商账单为准") {
                                        OutlinedTextField(priceIn,{priceIn=it.filter{c->c.isDigit()||c=='.'}.take(8)},label={Text("输入单价 · 元 / 百万 Token")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        OutlinedTextField(priceCached,{priceCached=it.filter{c->c.isDigit()||c=='.'}.take(8)},label={Text("缓存命中单价 · 元 / 百万 Token")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        OutlinedTextField(priceOut,{priceOut=it.filter{c->c.isDigit()||c=='.'}.take(8)},label={Text("输出单价 · 元 / 百万 Token")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        HorizontalDivider(color=scheme.outlineVariant)
                                        OutlinedTextField(limitTurn,{limitTurn=it.filter{c->c.isDigit()||c=='.'}.take(8)},label={Text("单次预算 · 元")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        OutlinedTextField(limitDaily,{limitDaily=it.filter{c->c.isDigit()||c=='.'}.take(8)},label={Text("每日预算 · 元")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy)
                                        Text("单价留空或填 0 时不显示零费用；预算填 0 表示不限。",style=typography.bodySmall,color=scheme.onSurfaceVariant)
                                    }
                                }
                                else -> {
                                    SettingsGroup("备份与恢复","将学习记录保存在你选择的位置") {
                                        Text("包含资料、标注、对话、卡片、草稿、阅读位置与外观偏好。恢复为追加导入，已有数据保留。",style=typography.bodyMedium)
                                        SettingsHint("不包含 API Key、模型配置和软删除的资料。")
                                        BackupSection(vm)
                                    }
                                    SettingsGroup("本地存储与隐私","由你决定何时向 AI 发送内容") {
                                        SettingsInfo("资料留在设备上","资料、高亮、卡片和完整问答保存在本机；卸载应用会删除本地资料。")
                                        HorizontalDivider(color=scheme.outlineVariant)
                                        SettingsInfo("仅发送本次所需内容","确认发送后，选区文字或圈选原图、当前问题与同处必要历史发送给所配置服务商。")
                                        HorizontalDivider(color=scheme.outlineVariant)
                                        SettingsInfo("密钥加密保存","API Key 使用 Android Keystore 保护，不写入数据库、日志或备份。")
                                        Text("文字提取使用 PDFBox-Android（Apache-2.0）。扫描资料可圈选问 AI，当前未启用 OCR。",style=typography.bodySmall,color=scheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if(section==1 || section==2) {
                        Surface(color=scheme.surface,shadowElevation=4.dp) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=if(wide)28.dp else 16.dp,vertical=12.dp),
                                horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                notice?.let { Text(it,Modifier.widthIn(max=840.dp).fillMaxWidth(),style=typography.bodySmall,color=scheme.onSurfaceVariant) }
                                Row(Modifier.widthIn(max=840.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    Button(enabled=!busy,onClick={vm.save(built,key)},modifier=Modifier.weight(1f)) { Text(if(busy)"正在处理…" else "保存配置") }
                                    OutlinedButton(enabled=!busy,onClick={vm.save(built,key,true)},modifier=Modifier.weight(1f)) { Text("保存并测试") }
                                }
                                Text("连接测试仅发送一条 OK 问题，可能计费；图片能力请通过圈选验证。",
                                    Modifier.widthIn(max=840.dp).fillMaxWidth(),style=typography.labelSmall,color=scheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.widthIn(max=840.dp).fillMaxWidth(),shape=RoundedCornerShape(24.dp),
        color=MaterialTheme.colorScheme.surface,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean=true) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(title,style=MaterialTheme.typography.titleSmall)
            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked=checked,onCheckedChange=onChange,enabled=enabled)
    }
}

@Composable
private fun SettingsInfo(title: String, text: String) {
    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Text(title,style=MaterialTheme.typography.titleSmall)
        Text(text,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsHint(text: String) {
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        Text(text,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ThemePreview(label: String, black: Boolean, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val bg=if(black)Color.Black else Color.White
    val fg=if(black)Color.White else Color.Black
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Surface(onClick=onClick,shape=RoundedCornerShape(18.dp),color=bg,contentColor=fg,
            border=BorderStroke(if(selected)2.dp else 1.dp,MaterialTheme.colorScheme.outline),modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.height(116.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text("Aa",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    if(selected)Text("✓",style=MaterialTheme.typography.titleMedium)
                }
                Box(Modifier.fillMaxWidth(.82f).height(4.dp).background(fg.copy(alpha=.25f),RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth().height(4.dp).background(fg.copy(alpha=.15f),RoundedCornerShape(2.dp)))
                Box(Modifier.fillMaxWidth(.65f).height(4.dp).background(fg.copy(alpha=.15f),RoundedCornerShape(2.dp)))
            }
        }
        Text(label,style=MaterialTheme.typography.labelLarge,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun BackupSection(vm: SettingsViewModel) {
    val stage by vm.backupStage.collectAsStateWithLifecycle()
    val message by vm.backupMessage.collectAsStateWithLifecycle()
    val plan by vm.restorePlan.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0.2.9"
    }
    val dateTag = remember { SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault()).format(Date()) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { vm.exportBackup(it.toString(), appVersion) }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.inspectRestore(it.toString()) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = stage == null, onClick = { exportLauncher.launch("FoliKeep备份-$dateTag.yuejian.zip") }) { Text("导出备份") }
        OutlinedButton(enabled = stage == null, onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) { Text("恢复备份") }
        if (stage != null) TextButton(onClick = vm::cancelBackup) { Text("取消") }
    }
    stage?.let { Text("正在进行：$it…", style = MaterialTheme.typography.bodySmall) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    plan?.let { p ->
        AlertDialog(onDismissRequest = vm::dismissRestore, title = { Text("确认追加恢复") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("格式 v${p.formatVersion} · 来自 ${p.appVersion}")
                Text("书籍 ${p.documents} · 页面 ${p.pages} · 标注 ${p.anchors} · 对话 ${p.conversations} · 消息 ${p.messages} · 图片 ${p.assets}")
                Text("预计占用约 ${p.totalBytes / 1024 / 1024} MB")
                p.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("开始恢复") } },
            dismissButton = { TextButton(onClick = vm::dismissRestore) { Text("取消") } })
    }
}
