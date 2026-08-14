package com.meshchats.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.meshchats.app.data.local.MeshDatabase
import com.meshchats.app.data.local.MessageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MeshDatabase =
        Room.databaseBuilder(context, MeshDatabase::class.java, MeshDatabase.NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideMessageDao(database: MeshDatabase): MessageDao = database.messageDao()

    @Provides
    @Singleton
    fun providePreferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupt preferences file must never take the app down on launch.
            // Reset to empty on a CorruptionException so callers fall back to
            // their defaults (e.g. BLE discovery re-enables) instead of crashing.
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        ) {
            context.preferencesDataStoreFile("mesh_chats_prefs")
        }
}
