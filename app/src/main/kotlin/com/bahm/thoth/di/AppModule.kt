package com.bahm.thoth.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.bahm.thoth.core.OutputSink
import com.bahm.thoth.inference.EngineSettings
import com.bahm.thoth.knowledge.ZimRepository
import com.bahm.thoth.knowledge.ZimSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.dataStore
    }

    /** :core writes perf/debug files via this; on Android that's the app's external files dir. */
    @Provides
    @Singleton
    fun provideOutputSink(@ApplicationContext context: Context): OutputSink =
        AndroidOutputSink(context)

    /** :core's SearchService/ThothTools read the ZIM through this interface. */
    @Provides
    @Singleton
    fun provideZimSource(impl: ZimRepository): ZimSource = impl

    /** Android leaves the inference backend at the library default (desktop passes Backend.GPU). */
    @Provides
    @Singleton
    fun provideEngineSettings(): EngineSettings = EngineSettings()
}
