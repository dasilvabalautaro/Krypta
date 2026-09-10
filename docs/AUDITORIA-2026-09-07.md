# Auditoría de arquitectura y código — Krypta

**Fecha:** 7 de septiembre de 2026
**Rama auditada:** `feat/avisos-gif-capturas-1.5` (limpia, sobre `deb0bf8`)
**Alcance:** los cinco módulos Gradle, el puente Go (`native-bridge/libp2p`), el nodo de
infraestructura (`infra/node`) y la coherencia entre lo que los documentos de objetivos
prometen y lo que el código hace.

---

## 1. Método

Lectura completa de: `:core` (interfaces y modelos), `:p2p-signaling` (`ChatService`,
`CallService`, cripto, sobres, respaldo), `:data` (entidades, DAO, migraciones, DI),
`:native-bridge` (`Libp2pNode` + `bridge.go`), `infra/node` (`main.go`, `mailbox.go`,
`wake.go`) y los puntos de entrada de `:app` (Application, FGS, receivers, notificaciones,
`ChatViewModel`, `DiskFileStore`, manifiesto). Lectura en diagonal de la UI Compose.

Comprobaciones ejecutadas:

| Comprobación | Resultado |
|---|---|
| `./gradlew testDebugUnitTest` | ✅ verde — 116 tests (82 en `:p2p-signaling`, 34 en `:app`) |
| `go test ./...` en `native-bridge/libp2p` | ✅ verde |
| `go test ./...` en `infra/node` | ✅ verde |
| `go vet ./...` en `native-bridge/libp2p` | ✅ sin avisos |
| Tests en `:core`, `:data`, `:native-bridge` | ⚠️ **cero** (ver §7) |

No se ejecutaron pruebas instrumentadas: borran la identidad del teléfono del autor
(CLAUDE.md lo advierte y la memoria del proyecto también).

---

## 2. Veredicto

Krypta hace lo que dice hacer. El lazo completo —identidad Ed25519 → ECDH X25519 →
AES-256-GCM → libp2p (DHT + rendezvous + relay v2 + DCUtR) → buzón E2EE → wake → aviso—
está implementado de verdad, no simulado, y está sostenido por tests que en varios puntos
nacieron de fallos reales en producción (pérdida de trozos de archivo, avisos que no
sonaban, ciclos WAN colgados). La calidad de la ingeniería de fiabilidad —ack-tras-persistir,
staging en disco, lecturas acotadas, presupuestos por paso, gancho directo de aviso— está
por encima de lo habitual en un proyecto de este tamaño, y la documentación (CLAUDE.md,
`architecture.md`, `PRUEBAS-PENDIENTES.md`, `PLAY-STORE.md`) es un activo real: registra
incluso las omisiones deliberadas.

Lo que la auditoría encuentra no son grietas en ese lazo, sino tres cosas:

1. **Un defecto de fuga que además erosiona una propiedad de privacidad declarada**
   (A-1): cada ciclo WAN deja una goroutine de anuncio viva para siempre, así que los
   rendezvous de días pasados se siguen publicando en la DHT indefinidamente. La rotación
   diaria —el corazón del "Riesgo 1" del plan— deja de acotar la correlación.
2. **Un cuello de botella de cliente que crece con el uso** (A-2): la conversación entera
   se descifra en el hilo principal, y además se vuelve a descifrar en cada recomposición
   (es decir, en cada pulsación de tecla).
3. **La Fase 6 (endurecimiento) está esencialmente sin hacer**: sin anti-abuso en nodo,
   sin análisis de metadatos y sin `docs/security-model.md`, que era un entregable
   explícito. Parte de esto ya está fichado en `PLAY-STORE.md` §Infraestructura; esta
   auditoría lo confirma y añade lo que faltaba.

Ninguno de los hallazgos rompe el cifrado ni permite a un tercero leer mensajes.

---

## 3. Objetivos declarados vs. estado real

Contrastado contra `docs/PLAN-senalizacion-descentralizada.md`.

| Fase | Objetivo | Estado |
|---|---|---|
| **0** — Spike (gate go/no-go) | AAR gomobile, host real, rendezvous en DHT, mensaje por stream, E2EE | ✅ todo hecho y verificado en vivo |
| | **Métricas del gate**: 2 SIM de operadoras distintas, % DCUtR vs relay, latencia de establecimiento | ❌ **nunca medido**. El gate que "valida toda la arquitectura" sigue abierto (fichado en `PRUEBAS-PENDIENTES`) |
| **1** — Infra | bootstrap + DHT server + Relay v2 | ✅ |
| | Buzón con TTL, cuotas **y rate-limit** | ⚠️ TTL y cuotas sí; **rate-limit no** |
| | wake-server UnifiedPush separado | 🔄 sustituido por wake integrado en el nodo (desviación acordada y mejor: sin terceros) |
| | ~5 nodos fijos con IP estática, `infra/deploy` (terraform/ansible) | ⚠️ 3 nodos, dos de ellos máquinas domésticas tras Cloudflare; `infra/deploy` no existe (lo cubren `deploy-vps.sh`/`deploy-catalina.sh`, razonable a esta escala) |
| **2** — Capa nativa | nodo client, `Libp2pNode`, FGS, exención de batería, reconexión | ✅ y endurecido más allá del plan (WifiLock, latido, BootReceiver, callback de red) |
| **3** — Rendezvous | `HKDF(secreto, día)`, anunciar y buscar | ✅ |
| | **ventana de solape al cambiar de día** | ❌ no implementada (ver A-8) |
| | Señalización SDP/ICE para llamadas | 🔄 descartado: no hay WebRTC (Opción A, decisión de 4 jul) |
| **4** — Buzón + mensajería | directo → buzón, estados PENDING→SENT→DELIVERED→READ | ✅ |
| | **Reintento/reconciliación con WorkManager** | ❌ no existe; WorkManager está en el catálogo de versiones y **no se usa en ningún sitio** (ver A-7) |
| **5** — Wake E2E | depósito → aviso → el móvil retira y descifra | ✅ verificado en vivo con dos teléfonos |
| **6** — Endurecimiento | anti-spam/abuso en relay, buzón y wake | ❌ nada (relay con `WithInfiniteLimits`, buzón sin origen restringido, wake sin tope de suscriptores) |
| | Análisis de metadatos y mitigaciones | ❌ no hecho |
| | `docs/security-model.md` | ❌ **el archivo no existe** |
| | Marcar mDNS como opcional en LAN | ✅ (en código y en docs) |
| **7** — Llamadas | 7a gate de latencia, 7b voz, 7c vídeo | ✅ implementadas y verificadas en vivo |
| | 7d endurecimiento | ⚠️ parcial: proximidad, tipo de FGS y flip de cámara sí; adaptación de bitrate y Telecom/ConnectionService no |
| **8** — Datos locales | vaciar chat, eliminar contacto | ✅ |
| **Extra** — no estaba en el plan | verificación anti-MITM, respaldo `.krbk`, bloqueo de app, bloqueo de contacto, FLAG_SECURE, ayuda en app, multi-nodo | ✅ todo entregado |

