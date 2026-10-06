package com.yuejian.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.yuejian.database.ReaderDatabase
import com.yuejian.database.MIGRATION_1_2
import com.yuejian.database.MIGRATION_2_3
import com.yuejian.database.MIGRATION_3_4
import com.yuejian.database.MIGRATION_4_5
import com.yuejian.database.MIGRATION_5_6
import com.yuejian.database.MIGRATION_6_7
import com.yuejian.database.MIGRATION_7_8
import com.yuejian.database.MIGRATION_8_9
import com.yuejian.database.MIGRATION_9_10
import com.yuejian.database.MIGRATION_10_11
import com.yuejian.ai.*
import kotlinx.coroutines.*
import com.yuejian.files.*
import com.yuejian.model.*
import com.yuejian.pdf.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp class YuejianApplication : Application() {
    @Inject lateinit var pages: PageSource
    @Inject lateinit var annotations: AnnotationRepository
    @Inject lateinit var backup: BackupRepository
    override fun onCreate() {
        super.onCreate()
        PdfPlatform.initialize(this)
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            annotations.recoverInterrupted()
            (backup as? LocalBackup)?.recoverPending()
        }
    }
    override fun onTrimMemory(level: Int) { super.onTrimMemory(level); pages.trimMemory() }
    override fun onLowMemory() { super.onLowMemory(); pages.trimMemory() }
}
@Module @InstallIn(SingletonComponent::class)
object StorageModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): ReaderDatabase =
        Room.databaseBuilder(context, ReaderDatabase::class.java, "yuejian-v1.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11).build()
    @Provides @Singleton fun annotations(@ApplicationContext context: Context, db: ReaderDatabase): AnnotationRepository = LocalAnnotations(context,db)
    @Provides @Singleton fun backup(@ApplicationContext context: Context, db: ReaderDatabase,
        preferences: ReadingPreferences): BackupRepository = LocalBackup(context,db,preferences)
    @Provides @Singleton fun secureSettings(@ApplicationContext context: Context) = SecureModelSettings(context)
    @Provides fun modelSettings(settings: SecureModelSettings): ModelSettings = settings
    @Provides @Singleton fun provider(settings: SecureModelSettings): ModelProvider = OpenAiCompatibleProvider(settings)
    @Provides @Singleton fun cache() = PageBitmapCache()
    @Provides @Singleton fun localDocuments(@ApplicationContext context: Context, database: ReaderDatabase, cache: PageBitmapCache) =
        LocalDocuments(context, database, cache)
    @Provides fun documents(local: LocalDocuments): DocumentRepository = local
    @Provides fun pages(local: LocalDocuments): PageSource = local
    @Provides @Singleton fun preferences(@ApplicationContext context: Context): ReadingPreferences = LocalReadingPreferences(context)
}
