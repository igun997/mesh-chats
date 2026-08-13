package com.meshchats.app.di

import com.meshchats.app.core.mesh.FakeMeshStateRepository
import com.meshchats.app.core.mesh.MeshStateRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /** Application-lifetime scope for repositories that observe radios. */
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MeshModule {

    /** Swap for the real transport-backed implementation once radios land. */
    @Binds
    @Singleton
    abstract fun bindMeshStateRepository(impl: FakeMeshStateRepository): MeshStateRepository
}