**Lectura**: el plan se ha cumplido con creces en producto y se ha quedado corto en
**endurecimiento e infraestructura**, que es exactamente donde el propio plan situaba los
riesgos abiertos. Las desviaciones (wake integrado, sin WebRTC, sin UnifiedPush) están
razonadas y documentadas; las omisiones (rate-limit, ventana de solape, WorkManager,
`security-model.md`) no lo están en ningún sitio.

---

## 4. Arquitectura

### 4.1 Lo que está bien

- **La separación en módulos se sostiene.** El grafo declarado (`:app → :core, :data,
  :p2p-signaling, :native-bridge`) es el real; `:core` no tiene Android ni DI y las
  interfaces (`ISignalingService`, `MessageRepository`, `ContactRepository`, `FileStore`,
  `MessageCipher`, `KeyExchange`, `AudioEngine`, `CallStream`) permiten que
  `ChatServiceTest` y `CallServiceTest` corran en JVM con dobles — 82 tests que ejercitan
  el dominio completo sin dispositivo. Eso es DI bien invertida donde importa.
- **La frontera Kotlin↔Go está bien elegida.** Go se queda con transporte y framing; el
  dominio (sobres, estados, cifrado de aplicación) vive en Kotlin. El puente respeta las
  restricciones de gomobile y las decisiones difíciles (`WithAllowLimitedConn`,
  `AddrsFactory` de circuito, `ForceReachabilityPublic` en el nodo) están explicadas en
  el propio código.
- **El camino de recepción es correcto por construcción**: el buzón entrega **síncrono** y
  solo se ack'ea lo persistido; el aviso al usuario cuelga de un gancho directo
  (`setIncomingNotifier`) y no de un `SharedFlow` que puede no tener suscriptor.

### 4.2 Desviaciones estructurales

**A-13 (Bajo) — `:app` depende de clases concretas, no de interfaces de `:core`.**
`ChatService` y `CallService` se inyectan directamente en `ChatViewModel`,
`KryptaForegroundService`, `HeartbeatReceiver`, `CallActionReceiver` e `IncomingNotifier`.
El plan pedía "interfaces en `:core` para DI y tests". Consecuencia práctica: `:app` no es
testable en JVM sin arrastrar `:p2p-signaling`, y de hecho sus 34 tests solo cubren piezas
hoja (`AppLock`, `ThemePreference`, `HelpContent`, `MessageText`, `QrCode`,
`DiskFileStore`). No hay un solo test de `ChatViewModel`, que es donde vive la lógica de
presentación (mapeo de mensajes, citas, permisos, envío de medios).

**A-14 (Medio) — `ChatService` es una clase-Dios.** 1.012 líneas, 8 dependencias, y al
menos siete responsabilidades independientes: bucle de conectividad WAN, rendezvous,
retirada de buzón, envío/reintento, troceado de archivos, recepción y ramificación de
sobres, acuses de lectura, CRUD de contactos, bloqueo y registro de diagnóstico. Está
legible porque está muy comentada, no porque esté acotada. Cada función nueva la ensancha:
bloquear contactos tocó cuatro puntos distintos de esta misma clase. Sugerencia de corte
natural, sin cambiar el comportamiento: `WanLoop` (ciclo, presupuestos, estado, kick),
`FileTransfer` (trocear, meta, reensamblado), `ContactService` (alta, verificación,
bloqueo, borrado), dejando `ChatService` como el lazo cifrar→persistir→enviar/recibir.
Lo mismo, en menor grado, para `ui/ChatScreens.kt` (1.825 líneas con lista de
conversaciones, chat, burbujas, popup de acciones, hoja de adjuntos y diálogos).

**Nota de contexto:** ninguna de las dos es urgente. Son deuda de mantenibilidad, y en un
proyecto de un solo autor con la documentación que este tiene, el coste real es menor que
en un equipo. Se listan porque el objetivo declarado del plan es explícitamente "mantener
arquitectura SOLID".

---

## 5. Hallazgos de código

Ordenados por severidad. Cada uno con el sitio exacto, el impacto y la corrección sugerida.

