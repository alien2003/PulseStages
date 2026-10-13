package io.github.alien2003.pulsestages.data.local.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.alien2003.pulsestages.data.local.db.ProcessedSessionDao
import io.github.alien2003.pulsestages.data.local.db.PulseStagesDatabase
import io.github.alien2003.pulsestages.data.local.settings.UserSettings
import io.github.alien2003.pulsestages.data.local.settings.UserSettingsSerializer
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataLocalModule {
    private const val SETTINGS_FILE_NAME = "user_settings.json"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): PulseStagesDatabase = Room.databaseBuilder(context, PulseStagesDatabase::class.java, PulseStagesDatabase.FILE_NAME).build()

    @Provides
    fun provideProcessedSessionDao(database: PulseStagesDatabase): ProcessedSessionDao = database.processedSessionDao()

    @Provides
    @Singleton
    fun provideUserSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<UserSettings> =
        DataStoreFactory.create(serializer = UserSettingsSerializer) {
            context.dataStoreFile(SETTINGS_FILE_NAME)
        }
}
