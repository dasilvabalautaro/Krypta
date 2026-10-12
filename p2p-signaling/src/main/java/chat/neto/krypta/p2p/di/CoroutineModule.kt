package chat.neto.krypta.p2p.di

import chat.neto.krypta.p2p.CpuContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {

    /** Scope de aplicación para trabajo en background que sobrevive a las pantallas. */
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** CPU del dominio (cifrado, ratchet) fuera del hilo de quien llama. Ver [CpuContext]. */
    @Provides
    @Singleton
    fun provideCpuContext(): CpuContext = CpuContext(Dispatchers.Default)
}