### A-1 (Alto) — Fuga de goroutines y publicación indefinida de rendezvous caducados

**Dónde:** [native-bridge/libp2p/bridge.go:1043](../native-bridge/libp2p/bridge.go#L1043) →
llamado desde [p2p-signaling/…/ChatService.kt:411](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/ChatService.kt#L411).

```go
func (n *Node) Advertise(rendezvous string) {
	dutil.Advertise(n.ctx, n.disc, rendezvous)
}
```

`dutil.Advertise` **no es un anuncio puntual**: lanza una goroutine que re-anuncia en bucle
hasta que su contexto muere. El contexto que recibe es `n.ctx`, que vive lo que vive el
nodo. Y `ChatService.announceAndFind()` lo llama **una vez por contacto en cada ciclo WAN**
(cada 30 s sin wake, cada 180 s con wake).

Consecuencias, las tres reales:

1. **Fuga de goroutines sin techo.** Con 3 contactos y el bucle ágil: 6 goroutines/minuto,
   ~8.600 al día, todas vivas mientras el proceso viva.
2. **Tráfico de DHT creciente.** Cada una de esas goroutines republica su clave a ~7/8 del
   TTL del provider. La cuenta de `Provide` por hora crece linealmente con el tiempo de
   funcionamiento — batería y datos, justo lo que el bucle adaptativo intentaba ahorrar.
3. **Erosiona el Riesgo 1 del plan.** El rendezvous del lunes se sigue publicando el
   martes, el miércoles y el mes que viene. La rotación diaria existe para que un
   observador que rastree la DHT no pueda correlacionar a largo plazo; con esto, un
   PeerID acumula una lista creciente de rendezvous simultáneamente activos, que es
   precisamente la huella que la rotación debía impedir.

**Corrección:** usar el anuncio de una sola pasada, que es lo que el diseño ya asume porque
el bucle WAN reanuncia por su cuenta:

```go
func (n *Node) Advertise(rendezvous string) {
	ctx, cancel := context.WithTimeout(n.ctx, 30*time.Second)
	defer cancel()
	_, _ = n.disc.Advertise(ctx, rendezvous)   // discovery.Advertiser, una pasada
}
```

Alternativa si se prefiere el re-anuncio persistente: mantener un `map[string]context.CancelFunc`
por clave de rendezvous y cancelar la del día anterior al rotar. La primera opción es más
simple y encaja con el bucle que ya existe. Requiere regenerar el AAR (`build-aar.sh`).

**Test que lo fijaría:** llamar `Advertise` N veces sobre el mismo nodo y comprobar que
`runtime.NumGoroutine()` no crece proporcionalmente a N.

### A-2 (Alto) — El historial entero se descifra en el hilo principal, y otra vez en cada recomposición

**Dónde:** [app/…/ChatViewModel.kt:274](../app/src/main/java/chat/neto/krypta/ui/ChatViewModel.kt#L274),
[app/…/ChatScreens.kt:342](../app/src/main/java/chat/neto/krypta/ui/ChatScreens.kt#L342),
[data/…/MessageDao.kt:18](../data/src/main/java/chat/neto/krypta/data/dao/MessageDao.kt#L18).

Tres problemas encadenados en una sola línea de UI:

```kotlin
val messages by viewModel.messages(contact).collectAsState(initial = emptyList())
```

1. **Flujo nuevo en cada recomposición.** `messages(contact)` construye un `Flow` nuevo cada
   vez. `collectAsState` está keyed por la instancia del flujo, así que **reinicia la
   colección en cada recomposición** y el estado vuelve a `emptyList` mientras Room reemite.
   `ChatScreen` recompone al escribir (lee `draftState.text`), así que **cada pulsación de
   tecla vuelve a descifrar la conversación completa**.
2. **Descifrado en el hilo principal.** El `.map { }` del ViewModel no lleva `flowOn`, así
   que se ejecuta en el contexto del colector, que es Main. Por cada mensaje: una derivación
   HKDF (`AesGcmMessageCipher.sessionKey` la recalcula en *cada* `encrypt`/`decrypt`,
   [AesGcmMessageCipher.kt:42](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/AesGcmMessageCipher.kt#L42))
   más un AES-GCM, más el decodificado del sobre.
3. **Sin paginación.** `observeConversation` es `SELECT * … ORDER BY timestamp ASC`: la
   conversación completa, y con ella **todos los JPEG en línea del chat residentes en
   memoria** (`MessageContent.Image` lleva los bytes).

Hoy no se nota porque los chats de prueba son cortos. Con 2.000 mensajes y unas cuantas
fotos, abrir el chat y escribir será jank garantizado y candidato a ANR y a OOM.

**Corrección**, por orden de coste/beneficio:

- `val flow = remember(contact.id) { viewModel.messages(contact) }` — arregla el reinicio
  por recomposición con una línea.
- `.flowOn(Dispatchers.Default)` en el `map` del ViewModel — saca el cripto de Main.
- Cachear la clave de sesión por contacto en `AesGcmMessageCipher` (`Map<ByteArrayKey,
  SecretKeySpec>`): la HKDF es determinista y hoy se recalcula por mensaje.
- Cachear el `DecodedMessage` por `id` (el ciphertext es inmutable: descifrar una vez por
  mensaje y no una vez por emisión).
- Paginar: `observeConversation(conversationId, limit, offset)` o `PagingSource`, y no
  cargar los bytes de imagen hasta que la burbuja entre en pantalla.

### A-3 (Alto) — La recepción de archivos no tiene tope de tamaño ni recolección de basura

**Dónde:** [app/…/DiskFileStore.kt:37-49](../app/src/main/java/chat/neto/krypta/data/DiskFileStore.kt#L37).

El emisor se limita (`MAX_FILE_BYTES` 8 MB, `MAX_ANIMATION_BYTES` 4 MB en `ChatViewModel`),
pero **el receptor no valida nada**:

- `onMeta` acepta cualquier `totalChunks` y cualquier `size`; no se contrastan entre sí.
- `onChunk` acepta cualquier `index ≥ 0`, aunque sea mayor que `totalChunks`, y escribe el
  trozo en disco antes de saber si la meta existe.
- No hay tope agregado del directorio de staging.
- **Las transferencias que nunca se completan no se limpian nunca.** Si el emisor
  desaparece a mitad, `krypta_files/staging/<fileId>/` se queda ahí para siempre. Con el
  camino de GIF (archivos de MB por el buzón) esto acumula basura de forma silenciosa.

El atacante tiene que ser un contacto aceptado (`onReceived` resuelve el contacto por PeerID
y descarta al desconocido antes de tocar el `FileStore`), lo que baja la severidad, pero
"un contacto puede llenarte el almacenamiento del teléfono" sigue siendo un fallo, y el
caso accidental —un cliente con un bug enviando `totalChunks` disparatado— es igual de real.

**Corrección:** rechazar en `onMeta` una `size` mayor que el tope de envío o un
`totalChunks` incoherente con ella; ignorar en `onChunk` los índices fuera de rango;
barrido de directorios de staging con `mtime` anterior a N horas (el mismo patrón que
`mailbox.sweep()` en el nodo).

### A-4 (Medio-Alto) — Identidad y base de datos sin cifrar en reposo

> **Resuelto el 8-9 sep 2026**: identidad envuelta por el Keystore, base entera con SQLCipher y
> adjuntos cifrados (`FileVault`). Lo que sigue es el diagnóstico original.

**Dónde:** [native-bridge/…/Libp2pNode.kt:40-45](../native-bridge/src/main/java/chat/neto/krypta/nativebridge/Libp2pNode.kt#L40)
(`// TODO: cifrar en reposo`), `DataModule.provideDatabase` (Room sin SQLCipher),
`krypta_files/` (adjuntos en claro).

La identidad Ed25519 es la raíz de **todo**: de ella se derivan por ECDH los secretos
compartidos de todos los contactos y, por tanto, las claves de todos los mensajes
almacenados. Hoy vive en Base64 dentro de un `SharedPreferences` en claro, junto a
`krypta.db` (ciphertext de todos los mensajes) y los adjuntos. Quien consiga leer el
directorio de datos de la app —dispositivo rooteado, extracción forense, malware con
escalada— obtiene la identidad, y con ella descifra todo el histórico **y suplanta al
usuario indefinidamente** (el PeerID es la identidad; no hay revocación).

Lo que ya mitiga: `allowBackup="false"` (bien hecho, y el razonamiento está documentado),
almacenamiento privado de la app, y `AppLock` — que, importante, **no cifra nada**: es una
puerta de UI, no una barrera criptográfica.

**Corrección** (en orden de esfuerzo): envolver la identidad con una clave del **Android
Keystore** (`setUserAuthenticationRequired` cuando AppLock está activo, `StrongBox` si el
dispositivo lo tiene) de modo que no salga nunca en claro del TEE; y/o SQLCipher para Room
con la clave también en Keystore. Es el hueco más grande que queda entre lo que la política
de privacidad promete al usuario ("tu identidad se guarda únicamente en tu dispositivo") y
lo que un atacante con acceso físico o root puede hacer.

### A-5 (Medio) — Sin secreto hacia adelante (PFS)

> **Resuelto en código el 9-10 sep 2026** (ver [DISENO-ratchet.md](DISENO-ratchet.md)): doble
> ratchet por épocas, envío encendido, clave de llamada negociada y adjuntos cifrados en reposo.
> Queda deber la prueba con dos móviles reales antes de contarlo como garantía en la
> documentación de cara al usuario. Lo que sigue es el diagnóstico original.

**Dónde:** [AesGcmMessageCipher.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/AesGcmMessageCipher.kt),
que lo admite en su propio KDoc.

La clave de un contacto es `HKDF(ECDH(identidad_propia, PeerID_contacto), "krypta-msg-key-v1")`:
**estática mientras dure la identidad**, sin ratchet ni claves efímeras. Consecuencias:

- Comprometer la identidad hoy descifra **todo el pasado** (incluido lo que un observador
  hubiera capturado del relay o lo que quedara en el buzón hasta 7 días).
- La clave por llamada `HKDF(secreto, callId)` mejora la separación entre llamadas pero
  hereda el mismo problema: se deriva del mismo secreto estático.
- No hay separación por dirección: ambos extremos cifran con la misma clave. Con nonce
  aleatorio de 96 bits el riesgo de colisión es despreciable a este volumen, así que es una
  observación de diseño, no un fallo explotable.

**No es un defecto de implementación** —está decidido y anotado— pero **sí es una promesa
que el usuario puede malinterpretar**: la §9 de la política dice "AES-256-GCM con claves
derivadas por X25519/HKDF", que es cierto, y un usuario que compare con Signal asumirá
ratchet. Recomendación mínima e inmediata: decirlo con todas las letras en la ayuda en app
y en la política ("si alguien obtiene tu dispositivo y tu identidad, puede descifrar el
historial que tenga guardado"). Recomendación de fondo: Noise/doble-ratchet como fase
futura, que es trabajo grande y no urgente para el modelo de amenaza actual.

### A-6 (Medio) — Fase 6: no existe `docs/security-model.md` ni análisis de metadatos

El plan lo pedía dos veces (estructura de `docs/` y Fase 6). No está. Y el análisis que
debería contener tiene un contenido concreto y no trivial que hoy no está escrito en
ninguna parte:

- **El rendezvous protege la DHT, pero el buzón no lo usa.** Un depósito lleva `to` (PeerID
  del destinatario) y el nodo le pone `from` (PeerID autenticado del emisor) y `ts`. Eso es
  el **grafo social y el timing en claro**, guardado en disco del nodo hasta 7 días o hasta
  la retirada. La propiedad "la DHT nunca ve identificadores reales" se cumple; la propiedad
  implícita "nadie ve quién habla con quién" **no**, en el camino offline.
- **El stream de wake revela presencia**: el nodo sabe qué PeerIDs están conectados y
  cuándo, en tiempo real.
- **Concentración de operador**: los tres nodos de `DEFAULT_BOOTSTRAP` los opera la misma
  persona. Es "descentralización de confianza, no de infraestructura" tal como se decidió,
  pero significa que un solo operador ve el grafo y el timing de todos los usuarios. El
  campo de bootstrap es editable (bien), pero no hay ninguna guía para que un tercero
  levante un nodo y lo use.

Nada de esto exige cambiar código ahora. Exige **escribirlo**, porque es la diferencia
entre una limitación conocida y una promesa incumplida.

### A-7 (Medio) — Sin reconciliación automática de envíos fallidos

El plan (Fase 4) pedía "reintento/reconciliación con WorkManager al recuperar conexión".
WorkManager está declarado en [gradle/libs.versions.toml](../gradle/libs.versions.toml)
y **no se usa en ninguna línea del proyecto**. Un mensaje que falla por las dos vías
(sin ruta al contacto **y** todos los nodos caídos) queda `FAILED` y **ahí se queda**: la
única recuperación es que el usuario se dé cuenta y toque la burbuja
(`ChatService.retry`). Al recuperar la conexión no pasa nada.

**Corrección barata:** en `wanCycle`, tras conectar, reintentar los `FAILED` de las últimas
N horas con un tope por ciclo. El `retry` ya existe, reusa el ciphertext y no duplica id;
falta solo quien lo dispare. (Y quitar WorkManager del catálogo si no se va a usar.)

### A-8 (Medio) — Falta la ventana de solape en el cambio de día del rendezvous

[RendezvousService.kt](../p2p-signaling/src/main/java/chat/neto/krypta/p2p/RendezvousService.kt)
solo calcula la clave de **hoy** (UTC), y `announceAndFind` solo anuncia y busca esa. El
plan pedía explícitamente "rotación diaria **+ ventana de solape al cambiar de día**".

Hoy el fallo está enmascarado por A-1: como los anuncios viejos nunca mueren, la clave de
ayer sigue publicada. **Al corregir A-1 aparecerá un agujero real de descubrimiento** cada
medianoche UTC (dos móviles que roten con unos segundos de diferencia, o uno que estuviera
apagado, no se encuentran hasta que ambos anuncien la clave nueva).

**Corrección:** anunciar y buscar `hoy` y, durante las primeras horas del día, también
`ayer`. Corregir A-1 y A-8 **en el mismo cambio**.

### A-9 (Bajo) — El id de mensaje lo elige el emisor y es clave primaria global

`MessageDao.upsert` es `OnConflictStrategy.REPLACE` y la PK es el `id` que viene **dentro
del sobre del emisor**. `ChatService.onReceived` solo comprueba que no se pise un mensaje
propio (`existing.senderId == SELF`); no comprueba que la fila existente pertenezca a esa
conversación. Lo mismo en `markOutgoingRead`: un acuse de lectura marca cualquier id
saliente, sin exigir que sea de la conversación de quien lo envía.

Explotarlo exige **conocer un UUIDv4 ajeno**, así que es impracticable — pero la defensa
cuesta una condición:

```kotlin
if (existing != null && existing.conversationId != contact.id) return null
```

y filtrar en `markOutgoingRead` por `m.conversationId == contact.id`.

### A-10 (Bajo) — `node.key` (identidad privada del nodo) no está en `.gitignore`

El README de `infra/node` indica ejecutar `go run .` en ese directorio, y el flag `-key`
tiene por defecto `node.key` **relativo**, así que se crea `infra/node/node.key`. Ese
archivo es la clave privada del nodo y **no está ignorado** (`.gitignore` ignora
`/infra/node/node`, que es el binario, y `/infra/node/dist/`). Un `git add -A` lo publica.
Ahora mismo no existe en el árbol ni está trackeado, así que es riesgo latente, no
incidente.

**Corrección:** añadir a `.gitignore`:

```
/infra/node/*.key
/infra/node/mailbox/
```

(el segundo por el mismo motivo: el buzón por defecto se crea junto al `node.key` y contiene
sobres de usuarios).

### A-11 (Bajo) — El nodo no tiene ninguna capa anti-abuso

Confirma y amplía lo ya fichado en `PLAY-STORE.md` §Infraestructura:

- **Relay abierto** (`WithInfiniteLimits`): ancho de banda gratis e ilimitado para cualquier
  peer libp2p de internet, no solo para Krypta. *(ya fichado)*
- **Buzón sin restricción de origen**: cualquiera que conozca un PeerID —y el PeerID se
  comparte abiertamente para darse de alta— puede depositar hasta llenar la cuota del
  destinatario (200 msgs / 5 MiB) y provocar que **los envíos legítimos sean rechazados**.
  Es denegación de entrega, no solo spam. *(fichado como "spam"; conviene reetiquetarlo)*
- **Wake sin tope de suscriptores** *(no fichado)*: `wakeRegistry` acepta cualquier número
  de suscripciones, cada una con su stream y sus dos goroutines. `infra/node/wake.go`.

Mitigación coherente con el modelo (sin que el nodo aprenda más de lo que ya sabe): cuota
**por par (emisor, destinatario)** además de la global —un emisor solo puede ocupar una
fracción del buzón ajeno—, topes finitos pero generosos en el relay, y un máximo de
suscripciones de wake.

### A-12 (Bajo) — Deuda menor y código muerto

- `Libp2pNode` sigue con el KDoc `STUB: la Fase 0 … sustituirán los TODO por llamadas
  reales` en una clase que hace tiempo que es real. Confunde a quien llegue nuevo.
- `Libp2pNode.dial()` es una función vacía con un `TODO Fase 2`, sin ningún llamante.
  Borrarla.
- `ChatService.ackedReceipts` es un `mutableSetOf` sin tope que además se pierde al morir
  el proceso: tras un reinicio se reenvían acuses ya enviados. Inocuo, pero es estado que
  debería vivir en Room (o simplemente derivarse de que el mensaje ya esté en READ local).
- `Libp2pNode.findPeers` no deduplica: `FindPeers` en Go puede devolver el mismo PeerID
  varias veces si aparece en varias respuestas de la DHT.

---

## 6. Seguridad — resumen

**Sólido y bien hecho:**

- Acuerdo de claves sin MITM matemático: el PeerID **es** la clave pública, así que el
  único vector es la sustitución del identificador en el canal por el que se comparte — y
  para eso están el número de seguridad y el QR. El razonamiento está correctamente
  documentado en `SafetyNumber`.
- Conversión Ed25519→X25519 correcta (clamp sobre `SHA-512(seed)[:32]`, `BytesMontgomery`).
- HKDF RFC 5869 propio, correcto. AES-256-GCM con nonce aleatorio de 12 B antepuesto y
  tag de 128 bits. Descifrado que lanza ante manipulación y no devuelve basura.
- Respaldo `.krbk` bien construido: PBKDF2-HMAC-SHA256 310k, magic como AAD, formato
  hacia adelante y hacia atrás compatible (las líneas `b=` que un cliente viejo ignora).
- Remitente no suplantable en el buzón (lo fija el nodo desde la identidad del stream) y
  GET que solo entrega el buzón del peer autenticado. Es la decisión de diseño más elegante
  del nodo.
- Lecturas acotadas en los tres sitios que hablan con desconocidos (`/krypta/msg`, fetch
  de buzón, wake), con el detalle del `+1` para distinguir "justo en el tope" de "se pasó".
- `FLAG_SECURE` acotado a la pantalla de chat, con el razonamiento de por qué app-wide
  estaba mal. `allowBackup="false"` con las reglas XML como defensa en profundidad.
- El manifiesto no pide ni un permiso de más, y cada uno está justificado en un comentario.

**Lo que queda abierto**, por orden de importancia: A-4 (datos en reposo sin cifrar),
A-6 (metadatos del buzón/wake sin documentar), A-5 (sin PFS), A-11 (nodo sin anti-abuso),
A-1 (rotación de rendezvous rota de facto), A-3 (tope de recepción), A-9 (endurecimiento
del id de mensaje).

---

## 7. Pruebas

**Lo que hay**, y es mucho: 82 tests JVM en `:p2p-signaling` que cubren el dominio entero
con dobles (mensajería, buzón con ack-tras-persistir, bloqueo, respuestas citadas,
llamadas con dos extremos en memoria y vídeo E2EE bidireccional), suites Go a ambos lados
del protocolo (buzón, wake, relay, streams de llamada y vídeo, límites de lectura,
bootstrap parcial), y sondas contra los nodos reales activables por variable de entorno —
un patrón muy bueno: la misma suite sirve de test y de monitorización manual.

**Los huecos:**

1. **`:data` no tiene ni un test.** Es el módulo con las **migraciones de Room**, que
   CLAUDE.md señala como el sitio donde un error "lanza en tiempo de ejecución". Hay cuatro
   versiones de esquema en producción y **ninguna migración se ha probado automáticamente**;
   se han validado a mano en el teléfono del autor. `MigrationTestHelper` existe para esto.
2. **Solo están exportados `data/schemas/4.json` y `5.json`.** Sin los de v2 y v3 no se
   pueden escribir tests de `MIGRATION_2_3` ni `MIGRATION_3_4` ni siquiera queriendo. Si
   quedan bases de datos v2/v3 en algún dispositivo, esas migraciones siguen siendo código
   vivo no verificable.
3. **`:core` sin tests** (aceptable: son interfaces y `data class`es).
4. **`ChatViewModel` sin tests**, consecuencia directa de A-13.
5. **La Verificación del plan pide instrumentados de "arranque/parada del FGS y del
   `Libp2pNode`; reconexión tras kill"** — no existen. Los instrumentados que hay son de
   notificaciones y de descubrimiento en dispositivo. Con el agravante de que ejecutar
   instrumentados **borra la identidad del teléfono**, lo que desincentiva escribirlos: la
   salida razonable es un emulador o un dispositivo de repuesto dedicado, no dejarlos sin
   escribir.

---

## 8. Plan de acción sugerido

**Antes de publicar** (rompe promesas declaradas o degrada con el uso):

1. **A-1** — anuncio de rendezvous de una pasada + **A-8** ventana de solape, en el mismo
   cambio. Requiere regenerar el AAR.
2. **A-2** — `remember` del flujo, `flowOn`, caché de clave de sesión. Las tres primeras
   son de una tarde; la paginación puede ir después.
3. **A-10** — dos líneas en `.gitignore`. Ahora.
4. **A-3** — validar la meta y barrer el staging huérfano.
5. **A-6** — escribir `docs/security-model.md`. No es código: es cerrar la brecha entre lo
   que se promete y lo que se sabe. Debe incluir el análisis de metadatos del buzón/wake y
   la concentración de operador.

**Siguiente ventana** (endurecimiento):

6. **A-11** — cuota por par emisor/destinatario en el buzón, topes finitos en el relay,
   máximo de suscripciones de wake.
7. **A-4** — identidad envuelta en Android Keystore (el paso con mejor relación
   protección/esfuerzo del informe, después de los anteriores).
8. **A-7** — reintento automático de `FAILED` en el ciclo WAN.
9. **§7** — tests de migración de Room con `MigrationTestHelper`, al menos 4→5.
10. **A-9, A-12** — endurecimiento del id y limpieza de código muerto.

**Cuando toque** (arquitectura, sin urgencia):

11. **A-14** — trocear `ChatService` y `ChatScreens.kt`.
12. **A-13** — interfaz de `:core` para el servicio de chat, que además desbloquea los
    tests de `ChatViewModel`.
13. **A-5** — PFS (Noise/doble-ratchet). Proyecto grande; mientras tanto, decir en la ayuda
    y en la política lo que hoy no se garantiza.
14. **Fase 0** — cerrar por fin el gate de NAT con dos SIMs y registrar el % DCUtR vs relay.
    Es la métrica que valida la arquitectura entera y sigue sin medirse.

---

## 9. Estado de implementación (8 de septiembre de 2026)

El plan de acción se ejecutó al día siguiente de escribir el informe. Estado por hallazgo:

| # | Hallazgo | Estado | Dónde |
|---|---|---|---|
| A-1 | Fuga de goroutines / rendezvous caducados | ✅ Hecho | `bridge.go` (`Advertise` de una pasada) + `advertise_test.go` (verificado que **falla** contra el código anterior) |
| A-8 | Ventana de solape del rendezvous | ✅ Hecho | `RendezvousService.rendezvousWindow` + `announceAndFind`; tests en `RendezvousServiceTest` y `ChatServiceTest` |
| A-2 | Descifrado en hilo principal / por recomposición | ✅ Hecho (paginación aparte) | `remember` en `ChatScreens.kt`, `flowOn` + caché de descifrado en `ChatViewModel`, caché de clave de sesión en `AesGcmMessageCipher` |
| A-3 | Recepción de archivos sin tope ni limpieza | ✅ Hecho | `DiskFileStore` (validación de meta, índices y tamaño de trozo, barrido del staging) + 5 tests nuevos |
| A-10 | `node.key` fuera de `.gitignore` | ✅ Hecho | `.gitignore` (`/infra/node/*.key`, `/infra/node/mailbox/`) |
| A-6 | Sin `security-model.md` ni análisis de metadatos | ✅ Hecho | [security-model.md](security-model.md), 10 secciones; el análisis de metadatos es su §6 |
| A-11 | Nodo sin capa anti-abuso | ✅ Hecho (falta desplegar) | `mailbox.go` (reparto justo + desalojo), `wake.go` (tope de suscripciones), `main.go` (límites finitos del relay) |
| A-4 | Identidad y BD sin cifrar en reposo | ✅ Hecho (9 sep 2026) | `IdentityStore` + `KeystoreKeyWrapper`; **base con SQLCipher** (`DatabaseKey`/`DatabaseEncryption`, conversión verificada sobre la base real del TECNO) y **adjuntos cifrados** (`FileVault`). Los adjuntos anteriores no se convierten |
| A-7 | Sin reconciliación de envíos fallidos | ✅ Hecho | `ChatService.retryFailed`, paso `reintentos` del ciclo WAN + `MessageRepository.findByStatus` |
| §7 | Migraciones de Room sin test | ✅ Hecho (9-10 sep 2026) | `MigrationTest` **ejecutado en el TECNO**: 5 casos (4→5, 5→6, 6→7, 7→8, 8→9), todos verdes |
| A-9 | El id del emisor puede pisar otra conversación | ✅ Hecho | Guardas en `onReceived`, `persistFile` y `markOutgoingRead` + 2 tests |
| A-12 | Deuda menor y código muerto | ✅ Hecho | `dial()` eliminado, KDoc de `Libp2pNode` al día, `ackedReceipts` acotado (LRU), `findPeers` sin duplicados |
| A-5 | Sin secreto hacia adelante (PFS) | 🟡 Implementado y encendido; **sin probar en vivo** | Doble ratchet por épocas ([DISENO-ratchet.md](DISENO-ratchet.md), fases 1-9), envío encendido el 10 sep 2026 — pero solo con contactos que lo anuncien, y la prueba con dos móviles (`PRUEBAS-PENDIENTES` §16) **se debe**. La clave de llamada ya se negocia para todos |
| A-14 | `ChatService` clase-Dios | ⬜ No hecho | Refactor de arquitectura, sin urgencia (tramo 3 del plan) |
| A-13 | `:app` depende de clases concretas | ⬜ No hecho | Ídem |
| Fase 0 | Gate de NAT con dos SIMs | ⬜ No hecho | Necesita dos teléfonos con operadoras distintas |

**Cobertura de tests**: 116 → **136** tests JVM (todos verdes), más 4 tests Go nuevos. `:data`
y `:native-bridge`, que no tenían ninguno, ya tienen: `MigrationTest` (instrumentado) y
`IdentityStoreTest` (6 casos) respectivamente.

> **Al 10 sep 2026**: **209** tests JVM tras el trabajo de cifrado en reposo y ratchet, más los
> instrumentados de `:data` corriendo de verdad en el dispositivo.

**Pendiente operativo, no de código**: los tres nodos de infraestructura **siguen corriendo el
binario anterior**. Todo el anti-abuso (A-11) y la lectura acotada del 6 sep solo entran en
vigor al redesplegar. Los **binarios ya están compilados** (8 sep 2026, Go 1.22.12 con
`GOTOOLCHAIN=local`; el de Catalina con `minos 10.13` verificado) en `infra/node/dist/`:
`krypta-node-linux-amd64` y `-arm64` (VPS, vía `deploy-vps.sh`), `krypta-node-catalina`
(Mac, vía `deploy-catalina.sh`) y `krypta-node-windows-amd64.exe` (copiar al PC). Falta
únicamente ejecutar el despliegue.

**Deferido a conciencia**: la **paginación** del historial (parte de A-2). Con `remember`,
`flowOn` y la caché de descifrado, el coste por pulsación de tecla y por mensaje nuevo ya
desaparece; paginar cambia además lo que el usuario ve (haría falta un "cargar más"), así que
es una funcionalidad, no un arreglo.

### Verificación en vivo (8 sep 2026)

**En el TECNO** (instalado sobre la versión anterior, con `.krbk` exportado antes):

- **La migración de la identidad al Keystore funcionó sin perder nada.** Antes de instalar,
  `shared_prefs/krypta_identity.xml` tenía `ed25519` en claro y su PeerID derivado era
  `12D3KooWE7JcNYJyczGeu9qPtLF6M8xybecsbha2zkFWdPKiruXg`. Después: la clave `ed25519` **ya no
  existe** y en su lugar hay `ed25519_wrapped`, y Ajustes muestra **el mismo PeerID**. Los dos
  contactos reales siguen ahí, **verificados**, y sus vistas previas **descifran** — que es la
  prueba de fondo, porque si la identidad hubiera cambiado el secreto ECDH de cada contacto
  sería otro y las previsualizaciones saldrían ilegibles.
- **El anuncio de rendezvous de una sola pasada descubre igual**: el diagnóstico muestra
  `DHT: conectado`, `relay: OK (alcanzable por circuit)`, `rendezvous: anunciando a
  2 contacto(s)`, `rendezvous: ✓ encontrado …unNxKaXK` y `wake activo: bucle relajado (180s)`.

**En los nodos** (sondas `TestMailboxFetchAgainstLiveNode`, `TestWakeAgainstLiveNode` y
`TestMailboxRoundTripAgainstLiveNode` contra los tres): buzón, wake e ida y vuelta completa
**pasan en los tres**.

**Qué binario corre cada nodo.** Se puede saber sin entrar en la máquina: con límites finitos,
la respuesta de reserva de Circuit Relay v2 **incluye** el límite; con `WithInfiniteLimits()`
no lo incluye. Reservando una plaza en cada nodo:

| Nodo | Límite anunciado | Binario |
|---|---|---|
| Mac (`krypta.neto.chat`) | 6 h / 8 GiB | ✅ nuevo (anti-abuso activo) |
| Windows (`krypta2.neto.chat`) | 6 h / 8 GiB | ✅ nuevo (anti-abuso activo) |
| **VPS São Paulo** (`216.128.169.83`) | 6 h / 8 GiB | ✅ nuevo (desplegado el 8 sep, 10:15 UTC) |

El VPS se había quedado atrás —seguía con el binario del 6 sep— y se desplegó con
`deploy-vps.sh` el mismo día. Comprobado tras el despliegue: el `sha256sum` del binario en
`/usr/local/bin/krypta-node` coincide con el de `dist/krypta-node-linux-amd64`
(`c463fa99…98eaf`), **el PeerID no cambió** (`/var/lib/krypta/node.key` sigue siendo el del
7 ago: el script no lo toca), el servicio quedó `active`+`enabled`, escucha en `4001/tcp`,
`4001/udp` y `8081/ws`, y las tres sondas (buzón, wake, ida y vuelta) pasan. **La app ni se
enteró**: el móvil siguió con su ciclo relajado de 3 min durante el reinicio, sin un solo error
en el diagnóstico — que es lo que se espera del diseño multi-nodo y del bucle auto-reparable.

**Hallazgo operativo de propina.** Al mirar la ocupación del buzón apareció un destinatario con
**88 sobres / 5,34 MiB, por encima de la cuota de 5 MiB**, todos del **mismo remitente** y
depositados entre el 2 y el 3 de septiembre: tiene toda la pinta de un archivo troceado
(~88 trozos) que quedó a medio entregar porque ese contacto lleva días sin conectarse — el
propio diagnóstico del móvil lo dice: `rendezvous: aún no encuentro …kzCtwtbB`. Se limpiará
solo al cumplirse el TTL de 7 días. Consecuencias: (a) los depósitos nuevos para ese contacto
los rechaza **ese** nodo, pero `MailboxPut` hace failover al siguiente, así que la entrega no
se corta; (b) con el reparto justo recién desplegado, ese mismo remitente puede seguir llenando
el buzón mientras sea el único, y un segundo remitente ya no se queda fuera: desaloja lo más
antiguo del acaparador. Es exactamente el comportamiento buscado. (c) Los sobres antiguos
llevan el nombre `<id>.json` sin remitente, así que la compatibilidad hacia atrás de
`scanBox`/`delete` se está ejercitando ahora mismo en producción.

