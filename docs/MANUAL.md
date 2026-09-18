# Manual técnico y de usuario de Krypta

> Estado del documento: refleja el estado del proyecto a **17 de julio de 2026**, con los
> apartados de cifrado puestos al día el **10 de septiembre de 2026** (ver
> [architecture.md](architecture.md) y [../CLAUDE.md](../CLAUDE.md) para el detalle más
> reciente). Krypta está en fase de skeleton/beta funcional: no ha sido publicado en Google
> Play todavía. Las secciones marcadas **[pendiente]** son trabajo o verificación que aún
> falta.

## Índice

1. [Qué es Krypta](#1-qué-es-krypta)
2. [Manual de usuario](#2-manual-de-usuario)
3. [Manual técnico](#3-manual-técnico)
4. [Comparativa con WhatsApp y Signal](#4-comparativa-con-whatsapp-y-signal)
5. [Requisitos de Google Play para publicar Krypta](#5-requisitos-de-google-play-para-publicar-krypta)
6. [Glosario](#6-glosario)

---

## 1. Qué es Krypta

Krypta es una **aplicación de mensajería para Android, descentralizada, cifrada de
extremo a extremo (E2EE) y de alcance mundial (WAN)**, escrita en Kotlin + Jetpack Compose.
A diferencia de WhatsApp o Signal, Krypta **no depende de un servidor central que enruta
los mensajes**: dos dispositivos se descubren entre sí sobre una red P2P (libp2p, la misma
familia de tecnología que usa IPFS) y se hablan directamente o, si no es posible, a través
de un nodo de **relevo** que solo ve bytes cifrados.

Piezas que lo hacen posible:

- **Identidad = clave criptográfica.** Cada instalación genera un par de claves Ed25519;
  el identificador público de un usuario (su `PeerID`) *es* su clave pública. No hay
  cuentas, números de teléfono ni registro en un servidor.
- **Descubrimiento por *rendezvous* diario.** Dos contactos que ya se conocen (intercambiaron
  su `PeerID` una vez) se encuentran cada día en un punto de una tabla hash distribuida
  (Kademlia DHT) derivado de su secreto compartido — nadie más puede predecir ese punto.
  En LAN también hay mDNS como atajo de pruebas.
- **Transporte libp2p** con QUIC/TCP directo cuando es posible, y **Circuit Relay v2** +
  **DCUtR** (hole-punching) cuando ambos están detrás de NAT — el caso normal en redes
  móviles.
- **Buzón cifrado (mailbox) de guarda-y-reenvía** para cuando el destinatario está offline:
  el nodo de infraestructura solo almacena blobs opacos, con cuotas y TTL de 7 días.
- **Aviso instantáneo (wake)** integrado en el propio nodo: al llegar un mensaje al buzón,
  el nodo empuja un aviso por un stream ligero que el teléfono mantiene abierto, disparando
  una notificación en segundos aunque la app esté cerrada.
- **Todo el contenido va cifrado con el secreto del par** (X25519 ECDH derivado de las
  identidades Ed25519 + HKDF + AES-256-GCM): texto, fotos, archivos, notas de voz y las
  llamadas de voz/vídeo. Desde el 10 sep 2026, con los contactos que tengan una versión
  reciente, esa clave además **cambia con cada mensaje** (ratchet); con el resto sigue siendo
  fija por contacto. Cada llamada usa su propia clave, sorteada para ella.
- **Lo que se guarda en el teléfono también va cifrado** (9 sep 2026): la base de datos de las
  conversaciones (SQLCipher) y los adjuntos de `krypta_files/`, con claves envueltas por el
  almacén seguro de Android. Dos matices: los adjuntos que ya estuvieran en el móvil antes de
  esa versión siguen en claro, y abrir uno con otra aplicación le entrega una copia sin cifrar.

El diseño completo y el roadmap por fases están en
[PLAN-senalizacion-descentralizada.md](PLAN-senalizacion-descentralizada.md); el estado de
arquitectura módulo a módulo, en [architecture.md](architecture.md).

---

## 2. Manual de usuario

> **Ayuda dentro de la app.** Además de este manual, Krypta trae una **Ayuda** integrada
> (icono **?** en la barra superior de la lista de conversaciones y de Ajustes): un resumen
> corto de las dudas más frecuentes, en preguntas desplegables y agrupadas por tema.
> Funciona sin conexión. En algunos puntos de Ajustes hay además un icono **ⓘ** con una
> explicación breve en contexto (por ejemplo, qué es tu PeerID o por qué configurar la
> recepción en segundo plano). Este manual es la referencia extensa; la Ayuda in-app es el
> resumen rápido.

### 2.1 Instalación

Krypta todavía no está en Google Play (ver [§5](#5-requisitos-de-google-play-para-publicar-krypta)).
Mientras tanto se instala como APK firmado con la clave de depuración:

1. En el móvil, habilita **Ajustes → Seguridad → Instalar apps desconocidas** para el
   origen desde el que vayas a copiar el APK (navegador, gestor de archivos, ADB…).
2. Instala el `.apk` (p. ej. `krypta-arm64-debug.apk`).
3. Al abrir la app por primera vez, Android pedirá permisos en tiempo de ejecución
   conforme los vayas usando (notificaciones al arrancar; micrófono la primera vez que
   grabes una nota de voz o llames; cámara la primera vez que escanees un QR de
   verificación).

Requisitos: **Android 11 (API 30) o superior**.

### 2.2 Primeros pasos: tu identidad

No hay registro ni contraseña. Al primer arranque, Krypta genera tu identidad
(par de claves Ed25519) y la guarda en el dispositivo. Tu **PeerID** —visible y copiable
desde **Ajustes**— es lo único que necesitas compartir para que alguien te añada como
contacto; no revela ninguna otra información personal.

La app se conecta automáticamente a la red WAN (DHT) al arrancar, sin ninguna
configuración: el nodo de arranque (*bootstrap*) por defecto ya viene precargado.

### 2.3 Añadir un contacto

1. En la lista de conversaciones, pulsa el botón **"Nuevo contacto"**.
2. Introduce un **nombre** (el que verás tú, local) y el **PeerID** de la otra persona
   (te lo tiene que pasar por otro canal: en persona, por otra app, por voz…).
3. Guarda. A partir de aquí, tu app deriva automáticamente el secreto compartido con esa
   persona (ECDH) — no hace falta ningún paso adicional ni intercambio de claves manual.

**El avatar del contacto (letra + color + forma).** Krypta no usa fotos de perfil (por el
sentido de privacidad de la app). En su lugar, cada contacto tiene un avatar generado
automáticamente con tres rasgos:

- **La letra** es la **inicial del nombre** que tú le pusiste (una sola letra). Es el único
  rasgo que depende del nombre local, así que si renombras al contacto, cambia la letra.
- **El color** y **la forma** (círculo, "squircle", hexágono, pentágono u octágono) **se
  derivan del `PeerID`** del contacto, no del nombre. Es decir, dependen de *quién es de
  verdad* esa persona (su identidad criptográfica), no de cómo tú la llamaste.

Como color y forma salen del PeerID, son **estables**: el mismo contacto se ve siempre igual
en la lista, en la cabecera del chat y en la pantalla de llamada. Y funcionan como una
pequeña **huella visual de identidad**: si un día el avatar de un contacto conocido apareciera
con otra forma o color, sería señal de que su PeerID cambió (posible suplantación) — un aviso
extra que se suma al escudo de verificación y al número de seguridad (ver §2.4). La variedad
de formas es solo para romper la monotonía de tener todo círculos; **todas tienen el mismo
tamaño visual y la misma letra**, así que ninguna forma "vale más" que otra.

### 2.4 Verificar la identidad de un contacto (anti-suplantación)

El intercambio de claves en sí no tiene ataque de intermediario (MITM) porque el `PeerID`
*es* la clave pública. El único punto débil es el canal por el que compartiste ese `PeerID`
la primera vez (¿y si alguien te dio un PeerID falso?). Para cerrar ese hueco:

1. Abre el chat con el contacto → **"Verificar identidad"**.
2. Verás un **número de seguridad** de 60 dígitos (al estilo Signal): es el mismo en ambos
   teléfonos si de verdad os habláis entre vosotros. Compáralo en voz alta o por un canal
   de confianza.
3. Alternativa más rápida: cada uno muestra su **código QR** y escanea el del otro
   (cara a cara). La app compara automáticamente el PeerID escaneado con el guardado:
   coincide → marca verificado (aparece un escudo ✅ en el contacto); no coincide → avisa
   de una posible suplantación.

### 2.5 Mensajería

- **Texto:** como cualquier chat. Estados visibles con icono: reloj (pendiente), ✓ (enviado),
  ✓✓ (entregado), ✓✓ en color (leído).
- **Fotos:** botón de clip → *Foto*. Se comprimen automáticamente antes de enviarse.
- **Archivos:** botón de clip → *Archivo*. Límite actual: **8 MB** (offline, vía buzón, el
  límite práctico ronda los ~5 MB por las cuotas del buzón).
- **Notas de voz:** mantén pulsado el icono del micrófono para grabar (suéltalo para
  enviar); un toque corto deja la nota fijada con botones **Cancelar/Enviar**. Grabaciones
  de menos de 1 segundo se descartan como pulsaciones accidentales.
- Si un mensaje queda en rojo/**Fallido**, tócalo para **reintentar el envío**.
- Los mensajes fallidos, pendientes y offline se resuelven solos: si el contacto está
  desconectado, Krypta deja el mensaje cifrado en el buzón del nodo y se entrega en cuanto
  la otra persona vuelve a conectarse (a menudo, en segundos, gracias al aviso instantáneo).

### 2.6 Llamadas de voz y vídeo

- Botón de teléfono en la barra superior del chat inicia una **llamada de voz**. Pantalla
  completa con avatar, nombre y cronómetro; controles redondos con etiqueta (silenciar,
  altavoz, colgar, vídeo).
- Durante una llamada activa, el botón de **cámara** activa el **vídeo** sin cortar el
  audio si el vídeo falla. Hay vista previa propia (arrastrable) y vídeo remoto a pantalla
  completa; un toque muestra/oculta los controles.
- El sensor de proximidad apaga la pantalla al acercarte la oreja (llamadas de voz sin
  vídeo).
- Si llega una llamada con la app cerrada, suena el tono y aparece una notificación de
  llamada entrante incluso con la pantalla apagada.
- No hay adaptación automática de bitrate ni integración con Teléfono del sistema
  (Telecom) todavía — ver [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md).

### 2.7 Notificaciones y segundo plano

Krypta mantiene un **servicio en primer plano** (ícono persistente discreto) para poder
recibir mensajes con la app cerrada, igual que hacen WhatsApp/Signal con sus servicios de
push — con la diferencia de que aquí no hay servidor de Google/Apple de por medio, sino
una conexión P2P propia. Recomendaciones:

- Acepta la **excepción de optimización de batería** que la app solicita al arrancar (si
  no, el sistema puede cortar la conexión con la pantalla apagada).
- En móviles con fama de "matar apps en segundo plano" (Xiaomi, TECNO/Transsion,
  Huawei…), usa el botón **"Ajustes de recepción en 2.º plano"** de la lista de contactos
  para abrir los ajustes específicos del fabricante y desactivar la restricción.
- Aunque todo lo anterior falle, hay un latido de seguridad cada ~2 minutos que reintenta
  la entrega, así que en el peor caso un mensaje tarda un par de minutos en notificarse.

### 2.8 Copia de seguridad de tu identidad

Tu identidad (y por tanto el acceso a tus conversaciones y contactos) vive solo en el
dispositivo. Si lo pierdes o cambias de teléfono, **sin copia de seguridad no hay forma de
recuperar tu PeerID ni de que tus contactos te reconozcan como el mismo interlocutor**.

- **Ajustes → Copia de seguridad → Exportar**: genera un archivo `.krbk` cifrado con una
  contraseña que tú eliges (contiene tu identidad + tu lista de contactos; nunca los
  secretos compartidos, que se regeneran solos al reinstalar).
- **Importar**: elige el archivo `.krbk`, introduce la contraseña; la app te pedirá
  cerrarse y reabrirse para aplicar la identidad importada.
- **Antes de importar, pon el móvil en fecha y hora automáticas.** Si al importar la fecha está
  atrasada, lo que escribes a un contacto le llega, pero **lo que te escribe él se pierde**, y
  corregir la hora después no basta. Si ya te ha pasado: activa la hora automática, elimina ese
  contacto y vuelve a añadirlo con su PeerID. Se borra esa conversación en tu móvil (tras importar
  apenas tiene nada, porque la copia no lleva mensajes) y tendréis que volver a verificaros.
- Guarda ese archivo en un sitio seguro (gestor de contraseñas, almacenamiento cifrado) —
  quien lo tenga y sepa la contraseña puede suplantarte.

### 2.9 Bloqueo de la app

Puedes exigir un desbloqueo para entrar en Krypta, de modo que quien tenga tu móvil en la
mano no pueda abrir tus conversaciones:

- **Ajustes → Bloqueo de la app → "Pedir desbloqueo para entrar"**: se desbloquea con tu
  huella, tu cara o el PIN/patrón del propio móvil (el diálogo nativo de Android). Krypta
  **no guarda ninguna contraseña propia** — la comprobación la hace el sistema, igual que
  en Signal o WhatsApp. Requiere tener configurado un bloqueo de pantalla en el móvil.
- **"Volver a bloquear al salir"**: al instante (por defecto), tras 1 min o tras 5 min en
  segundo plano. Al abrir la app en frío siempre pide desbloqueo.
- Activarlo o desactivarlo pide autenticarse (para que nadie lo apague con tu móvil en la
  mano), y una llamada entrante **se puede atender sin desbloquear** (como el teléfono).
- Ojo: esto protege la *pantalla*, no el disco — los mensajes siguen recibiéndose y
  notificándose con normalidad.

### 2.10 Vaciar un chat o eliminar un contacto

Krypta guarda todo (mensajes, fotos, archivos, notas de voz) solo en tu dispositivo, así
que también puedes borrarlo selectivamente sin tener que desinstalar la app:

- **Vaciar un chat:** mantén pulsada una conversación en la lista (o abre el menú **⋮** en
  la cabecera del chat) → **"Vaciar conversación"**. Borra todos los mensajes y los
  archivos/fotos/notas de voz asociados de tu dispositivo, pero **conserva el contacto**
  (nombre, PeerID y estado de verificación); podéis seguir escribiéndoos con normalidad.
- **Eliminar un contacto:** mismo menú → **"Eliminar contacto"**. Vacía la conversación y
  además borra el contacto por completo; Krypta deja de buscarlo en la red. Si más adelante
  lo vuelves a añadir con el mismo PeerID, el secreto compartido se regenera solo, pero
  **la verificación de identidad se pierde** y hay que repetirla.
- Ambas acciones piden confirmación explícita porque **no se pueden deshacer**: no hay
  papelera ni copia de seguridad automática de lo borrado.
- Esto solo afecta a tu copia local: la otra persona conserva su propio historial hasta
  que decida borrarlo también.

### 2.11 Bloquear un contacto

Si alguien te molesta y prefieres no borrar el historial, puedes **bloquearlo**: mantén
pulsada la conversación en la lista, o abre el menú **⋮** dentro del chat, y elige
**"Bloquear"**.

- **Deja de llegarte todo lo suyo**: mensajes, fotos, archivos, notas de voz y llamadas se
  descartan en tu móvil sin guardarse y sin avisarte (ni notificación, ni timbre, ni fila de
  llamada perdida).
- **Tampoco le llega nada tuyo**: no puedes escribirle ni llamarle mientras esté bloqueado,
  y Krypta deja incluso de mandarle el ✓✓ de leído.
- **Él no se entera.** No recibe ningún aviso de que lo has bloqueado: sus mensajes le
  aparecen como enviados, igual que si tuvieras el móvil apagado o sin cobertura.
- **Tu móvil deja de anunciarse a él** en la red: ya ni siquiera puede localizar tu
  dispositivo mientras dure el bloqueo.
- **No borra nada.** El chat y el historial siguen ahí; para borrarlos están "Vaciar
  conversación" y "Eliminar contacto" (§2.10). Por eso bloquear **no pide confirmación**:
  se deshace en el mismo sitio con **"Desbloquear"**.
- Dentro de un chat bloqueado no hay barra para escribir: en su lugar verás el aviso de que
  lo has bloqueado, con un botón **Desbloquear**.

Recuerda además que en Krypta **solo pueden escribirte tus contactos**: un PeerID que no
tengas guardado no puede hacerte llegar nada, así que el bloqueo es para gente que ya
añadiste.

### 2.12 Ajustes relevantes

- **Apariencia (tema):** elige el tema de la app entre **Sistema**, **Claro** y **Oscuro**.
  Por defecto es **Sistema**, que sigue el modo claro/oscuro del móvil (incluido el cambio
  automático por horario). El cambio se aplica al instante, sin reiniciar.
- **Nodo WAN (bootstrap):** lista de nodos de arranque a la red (uno por línea). Viene
  precargado con el nodo público de Krypta; puedes añadir otros o dejarlo en blanco para
  forzar modo solo-LAN.
- **Estado WAN / Diagnóstico:** panel con el estado de conexión DHT/relay/wake y un log
  técnico, útil para reportar problemas.
- **📞 Latencia:** mide el *ping* real sobre la red P2P (útil antes de una llamada).

### 2.13 Preguntas frecuentes

**¿Necesito un número de teléfono o email?** No. Solo tu identidad local y el PeerID de
tus contactos.

**¿Puede alguien leer mis mensajes en el camino?** No: todo el contenido va cifrado de
extremo a extremo; los nodos de relevo/buzón solo ven bytes opacos.

**¿Qué pasa si cambio de móvil sin copia de seguridad?** Se genera una identidad nueva
(un PeerID nuevo); tus contactos tendrán que volver a añadirte y, si te habían verificado,
volver a verificar.

**¿Funciona sin wifi, solo con datos móviles?** Sí, la red P2P funciona igual sobre datos
móviles (verificado en pruebas de latencia y llamadas).

**¿Por qué a veces tarda unos minutos en notificarme un mensaje?** En algunos fabricantes
Android suspende la red en segundo plano; el "latido" de seguridad (~2 min) es la última
red de protección. Revisa el ajuste de "recepción en 2.º plano" ([§2.7](#27-notificaciones-y-segundo-plano)).

---

## 3. Manual técnico

### 3.1 Estructura de módulos

```
:app            UI Compose + ViewModels, KryptaApplication (@HiltAndroidApp),
                MainActivity, KryptaForegroundService, KryptaNotifications.
:core           Dominio puro: interfaces (ISignalingService, IDiscoveryService,
                MessageRepository, ContactRepository) + modelos (Message, Contact,
                MessageStatus). Sin Android, sin DI. Todo lo demás depende de :core.
:data           Persistencia Room: MessageEntity/MessageDao/KryptaDatabase/Converters,
                RoomMessageRepository, DataModule (Hilt).
:native-bridge  Envoltorio Kotlin/JNI sobre el AAR de go-libp2p (Libp2pNode).
:p2p-signaling  RendezvousService (HKDF-SHA256 real, RFC 5869) + SignalingService
                (implementa ISignalingService) + SignalingModule (Hilt @Binds).
```

Grafo de dependencias: `:app → :core, :data, :p2p-signaling, :native-bridge`;
`:p2p-signaling → :core, :native-bridge`; `:data → :core`; `:native-bridge → :core`. Las
interfaces del dominio viven en `:core` para que las implementaciones sean intercambiables
vía Hilt. Detalle completo en [architecture.md](architecture.md).

### 3.2 Identidad y criptografía

| Elemento | Mecanismo |
|---|---|
| Identidad de dispositivo | Par Ed25519 persistente (SharedPreferences); el `PeerID` es la clave pública |
| Secreto compartido por par | X25519 ECDH entre tu clave privada y la pública embebida en el PeerID del contacto (`KeyExchange` / `Bridge.sharedSecretFor`) — **no requiere intercambio de claves manual**, solo conocer el PeerID |
| Cifrado de payload | AES-256-GCM. Entre clientes actualizados, clave **por mensaje** de un doble trinquete por épocas (`Ratchet`, activo desde el 10 sep 2026); con un contacto que aún no lo anuncia, clave estática derivada por HKDF del secreto compartido (`MessageCipher`) |
| Rendezvous (descubrimiento) | `HKDF(secreto_compartido, fecha)` → punto de encuentro diario en la DHT, no enumerable sin el secreto |
| Verificación anti-MITM | *Safety number* = 60 dígitos decimales de `SHA-256(dominio ‖ peerIdA ordenado ‖ peerIdB)`, simétrico; también disponible como QR (`krypta:verify:<PeerID>`) |
| Huella visual del avatar | Color y **forma** del avatar derivados por hash del `PeerID` (color: `AvatarColors[hash]`; forma: `avatarShapeFor` sobre un hash decorrelacionado, set de formas de igual área en `ui/theme/AvatarShape.kt`). No es criptográfico ni sustituye al *safety number*, pero es una pista estable: un cambio de PeerID cambia color/forma. La **inicial** (una letra) deriva del nombre local, no del PeerID |
| Backup de identidad | `"KRBK1" ‖ salt ‖ nonce ‖ AES-256-GCM(payload)`, clave = PBKDF2-HMAC-SHA256 · 310.000 iteraciones, magic como AAD |
| Llamadas (voz/vídeo) | Clave de sesión por llamada = `HKDF(k_llamante ‖ k_contestador, salt = secreto_compartido)`: cada lado sortea 32 bytes al azar y los manda dentro del sobre cifrado (fase 7 del ratchet). Con un contacto que no negocia se usa el camino antiguo, `HKDF(secreto_compartido, callId)` |

Ningún servidor ve nunca texto en claro, ni siquiera los nodos de infraestructura propios
de Krypta: solo manejan blobs cifrados. Metadatos sí ven, y **no son mínimos**: el grafo
diario de parejas (los dos extremos publican el mismo punto de cita y quien lo almacena es el
nodo, también si vuestro tráfico va directo y no pasa por él), quién está en línea, la IP, y
qué dos PeerID hablan y cuánto cuando el tráfico pasa por el relevo. En el buzón, desde el
depósito ciego (12 sep 2026) el sobre va bajo una etiqueta opaca para las parejas que anuncian
protocolo ≥ 2; para el resto, con PeerID de origen y destino. Detalle completo en
[security-model.md](security-model.md) §5 y §6.

### 3.3 Descubrimiento y transporte

- **DHT (Kademlia)** vía go-libp2p: cada contacto se anuncia (`advertise`) y se busca
  (`findPeers`) en el punto de rendezvous derivado del día actual.
- **mDNS** como atajo solo para pruebas en LAN (`best-effort`, envuelto para no abortar el
  host si falla, p. ej. en datos móviles sin interfaz multicast).
- **Conexión directa** TCP/QUIC cuando es posible; si ambos peers están detrás de NAT
  (el caso típico en móvil):
  - **Circuit Relay v2**: el nodo de infraestructura releva el tráfico cifrado
    (`ForceReachabilityPublic` + `AddrsFactory` propio en el móvil + reserva de relay cada
    30 s + `WithAllowLimitedConn` al abrir el stream + `EnableRelayService(WithInfiniteLimits())`
    para no capar la conexión a 128 KiB/2 min).
  - **DCUtR** (hole-punching) intenta después upgradear esa conexión relevada a directa.
- **Transporte sobre WebSocket seguro (`wss`) vía Cloudflare Tunnel**, porque el nodo de
  infra no tiene IP pública: `/dns4/krypta.neto.chat/tcp/443/wss/p2p/<PeerID>`. El bucle
  WAN de la app es *auto-sanador* (reconecta cada 30–180 s, adaptativo según si el stream
  de aviso está vivo) porque Cloudflare Free recicla los WebSockets a los ~100 s de
  inactividad / ~10 min en total.
- **Multi-nodo**: la lista de bootstrap admite varios nodos; el buzón hace *fallback* al
  depositar y **drena todos** los nodos alcanzables al leer (una entrega puede haber
  caído en cualquiera).

### 3.4 Mensajería, buzón y aviso (wake)

1. `ChatService` cifra un `MessageEnvelope` (incluye el id del mensaje del emisor) y lo
   persiste en Room como `PENDING`.
2. Intenta **enviar directo** por el stream libp2p (`/krypta/msg/1.0.0`). Si funciona → `SENT`.
3. Si falla, **deposita el ciphertext en el buzón** del nodo (`/krypta/mbx/put/1.0.0`) → `SENT`.
   El nodo solo guarda blobs opacos, con cuota (≤200 msgs / 5 MiB por destinatario, blob
   ≤64 KiB, TTL 7 días) y autentica la operación con la identidad libp2p del stream (un GET
   solo devuelve tus propios envelopes; el remitente lo fija el nodo, no falsificable).
4. El receptor recorre su buzón en cada ciclo del bucle WAN (`/krypta/mbx/get/1.0.0`), o al
   instante si tiene el stream de **wake** (`/krypta/wake/1.0.0`) abierto: el nodo empuja un
   aviso al depositar, y el bridge dispara un fetch inmediato.
5. **Ack-after-persist**: el lado Kotlin solo confirma (y borra) del buzón los envelopes que
   ha logrado persistir con éxito; el resto se redistribuye en el siguiente fetch — esto
   corrigió una pérdida real de datos (ver histórico en [architecture.md](architecture.md) /
   CLAUDE.md) causada por un `SharedFlow` de 64 slots que descartaba silenciosamente
   envelopes bajo ráfaga.
6. Los ficheros grandes se trocean en chunks de 48 KiB (envelopes `F`+`K`), se **escriben a
   disco progresivamente** (`DiskFileStore`, escritura atómica tmp+rename, sobrevive a
   muerte de proceso, idempotente ante redelivery) en vez de acumularse en memoria.

### 3.5 Borrado local de datos

Fase 8, sin cambios de protocolo (todo ocurre en el dispositivo):

- `ChatService.clearConversation` borra los mensajes de Room de esa conversación y, por
  cada mensaje con un fichero adjunto, llama a `FileStore.deleteLocal(fileId, localPath)`
  (staging pendiente + directorio ensamblado + la copia propia, p. ej. una nota de voz
  enviada). `DiskFileStore` se niega a borrar nada fuera de `krypta_files/`, porque la ruta
  viene de un descriptor persistido, no de una entrada de usuario.
- `ChatService.deleteContact` vacía la conversación y además borra el contacto de Room. No
  hace falta ninguna cirugía en el bucle WAN: `announceAndFind` relee los contactos de Room
  en cada ciclo, así que el *rendezvous* de ese contacto se detiene solo. Volver a añadirlo
  por su PeerID re-deriva el mismo secreto compartido, pero la verificación hay que
  rehacerla.
- UI: mantener pulsada una conversación abre un diálogo de acciones; el menú **⋮** de la
  cabecera del chat ofrece lo mismo; ambas pasan por un `ConfirmDeleteDialog` destructivo.
  Eliminar desde el propio chat navega hacia atrás y cancela la notificación pendiente de
  ese contacto.
- Borrar un mensaje individual se excluyó deliberadamente (decisión del 17 Jul 2026).
- Cubierto por `ChatServiceTest` (vaciar conserva el contacto / eliminar borra ambos) y
  `DiskFileStoreTest` (`deleteLocal` idempotente, nunca sale del store); verificado en
  vivo en el TECNO con un contacto de prueba.

### 3.6 Segundo plano y entrega fiable

- **Foreground Service** tipo `specialUse` (no `dataSync`, que Android 15 mata a las 6 h) +
  `microphone` durante llamadas — mantiene vivo el nodo libp2p + el stream de aviso con la
  UI cerrada, y puede arrancar desde `BOOT_COMPLETED`.
- **`BootReceiver`** rearma el servicio tras reiniciar el teléfono.
- **`registerDefaultNetworkCallback`** dispara una reconexión inmediata (`kickWan()`) en
  cambios WiFi↔datos móviles.
- **`WifiLock` (FULL_HIGH_PERF)** evita que fabricantes agresivos (Xiaomi, Transsion/TECNO)
  apaguen el WiFi con la pantalla apagada en batería.
- **`HeartbeatReceiver`** (AlarmManager, `setAndAllowWhileIdle`, ~2 min, sin throttling
  gracias a la exención de Doze): red de seguridad final para móviles que suspenden la red
  en 2.º plano pese a lo anterior — hace un `pollOnce()` (reconecta DHT + revisa buzón).
- La supresión de notificaciones mientras la app está visible se controla con un observer
  de `ProcessLifecycleOwner` **en el hilo principal** (`@Volatile uiVisible`).

### 3.7 Llamadas de voz y vídeo (protocolo)

- **Voz**: frames de audio sobre stream libp2p propio (`/krypta/call/1.0.0`, framing
  uint16 + `WithAllowLimitedConn`), señalización (invitar/aceptar/rechazar/colgar/ocupado)
  vía envelopes `C` E2EE por el camino normal directo→buzón. Códec **Opus 48 kHz** o
  **AMR-WB 16 kHz** como *fallback*, anunciado en banda por frame (sin negociación SDP).
  Colchón de jitter ~120 ms.
- **Vídeo**: *toggle* independiente dentro de una llamada activa, stream propio
  (`/krypta/video/1.0.0`, framing uint32, tope 1 MiB por keyframes H.264). H.264 vía
  Camera2 + `MediaCodec` (encoder de superficie), 320×240 / 12 fps / 250 kbps / keyframe
  cada 1 s tras ajuste en pruebas reales; *congestion dropping* consciente de GOP (a partir
  de ~12 frames en cola descarta todo hasta el siguiente keyframe, para no corromper H.264
  y no ahogar el audio).
- Sin WebRTC ni TURN: como Cloudflare no permite UDP, todo el NAT-traversal corre por
  DCUtR/Relay v2, igual que el resto de Krypta.

### 3.8 Infraestructura (nodo de señalización)

- `infra/node`: bootstrap DHT + Circuit Relay v2 + buzón + wake, en Go, pinneado a
  **go-libp2p v0.38 + Go 1.22** para poder compilar contra macOS Catalina (interopera con
  los teléfonos en v0.48). Sin IP pública propia: expuesto vía **Cloudflare Tunnel**
  (`wss` sobre 443, `cloudflared` mapea `krypta.neto.chat → localhost:8081`). Corre bajo
  `launchd` (`KeepAlive`); deploy documentado en [infra/node/README.md](../infra/node/README.md).
- El puente nativo Android (`native-bridge/libp2p`, paquete Go `bridge`) se compila a AAR
  vía gomobile (`native-bridge/libp2p/build-aar.sh`), con restricciones de tipos
  gomobile-friendly (sin mapas/slices de structs, sin canales cruzando el binding — se
  usan interfaces callback).

### 3.9 Toolchain y build

- **AGP 9.2.1, Kotlin 2.2.10, Gradle 9.4.1, Compose BOM 2026.02.01, compileSdk 36.1,
  minSdk 30, targetSdk 36, JDK 25.** Versiones centralizadas en
  [gradle/libs.versions.toml](../gradle/libs.versions.toml).
- DI = Hilt 2.59.2 + KSP (no kapt); persistencia = Room 2.8.4 con **migraciones reales**
  (nunca destructivas salvo el v1 histórico) en `data/Migrations.kt`.
- Build de release con **R8** activado; reglas críticas en
  [app/proguard-rules.pro](../app/proguard-rules.pro) preservan `go.**` y
  `chat.neto.krypta.bridge.**` (el puente JNI de gomobile resuelve esas clases por nombre
  en tiempo de ejecución).
- Comandos habituales: `./gradlew :app:assembleDebug`, `./gradlew testDebugUnitTest`,
  `./gradlew :app:installDebug`. Detalle completo en [../CLAUDE.md](../CLAUDE.md).

### 3.10 Estado y pendientes

Ver el detalle exhaustivo, con qué está verificado en dispositivo real y qué queda por
probar con dos teléfonos, en [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md). En resumen,
a fecha de este documento **falta**:

- Segundo nodo de infraestructura desplegado (hoy solo hay uno).
- Verificación de DCUtR (upgrade a conexión directa) en datos móviles con NAT real (2 SIMs).
- Adaptación de bitrate en vídeo, integración con Telecom (`ConnectionService`,
  tipo de FGS `phoneCall`), pulido de rotación/espejo en vídeo.
- Publicación en Google Play (ver [§5](#5-requisitos-de-google-play-para-publicar-krypta)).

---

## 4. Comparativa con WhatsApp y Signal

| Aspecto | **Krypta** | **Signal** | **WhatsApp** |
|---|---|---|---|
| Cifrado extremo a extremo | Sí (X25519 ECDH + HKDF + AES-256-GCM, por par de contactos). Desde el 10 sep 2026, doble trinquete propio por épocas entre clientes actualizados — protocolo propio, **sin auditoría externa todavía** | Sí (Signal Protocol, doble trinquete) | Sí (Signal Protocol con licencia) |
| Servidor central | **No** — enrutamiento P2P (libp2p/DHT); un nodo propio solo relevo/buzón cifrado y opcional | Sí — servidor central de Signal Foundation (código servidor abierto) | Sí — servidores de Meta (propietario) |
| Identidad | Par de claves generado en el dispositivo; el PeerID **es** la clave pública, sin número de teléfono | Número de teléfono (con *usernames* opcionales recientes) | Número de teléfono obligatorio |
| Descubrimiento de contactos | Manual: compartes tu PeerID por otro canal; sin directorio central | Servidor central + *contact discovery* con hashing/SGX | Servidor central, sube tu agenda |
| Multi-dispositivo | No (una identidad = un dispositivo; hay backup/restauración manual) | Sí (vinculación de dispositivos) | Sí (multi-dispositivo nativo) |
| Metadatos visibles al operador | **No son mínimos**: el nodo tiene el grafo diario de parejas (vía rendezvous, incluso si el tráfico va directo y no pasa por él), la presencia, la IP, y qué dos PeerID hablan y cuánto cuando hay relevo. El buzón va bajo etiqueta opaca entre clientes actualizados. El nodo es reemplazable/auto-hospedable | Diseño *sealed sender* minimiza metadatos, pero corre en infraestructura centralizada | Meta ve metadatos de comunicación (a quién, cuándo) aunque no el contenido |
| Código abierto | Sí (app + puente Go + nodo de infra, todo en este repo) | Sí (cliente y servidor) | No (cliente y servidor propietarios) |
| Backup de historial | Copia de la **identidad + contactos** cifrada localmente (`.krbk`); el historial de mensajes no sale del dispositivo | Backup local cifrado (o en la nube cifrado con PIN) | Backup en Google Drive/iCloud (cifrado opcional) |
| Llamadas voz/vídeo | Sí, sobre el propio transporte P2P/relay (sin WebRTC/TURN) | Sí (WebRTC) | Sí (WebRTC) |
| Verificación de identidad | Número de seguridad (60 dígitos) + QR, estilo Signal | Número de seguridad + QR | Código QR / número de seguridad |
| Madurez / usuarios | Proyecto propio en fase beta, un solo nodo de infra desplegado | Producto maduro, cientos de millones de usuarios, auditado externamente | Producto maduro, ~2 mil millones de usuarios |
| Modelo de confianza | Confías en la criptografía y en que el (o los) nodo(s) de relevo no pueden leer contenido — pero hoy son operados por el propio proyecto, no por terceros independientes auditados | Confías en Signal Foundation (sin ánimo de lucro, código auditado) | Confías en Meta (empresa con modelo de negocio publicitario) |

**Lectura rápida:** Krypta se diferencia principalmente en que **no necesita ningún
servidor central para enrutar mensajes** — algo que ni Signal ni WhatsApp ofrecen, ambos
dependientes de infraestructura centralizada propia — a cambio de ser un proyecto mucho
más joven, sin auditoría de seguridad externa todavía, sin multi-dispositivo, y con una
única pieza de infraestructura (el nodo bootstrap/relay) que hoy es un punto único
operado por el propio autor.

---

## 5. Requisitos de Google Play para publicar Krypta

Esta sección resume lo que Google Play pedirá al dar de alta Krypta como app en la
**Play Console**. Son requisitos de la plataforma (pueden cambiar; conviene revisar la
Play Console en el momento de publicar), organizados según lo que ya cumple el proyecto y
lo que falta preparar.

### 5.1 Cuenta de desarrollador

- Alta como **cuenta de desarrollador individual u organización** en Play Console (cuota
  única de registro; Google exige verificación de identidad — documento oficial, y para
  cuentas nuevas puede pedir un periodo de espera/verificación adicional).
- Si se publica como organización, Google puede pedir verificación **D-U-N-S** o documento
  mercantil equivalente.

### 5.2 Requisitos técnicos del build

| Requisito de Play | Estado en Krypta |
|---|---|
| Formato **Android App Bundle (.aab)**, no APK suelto | Falta configurar: hoy el proyecto genera APK (`assembleDebug`/`assembleRelease`); hace falta `./gradlew :app:bundleRelease` y firmar con un keystore real (App Signing) |
| **`targetSdk`** dentro de la ventana vigente de Google Play (a mitad de 2026, exige apuntar a una API reciente, revisar el mínimo exacto en la Play Console) | `targetSdk = 36` ✅, ya por encima de cualquier mínimo esperable |
| Soporte de **64-bit** (`arm64-v8a`) | ✅ el AAR de libp2p compila para las 4 ABIs; hay flavor `-PslimAbi` solo-arm64 para pruebas, pero el *release* que se suba a Play debe incluir todas las ABIs relevantes (o usar `.aab` con entrega dinámica por ABI, que Play gestiona solo) |
| **Firma de la app** (Play App Signing) | Pendiente: hoy el build de *release* firma con el **keystore de depuración** (`signingConfig = signingConfigs.getByName("debug")`) — **hay que generar un keystore de producción propio** antes de subir nada a Play; Play App Signing gestiona después la clave de distribución |
| Tamaño del binario | El AAR de libp2p pesa ~65 MB (`libgojni.so` ~34 MB × 4 ABIs); con `.aab` Play sirve solo la ABI del dispositivo, mitigando el problema |

### 5.3 Permisos sensibles y su declaración

Krypta declara varios permisos que Play revisa con atención especial (formulario de
**Declaraciones de permisos** en Play Console, por cada uno):

- `RECORD_AUDIO`, `CAMERA` — uso normal (permiso en tiempo de ejecución con propósito
  claro: notas de voz/llamadas, escaneo de QR de verificación). Hay que justificarlos en
  el formulario y en la política de privacidad.
- `FOREGROUND_SERVICE_SPECIAL_USE` — **requiere una declaración explícita** en Play
  Console (el "Foreground Service permission declaration form"), explicando por qué el
  caso de uso no encaja en ningún tipo estándar (`dataSync`, `mediaPlayback`, etc.). El
  manifiesto ya incluye el `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` con la justificación en
  texto ("Conexión P2P persistente (libp2p) para recibir mensajes E2EE sin servidores de
  push de terceros") — es el argumento a repetir en el formulario de Play.
- `FOREGROUND_SERVICE_MICROPHONE` — declaración de **tipo de servicio en primer plano**
  con micrófono (llamadas); Play pide justificar por qué necesita el micro en 2.º plano.
- `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `MODIFY_AUDIO_SETTINGS`, `USE_BIOMETRIC` — no
  requieren declaración especial normalmente, pero deben quedar explicados en la política
  de privacidad y en la descripción de la app (por qué una app de mensajería pide ignorar
  la optimización de batería, por ejemplo; `USE_BIOMETRIC` es el bloqueo de acceso opcional
  y no sale del dispositivo).

### 5.4 Política de privacidad (obligatoria)

Google exige una **URL pública** con la política de privacidad, enlazada desde la ficha
de Play Store. Debe describir, entre otras cosas:

- Qué datos recoge la app y con qué fin (en Krypta: PeerID de contactos añadidos
  localmente, identidad criptográfica generada en el dispositivo; **no hay servidor con
  cuentas de usuario ni recolección de mensajes en claro**).
- Qué ve el nodo de infraestructura (metadatos de enrutamiento: el grafo diario de parejas,
  la presencia, la IP, el tamaño de blob y el timestamp, más los PeerID de origen y destino en
  el buzón cuando el contacto no está actualizado; blobs cifrados, nunca contenido legible) y su
  política de retención (TTL de 7 días, cuotas).
- Que no hay compartición con terceros ni publicidad.
- Datos de diagnóstico si en algún momento se añade *crash reporting*/analítica (hoy el
  proyecto no integra ninguno; si se añade, hay que declararlo aquí y en el formulario de
  seguridad de datos).

### 5.5 Formulario de seguridad de datos (Data Safety)

Cuestionario obligatorio en Play Console sobre qué datos se **recogen, comparten y cómo
se protegen**. Para Krypta, previsiblemente:

- "¿Recoge la app datos del usuario?" → los mínimos imprescindibles para operar (PeerID de
  contactos guardados localmente; no hay email/teléfono/nombre real requerido por la app).
- "¿Los datos viajan cifrados en tránsito?" → Sí (E2EE de contenido; TLS/`wss` para las
  conexiones al nodo).
- "¿Puede el usuario pedir borrado de datos?" → Sí, desinstalar borra todo lo local; el
  nodo de infra borra los blobs del buzón automáticamente por TTL (documentar el plazo).
- Declarar explícitamente que **no hay servidor con base de datos de usuarios** más allá
  del nodo de señalización/relay/buzón cifrado (esto es una ventaja competitiva a
  destacar, pero también algo que Google pedirá describir con precisión técnica).

### 5.6 Clasificación de contenido (IARC)

Cuestionario estándar de clasificación por edades (violencia, lenguaje, contenido
generado por usuarios, etc.). Al ser una app de **mensajería con contenido generado por
el usuario no moderado** (texto/foto/audio/vídeo libre entre contactos), es previsible que
el cuestionario suba la clasificación mínima ligeramente (equivalente a "Comunicación
social" con advertencia de contenido generado por usuarios), igual que ocurre con
WhatsApp/Signal/Telegram. No requiere moderación previa por parte de Krypta porque es
comunicación 1:1 cifrada (Google no puede exigir moderación de contenido que ni el propio
desarrollador puede leer), pero sí exige un mecanismo de **bloqueo/reporte de abuso** a
nivel de cuenta — hoy Krypta permite **eliminar un contacto** ([§2.10](#210-vaciar-un-chat-o-eliminar-un-contacto),
que detiene el *rendezvous* y borra el historial), pero eso no equivale a "bloquear"
(nada impide que ese PeerID te vuelva a añadir a ti) ni a "reportar": **es recomendable
añadir ambas antes de publicar**, dado que Play Console suele preguntar por mecanismos de
moderación en apps de comunicación con usuario generado.

### 5.7 Cumplimiento de exportación de cifrado

Al usar cifrado fuerte (AES-256, Ed25519/X25519), Play Console pide responder el
cuestionario de **cumplimiento de exportación** (basado en el marco EAR de EE. UU.):
Krypta encaja en la categoría estándar de "app de mensajería con cifrado disponible
públicamente" (equivalente a la excepción **EAR99 / auto-clasificación 5D992**, la misma
que usan Signal/WhatsApp/Telegram), lo que normalmente no exige licencia adicional, solo
la declaración en el formulario. Conviene confirmarlo en el momento de publicar por si el
marco regulatorio ha cambiado.

### 5.8 Assets de la ficha de Play Store

Hay que preparar (hoy no existen en el repo):

- Icono de la app en **512×512 px** (32-bit PNG).
- *Feature graphic* **1024×500 px**.
- Al menos **2 capturas de pantalla** de teléfono (recomendable: conversaciones, chat,
  verificación por QR, llamada); PNG/JPEG, proporciones dentro de lo que exige Play.
- Descripción corta (≤80 caracteres) y descripción larga (≤4000 caracteres) — buen sitio
  para explicar el enfoque descentralizado/E2EE sin prometer más seguridad de la que hoy
  está auditada.
- Categoría: **Comunicación**.
- Selección de países de distribución — algunos países restringen o prohíben apps de
  cifrado fuerte / VoIP; conviene revisar restricciones locales antes de distribuir
  globalmente.

### 5.9 Testing progresivo en Play Console

Antes de producción, Play permite (y para cuentas nuevas a veces exige) pasar por pistas
de **prueba interna → cerrada → abierta** con un número mínimo de testers activos durante
un tiempo mínimo (política de "acceso anticipado" para cuentas de desarrollador nuevas).
Conviene planificarlo con margen, ya que añade semanas al calendario de publicación.

### 5.10 Resumen de pendientes para publicar

- [ ] Generar keystore de producción propio y configurar Play App Signing (hoy firma con
      el keystore de depuración).
- [ ] Migrar el build de release a **Android App Bundle** (`bundleRelease`).
- [ ] Redactar y publicar la **política de privacidad** (URL pública).
- [ ] Rellenar el formulario de **Data Safety**.
- [ ] Rellenar la **declaración de Foreground Service** (`specialUse` + `microphone`).
- [ ] Responder el cuestionario de **cumplimiento de exportación de cifrado**.
- [ ] Completar el cuestionario de **clasificación de contenido (IARC)**.
- [ ] Añadir alguna forma de **bloquear/reportar** un contacto (previsible pedido de Play
      para apps de comunicación con contenido de usuario no moderado).
- [ ] Preparar icono, *feature graphic*, capturas y descripciones de la ficha.
- [ ] Verificar la cuenta de desarrollador y planificar el rodaje por pistas de *testing*.
- [ ] Desplegar al menos un **segundo nodo de infraestructura** (recomendable antes de un
      lanzamiento público, para no depender de un único punto de fallo operado por una
      sola persona — hoy solo hay un nodo, ver [§3.10](#310-estado-y-pendientes)).

---

## 6. Glosario

- **E2EE**: cifrado de extremo a extremo — solo emisor y receptor pueden leer el contenido.
- **libp2p**: pila de red P2P modular (la misma familia que usa IPFS) sobre la que corre
  el transporte de Krypta.
- **DHT (tabla hash distribuida)**: mecanismo Kademlia que usa Krypta para que dos peers
  se encuentren sin directorio central.
- **Rendezvous**: punto de encuentro diario en la DHT, derivado por HKDF del secreto
  compartido de cada par de contactos.
- **Circuit Relay v2 / DCUtR**: mecanismos de libp2p para atravesar NAT — relevar tráfico
  a través de un tercero, y luego intentar abrir una conexión directa (*hole punching*).
- **PeerID**: identificador público de un nodo/usuario en libp2p; en Krypta es
  directamente la clave pública Ed25519.
- **HKDF**: función de derivación de claves (RFC 5869) usada para obtener claves de
  cifrado y puntos de rendezvous a partir de un secreto compartido.
- **Mailbox (buzón)**: almacén cifrado de guarda-y-reenvía en el nodo de infraestructura,
  para entregar mensajes a un contacto que está offline.
- **Wake**: aviso instantáneo desde el nodo hacia el teléfono cuando llega algo nuevo al
  buzón, para no depender de sondeo periódico.
- **FGS (Foreground Service)**: servicio Android que se mantiene visible con una
  notificación persistente para poder seguir funcionando en segundo plano.
- **Safety number (número de seguridad)**: código de verificación de identidad derivado
  de las claves públicas de ambos contactos, usado para detectar suplantación.
