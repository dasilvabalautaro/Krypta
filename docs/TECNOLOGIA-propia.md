# Tecnología propia de Krypta

*5 oct 2026*

Este documento separa lo que Krypta toma de terceros de lo que construye por su cuenta. Las piezas
criptográficas y de red básicas son estándar y vienen de librerías conocidas. Lo propio es cómo se
combinan: el protocolo, la señalización sin servidor, el buzón y la forma de entregar mensajes en
Android.

## Lo que usamos de terceros (y está bien que así sea)

- **Red P2P:** go-libp2p, con DHT Kademlia, Circuit Relay v2, DCUtR, Noise/TLS y QUIC.
- **Criptografía básica:** X25519, AES-256-GCM, HMAC y ML-KEM, de las bibliotecas de Go y del JDK.
- **Almacenamiento:** SQLCipher para la base de datos y Android Keystore para guardar las claves.
- **Multimedia:** MediaCodec (Opus, AMR-WB, H.264), Camera2 y ZXing para el QR.
- **Infraestructura:** Caddy para TLS en el puerto 443 y los VPS de Vultr e InterServer.

La única primitiva escrita a mano es HKDF ([Hkdf.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/Hkdf.kt)),
y está fijada contra los vectores del RFC 5869 en
[HkdfTest](../p2p-signaling/src/test/java/chat/neto/krypta/p2p/HkdfTest.kt).

## Lo que es nuestro

### 1. Señalización sin servidor (rendezvous diario por pareja)

Cada pareja de contactos calcula un punto de encuentro con `HKDF(secreto_compartido, fecha)` y lo
publica y busca en la DHT. El punto cambia cada día y nadie que no tenga el secreto puede
adivinarlo. A medianoche UTC hay una ventana de solape de 2 horas. Esto reemplaza el mDNS, que solo
funciona en red local
([RendezvousService.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/RendezvousService.kt),
[PLAN-senalizacion-descentralizada.md](PLAN-senalizacion-descentralizada.md)).

### 2. El PeerID es la clave pública

El secreto de cada pareja es `S = X25519(mi identidad, PeerID del contacto)`. Por eso para agregar a
alguien basta su PeerID, sin contraseñas ni servidor de claves
([KeyExchange.kt](../core/src/main/java/chat/neto/krypta/core/KeyExchange.kt),
[Libp2pKeyExchange.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/Libp2pKeyExchange.kt)).
Encima de esto están el número de seguridad de 60 dígitos
([SafetyNumber.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/SafetyNumber.kt)) y la
verificación por QR.

### 3. Double ratchet "por épocas"

Es nuestra variante del Double Ratchet de Signal
([Ratchet.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/Ratchet.kt),
[DISENO-ratchet.md](DISENO-ratchet.md)):

- No hay roles de iniciador y respondedor. Signal los necesita porque tiene un servidor de
  prekeys, y nosotros no tenemos.
- El `lineage` permite recuperar una sesión rota sin servidor.
- `encrypt` y `decrypt` son funciones puras. El avance del ratchet se guarda en la misma
  transacción de Room que el mensaje.
- Además: padding por bandas para ocultar tamaños
  ([Padding.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/Padding.kt)), y cada capacidad
  del protocolo tiene su propia versión mínima.

### 4. Formato de sobres

`MessageEnvelope` ([MessageEnvelope.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/MessageEnvelope.kt))
tiene un tipo por función: texto, imagen, fragmentos de archivo, acuses de lectura, señales de
llamada, anuncio de capacidades (`V`) y respuestas (`Y`, que envuelve a cualquier otro tipo). Los
bytes exactos están en [ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md).

### 5. Buzón cifrado con aviso integrado

Vive en el nodo de infraestructura ([mailbox.go](../infra/node/mailbox.go),
[wake.go](../infra/node/wake.go), [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md)):

- Guarda mensajes para quien está desconectado, sin poder leerlos.
- Solo se borra un mensaje del buzón cuando el teléfono confirma que lo guardó.
- Tiene cuotas por remitente y límites de frecuencia.
- El buzón ciego usa etiquetas opacas en lugar del PeerID del destinatario.
- Un stream propio (`/krypta/wake/1.0.0`) avisa al teléfono al instante cuando le llega algo.
- Con varios nodos: si uno falla se deposita en el siguiente, y se recoge de todos.

### 6. Nodo de infraestructura propio

Un solo binario ([infra/node](../infra/node/)) hace de bootstrap, DHT, relay, buzón y aviso. Tiene
sus propios scripts de despliegue y monitoreo.

### 7. Llamadas de voz y video sin WebRTC

[CallService.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/CallService.kt):

- El audio y el video viajan por streams de libp2p (`/krypta/call`, `/krypta/video`) con un formato
  de tramas propio.
- La clave de cada llamada se negocia con aportes aleatorios de los dos lados.
- El códec se anuncia dentro del mismo stream.
- Si la red se satura, el video descarta cuadros hasta el siguiente keyframe, para que la voz no se
  corte.

### 8. Ajustes propios sobre libp2p

En [native-bridge/libp2p](../native-bridge/libp2p/):

- [gater.go](../native-bridge/libp2p/gater.go): solo acepta conexiones entrantes de contactos y
  nodos. Esto cierra la fuga de IP hacia extraños.
- [dial_ranker.go](../native-bridge/libp2p/dial_ranker.go) y
  [conn_prune.go](../native-bridge/libp2p/conn_prune.go): prefieren la conexión directa y cierran la
  conexión WebSocket sobrante sin cortar llamadas en curso.
- Lecturas con tamaño máximo y la dirección de relay (`/p2p-circuit`) anunciada a mano.

### 9. Archivos fragmentados y cifrado local

Los archivos viajan en trozos de 48 KiB. El receptor los guarda en disco mientras llegan
([DiskFileStore.kt](../app/src/main/java/chat/neto/krypta/data/DiskFileStore.kt)), así que
sobreviven si el proceso muere. Si un envío falla, se reenvía el archivo completo.
[FileVault.kt](../app/src/main/java/chat/neto/krypta/data/FileVault.kt) cifra los adjuntos guardados
en el teléfono.

### 10. Entrega confiable en Android

Para que los avisos lleguen aunque el sistema mate la app:

- un servicio en primer plano;
- [HeartbeatReceiver](../app/src/main/java/chat/neto/krypta/HeartbeatReceiver.kt) como red de
  seguridad;
- [IncomingNotifier](../app/src/main/java/chat/neto/krypta/IncomingNotifier.kt), que funciona
  aunque la app se haya reiniciado solo por una alarma;
- `WifiLock` contra las restricciones de algunos fabricantes.

### 11. Copia de seguridad `.krbk`

Un formato propio que guarda la identidad y los contactos, cifrado con PBKDF2 y AES-GCM
([IdentityBackup.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/IdentityBackup.kt)).

## Una advertencia

Lo propio es justo lo que todavía nadie de afuera ha revisado. El protocolo (puntos 2, 3, 4 y 5)
está preparado para una revisión externa, sobre el tag `revision-externa-1`
([SOLICITUD-revision-externa.md](SOLICITUD-revision-externa.md)). La solicitud al OTF (#23596) fue
rechazada en octubre de 2026 y se busca por otra vía. Hasta ahora
todo lo validó solo el equipo: revisiones internas
([REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md)), pruebas de propiedades,
fuzzing y una verificación independiente preparatoria. Por eso el README no presenta el secreto
hacia adelante (forward secrecy) como algo garantizado. Lo que Krypta no protege está en
[security-model.md](security-model.md).
