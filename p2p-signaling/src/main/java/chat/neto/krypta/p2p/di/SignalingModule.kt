package chat.neto.krypta.p2p.di

import chat.neto.krypta.core.Curve25519
import chat.neto.krypta.core.ISignalingService
import chat.neto.krypta.core.KeyExchange
import chat.neto.krypta.core.MessageCipher
import chat.neto.krypta.p2p.AesGcmMessageCipher
import chat.neto.krypta.nativebridge.BridgeCurve25519
import chat.neto.krypta.p2p.Libp2pKeyExchange
import chat.neto.krypta.p2p.SignalingService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SignalingModule {

    @Binds
    abstract fun bindSignalingService(impl: SignalingService): ISignalingService

    @Binds
    abstract fun bindMessageCipher(impl: AesGcmMessageCipher): MessageCipher

    @Binds
    abstract fun bindKeyExchange(impl: Libp2pKeyExchange): KeyExchange

    // X25519 efímero para el ratchet: lo pone el puente Go porque Android no trae `XDH`
    // hasta la API 33 y el minSdk es 30 (ver [Curve25519]).
    @Binds
    abstract fun bindCurve25519(impl: BridgeCurve25519): Curve25519
}
