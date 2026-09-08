package com.transfer.flash.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Module
@InstallIn(SingletonComponent::class)
object FlashAppModule {

    @Provides
    @IoDispatcher
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @AppScope
    fun providesAppScope(
        @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    // ERROR-034: there was a `@Provides @Singleton fun chatRepository(): FlashChatRepository =
    // SampleFlashChatRepository()` here. Nothing injects FlashChatRepository — the real one is built
    // by DiscoveryEngineHolder and handed out through AppEngine.chats — so the binding was dead, but
    // it was a live landmine: the first future `@Inject` of FlashChatRepository would have silently
    // received fabricated sample conversations. Removed rather than repointed; add a binding here
    // only when a real implementation can be supplied.
}
