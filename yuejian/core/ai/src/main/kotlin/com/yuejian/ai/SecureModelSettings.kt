package com.yuejian.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.yuejian.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.modelData by preferencesDataStore("model_secure_v1")
class SecureModelSettings(private val context: Context) : ModelSettings {
    private val name=stringPreferencesKey("name")
    private val url=stringPreferencesKey("baseUrl")
    private val text=stringPreferencesKey("textModel")
    private val image=stringPreferencesKey("imageModel")
    private val vision=booleanPreferencesKey("imageEnabled")
    private val streaming=booleanPreferencesKey("streaming")
    private val timeout=intPreferencesKey("timeout")
    private val encrypted=stringPreferencesKey("encryptedKey")
    private val ctxWin=intPreferencesKey("contextWindow")
    private val inBudget=intPreferencesKey("inputBudget")
    private val outBudget=intPreferencesKey("outputBudget")
    private val outputBudgetV2=booleanPreferencesKey("outputBudgetV2")
    private val imgBudget=intPreferencesKey("imageTokenBudget")
    private val thinkingKey=stringPreferencesKey("thinkingMode")
    private val priceIn=doublePreferencesKey("inputPrice")
    private val priceCached=doublePreferencesKey("cachedPrice")
    private val priceOut=doublePreferencesKey("outputPrice")
    private val limitTurn=doublePreferencesKey("turnLimitYuan")
    private val limitDaily=doublePreferencesKey("dailyLimitYuan")
    private val alias="yuejian.model.aes.v1"
    override val config = context.modelData.data.map { p -> ModelConfig(
        name=p[name]?:"自定义",baseUrl=p[url]?:"",textModel=p[text]?:"",imageModel=p[image]?:"",
        imageEnabled=p[vision]?:false,streaming=p[streaming]?:true,timeoutSeconds=p[timeout]?:90,
        keyPresent=!p[encrypted].isNullOrEmpty(),
        contextWindow=p[ctxWin]?:65536,inputBudget=p[inBudget]?:32768,
        outputBudget=if(p[outputBudgetV2]!=true && p[outBudget]==4096) 8192 else p[outBudget]?:8192,
        imageTokenBudget=p[imgBudget]?:8192,thinkingMode=p[thinkingKey]?.takeIf { it=="enabled" || it=="disabled" }?:"disabled",
        inputPrice=p[priceIn]?:0.0,cachedPrice=p[priceCached]?:0.0,outputPrice=p[priceOut]?:0.0,
        turnLimitYuan=p[limitTurn]?:0.0,dailyLimitYuan=p[limitDaily]?:0.0) }
    private fun key(): SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    override suspend fun save(config: ModelConfig,newKey: String?): Unit = withContext(Dispatchers.IO) {
        val endpoint=config.baseUrl.trim().trimEnd('/')
        val parsed=requireNotNull(endpoint.toHttpUrlOrNull()) { "请输入有效的 HTTPS Base URL" }
        require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.query==null && parsed.fragment==null) { "Base URL 必须使用 HTTPS，且不能包含密钥或查询参数" }
        require(!endpoint.endsWith("/chat/completions")) { "Base URL 不要包含 /chat/completions" }
        require(config.textModel.isNotBlank()) { "请输入文字模型 ID" }
        require(!config.imageEnabled || config.imageModel.isNotBlank()) { "请填写图片模型 ID" }
        val secret=newKey?.trim()?.takeIf { it.isNotEmpty() }
        require(secret==null || secret.none { it=='\n'||it=='\r' }) { "API Key 格式不正确" }
        val cipherText=secret?.let {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
            Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(it.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        }
        context.modelData.edit { p ->
            if (p[url] != endpoint) p.remove(encrypted) // Never send an old server's key to a new endpoint.
            p[name]=config.name.trim();p[url]=endpoint;p[text]=config.textModel.trim();p[image]=config.imageModel.trim()
            p[vision]=config.imageEnabled;p[streaming]=config.streaming;p[timeout]=config.timeoutSeconds.coerceIn(15,300)
            // 容量与价格：未知/0 表示未知或免费，展示端不得当作“确定为零”
            p[ctxWin]=config.contextWindow.coerceAtLeast(2048);p[inBudget]=config.inputBudget.coerceAtLeast(1024)
            p[outBudget]=config.outputBudget.coerceIn(256,32768);p[outputBudgetV2]=true;p[imgBudget]=config.imageTokenBudget.coerceAtLeast(0)
            p[thinkingKey]=config.thinkingMode;p[priceIn]=config.inputPrice;p[priceCached]=config.cachedPrice
            p[priceOut]=config.outputPrice;p[limitTurn]=config.turnLimitYuan;p[limitDaily]=config.dailyLimitYuan
            if(cipherText!=null)p[encrypted]=cipherText
        }
    }
    override suspend fun deleteKey() { context.modelData.edit { it.remove(encrypted) } }
    internal suspend fun apiKey(expectedUrl: String): String = withContext(Dispatchers.IO) {
        val p=context.modelData.data.first()
        check(p[url]==expectedUrl.trimEnd('/')) { "模型配置已变化，请重新确认后发送" }
        val stored=p[encrypted] ?: error("请先在设置中保存 API Key")
        try {
            val parts=stored.split(':',limit=2)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP))) }
            String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
        } catch (_: Exception) { error("本机密钥已失效，请重新输入 API Key") }
    }
}
