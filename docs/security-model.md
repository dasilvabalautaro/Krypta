# Modelo de seguridad de Krypta

**Última actualización:** 8 de septiembre de 2026 (§7: el secreto compartido **ya no se
guarda** — se deriva de la identidad al leer, así que el fichero de la base de datos por sí
solo ya no abre el historial; ese mismo día se había corregido aquí la afirmación contraria).
**Estado:** entregable pendiente de la Fase 6 del
[plan](PLAN-senalizacion-descentralizada.md), abierto desde el inicio del proyecto y escrito
a raíz de la [auditoría del 7 de septiembre de 2026](AUDITORIA-2026-09-07.md) (hallazgo A-6).

Este documento dice **qué protege Krypta, contra quién, y qué no protege**. Está escrito para
poder contrastarlo con la [política de privacidad](politica-privacidad.html): si algo de aquí
contradice lo que se le promete al usuario, lo que hay que corregir es la promesa.

---

## 1. Qué es un mensaje de Krypta

```
texto/foto/archivo/voz
   │  sobre de aplicación (MessageEnvelope: tipo + id + cuerpo)
   ▼
AES-256-GCM  ·  clave = HKDF(ECDH_X25519(mi identidad, PeerID del contacto))
   │  ciphertext opaco
   ▼
stream libp2p (Noise)  ──directo──▶  el otro móvil
        └──si no hay ruta──▶  buzón del nodo  ──▶  el otro móvil lo retira
```

Hay **dos capas de cifrado independientes**: la de transporte (Noise, entre cada par de nodos
libp2p, incluido el salto al relay) y la de aplicación (AES-GCM extremo a extremo). La primera
la termina el nodo de infraestructura cuando actúa de relay o de buzón; **la segunda no la
termina nadie más que los dos móviles**. Todo lo que sigue trata de qué pasa cuando alguien
ataca cada una de esas capas o lo que las rodea.

---

## 2. Actores y qué confianza requiere cada uno

| Actor | Qué puede ver | Qué puede hacer | Confianza requerida |
|---|---|---|---|
| **Los dos extremos** | Todo el contenido | Todo | Total (es el otro lado de la conversación) |
| **Nodo de infraestructura** (bootstrap + DHT + relay + buzón + wake) | Ciphertext opaco, tamaños, **quién deposita para quién**, **cuándo está conectado cada PeerID** | Retrasar o perder mensajes; negar el servicio; **no** leer, no alterar sin que se note (GCM), no suplantar a un remitente | **Ninguna para la confidencialidad**; sí para la disponibilidad |
| **Red / operador / WiFi** | Que hablas con un nodo de Krypta y cuánto tráfico | Cortar; retrasar; nada más | Ninguna |
| **Observador de la DHT** | Claves de rendezvous y qué PeerID las anuncia | Correlacionar PeerIDs con puntos de cita (ver §5) | Ninguna |
| **Otro contacto tuyo** | Lo que le mandas | Reenviarlo; guardarlo; hacerte spam de archivos | La que le des al añadirlo |
| **Un desconocido con tu PeerID** | Nada del contenido | Intentar entregarte cosas (se descartan), ocupar tu buzón (§6) | Ninguna |
| **Quien tenga tu móvil desbloqueado** | Todo | Todo | Total — ver §7 |

---

## 3. Identidad y acuerdo de claves

La identidad es **un par de claves Ed25519** generado en el dispositivo en el primer arranque.
El **PeerID es la clave pública** (va embebida en él), así que:

- No hay servidor de directorio ni cuenta: añadir a alguien es pegar su PeerID.
- El acuerdo de claves **no tiene intermediario posible en la matemática**: el secreto
  compartido sale de tu privada y de la pública que *ya está dentro* del PeerID del contacto
  (`Bridge.SharedSecretFor`, X25519 sobre la conversión Edwards→Montgomery).
- Por tanto **el único ataque de suplantación es sustituir el PeerID** en el canal por el que
  se comparte (un WhatsApp interceptado, un papel cambiado). Contra eso está el **número de
  seguridad** (`SafetyNumber`, 60 dígitos derivados de ambos PeerIDs, simétrico) y el **QR** de
  verificación. Cotejarlo fuera de banda cierra el hueco; no cotejarlo lo deja abierto.

**Consecuencia dura:** el PeerID *es* la identidad y **no hay revocación**. Si alguien te roba
la identidad, no puedes invalidarla: hay que generar otra y que todos los contactos te
vuelvan a añadir. Por eso el §7 (identidad en reposo) es la parte más crítica del modelo.

---

## 4. Cifrado del contenido, y sus límites

- **AES-256-GCM**, nonce aleatorio de 96 bits antepuesto, tag de 128 bits. Un mensaje
  manipulado **falla la autenticación y se descarta**; no se pinta nada a medias.
- Clave = `HKDF-SHA256(secreto_compartido, info="krypta-msg-key-v1")`.
- Las **llamadas** usan una clave por llamada, `HKDF(secreto, callId)`, y cada frame de audio y
  de vídeo va cifrado con ella. El relay solo mueve bytes opacos.

### Lo que NO se garantiza (importante)

- **No hay secreto hacia adelante (PFS).** La clave de un contacto es estática mientras dure
  la identidad: no hay ratchet ni claves efímeras. Quien obtenga tu identidad **puede descifrar
  todo lo que tengas guardado** y todo lo que hubiera capturado antes. Signal y WhatsApp sí
  tienen ratchet; Krypta, hoy, no. Es una decisión consciente de alcance (v1), no un descuido,
  y está anotada en el código (`AesGcmMessageCipher`).
- **No hay negación (deniability) ni protección de metadatos por diseño.** Ver §5 y §6.
- **No se oculta el tamaño.** Un mensaje corto y una foto se distinguen por el número de bytes
  que pasan por el relay, y un archivo troceado se ve como una ráfaga de depósitos de 48 KiB.

---

## 5. Descubrimiento: qué ve la DHT

El punto de cita entre dos contactos es `rendezvous = HKDF(secreto_compartido, fecha)`
(`RendezvousService`). Propiedades:

- **No enumerable**: sin el secreto compartido no se puede calcular ni buscar; la DHT nunca ve
  números de teléfono, correos ni identificadores estables del usuario.
- **Rotativo por día**: la clave de hoy no se puede ligar con la de ayer sin el secreto, lo que
  acota la ventana en la que un observador de la DHT puede correlacionar.
- **Ventana de solape**: durante dos horas a cada lado de la medianoche UTC se usan las dos
  claves contiguas, para que dos móviles que roten con desfase sigan encontrándose.

Lo que un observador de la DHT **sí** ve: que un PeerID concreto anuncia y busca ciertas claves
opacas. Puede contar cuántas (≈ cuántos contactos activos tienes) y ver cuándo estás en línea.
No puede saber **quiénes** son ni leer nada.

> **Nota histórica (corregida el 8 sep 2026).** Hasta esa fecha, cada ciclo del bucle WAN dejaba
> viva una goroutine de re-anuncio, de modo que las claves de días pasados **se seguían
> publicando indefinidamente**. En la práctica eso anulaba la rotación diaria: un PeerID
> acumulaba decenas de puntos de cita simultáneos, que es justo la huella que la rotación
> existe para impedir. Corregido (hallazgo A-1 de la auditoría).

---

## 6. Metadatos: qué ve el nodo de infraestructura

Esta es la parte que el plan pedía analizar y que faltaba por escrito. **El rendezvous protege
la DHT, pero el camino de entrega diferida no lo usa.**

### 6.1 Buzón (store-and-forward)

Cuando el envío directo falla, el emisor deposita en el nodo un sobre con:

| Campo | Quién lo pone | Qué revela |
|---|---|---|
| `to` | El emisor | **PeerID del destinatario** |
| `from` | **El nodo**, desde la identidad del stream | **PeerID del emisor** (no suplantable) |
| `ts` | El nodo | Hora exacta del depósito |
| `blob` | El emisor | Ciphertext opaco (≤ 64 KiB) |

Es decir: **el nodo sabe quién escribe a quién y cuándo**, y lo guarda en disco hasta que el
destinatario lo retira o hasta 7 días. Con eso se reconstruye el grafo social y los patrones
horarios de quienes usan la entrega diferida. El contenido, no.

Que el nodo fije `from` es deliberado y bueno para la seguridad (nadie puede suplantar a un
remitente), pero tiene este coste en privacidad. Un diseño que lo evitara —depósitos ciegos
bajo una etiqueta derivada del secreto compartido, estilo rendezvous— es posible y no está
hecho; sería el siguiente paso natural de esta sección.

### 6.2 Wake

El móvil mantiene abierto un stream `/krypta/wake/1.0.0`. El nodo, por tanto, **sabe en tiempo
real qué PeerIDs están conectados**. El aviso en sí no lleva payload ni remitente, pero el
patrón de conexión es un registro de presencia bastante fino.

### 6.3 Relay

Cuando DCUtR no perfora el NAT, el tráfico —incluidas las llamadas— pasa por el nodo. El nodo
ve **qué dos PeerIDs hablan entre sí, cuándo y cuánto**, con la granularidad de una conexión.
No ve el contenido (doble cifrado: Noise + E2EE).

### 6.4 Concentración de operador

Los tres nodos de `Libp2pNode.DEFAULT_BOOTSTRAP` los opera **la misma persona** (el autor). Eso
significa que, hoy, un solo operador está en posición de observar todo lo anterior para todos
los usuarios. Es coherente con la decisión de "descentralizar la confianza, no la
infraestructura" —el operador no puede leer nada— pero **no** con una lectura ingenua de
"descentralizado".

Mitigación disponible hoy: el campo "Nodo WAN (bootstrap)" de Ajustes acepta una **lista de
nodos** y es editable, así que cualquiera puede levantar el suyo
([infra/node/README.md](../infra/node/README.md)) y usarlo, solo o combinado. Falta: que dos
usuarios que quieran hablar entre sí compartan al menos un nodo, y una guía de "monta tu nodo"
orientada a usuarios y no a operadores.

---

## 7. El dispositivo

| Dato | Dónde | Protección |
|---|---|---|
| Identidad Ed25519 | `krypta_identity` (prefs) | **Envuelta con una clave AES del Android Keystore** (TEE, no exportable). Desde el 8 sep 2026; antes estaba en claro. La migración es automática y solo borra la copia en claro tras verificar que la envuelta se recupera igual (`IdentityStore`) |
| Mensajes (ciphertext) y contactos | `krypta.db` (Room) | Almacenamiento privado de la app. **Sin cifrar** (no hay SQLCipher) |
| Adjuntos, notas de voz, GIF | `filesDir/krypta_files/` | Almacenamiento privado. **Sin cifrar** |
| **Secreto compartido por contacto** | **En ningún sitio** (memoria durante la ejecución) | Se **deriva** por ECDH de la identidad y del PeerID cada vez que se lee el contacto (DB v6, 8 sep 2026). Antes se guardaba en claro y bastaba el fichero para descifrar todo el historial |
| Copia de seguridad `.krbk` | Donde el usuario elija | AES-256-GCM con clave PBKDF2-HMAC-SHA256 (310k iteraciones) de la frase-clave del usuario |

Notas:

- **La clave del Keystore no exige autenticación del usuario** y a propósito: Krypta tiene que
  poder recibir mensajes y llamadas con el móvil bloqueado. Lo que protege es la **extracción
  en frío** (copiar el fichero de otro dispositivo, forense, malware sin uso del TEE), no a
  quien ya controla el móvil desbloqueado.
- **El bloqueo de app (`AppLock`) no cifra nada**: es una puerta de la interfaz, con
  `BiometricPrompt`. Útil contra quien coge el móvil un momento; inútil contra quien extrae los
  datos.
- **El fichero de la base de datos ya no abre el historial.** Hasta la v6, `contacts` guardaba
  el secreto compartido en claro, y como la clave de cada mensaje sale de él por HKDF, quien se
  llevara `krypta.db` descifraba todas las conversaciones sin tocar la identidad ni ejecutar
  nada dentro de la app. Ahora ese valor **no se persiste**: se deriva por ECDH al leer el
  contacto (es función pura de la identidad y del PeerID), así que la confidencialidad del
  historial vuelve a depender de la identidad, que vive envuelta en el TEE. La migración quitó
  además la columna con `secure_delete` activo, para que las páginas liberadas no conservaran
  los secretos anteriores — comprobado en el dispositivo: la cadena ya no aparece ni en el
  fichero ni en el WAL.

  Importa saber cómo se llega a ese fichero, porque no es solo el escenario forense: hace falta
  **root**, una extracción, un fallo del sistema… **o una compilación de depuración**, donde
  `adb shell run-as chat.neto.krypta` lee el directorio entero con solo tener el móvil
  desbloqueado y la depuración USB activa. Las de release no son depurables. Es una diferencia
  que importa al repartir APK de prueba.

- **Room sigue sin cifrar**, y lo que eso expone ahora son **metadatos locales**: nombres de
  contacto, PeerIDs, marcas de tiempo, quién habla con quién y el tamaño de cada mensaje. El
  contenido ya no. Cifrar la base con una clave del Keystore (SQLCipher) sigue siendo trabajo
  pendiente, pero ha bajado de "el punto débil" a "lo siguiente". Los adjuntos de
  `krypta_files/` están en claro por definición: son la foto, el PDF o la nota de voz.
- `android:allowBackup="false"`: nada de esto sube a Google Drive. La única copia es el `.krbk`.
- **`FLAG_SECURE` en la pantalla de chat**: sin capturas, sin grabación, sin miniatura en
  recientes, sin proyección a pantallas no seguras. Solo en el chat, que es donde está el
  contenido. Una captura que el usuario haga desde el propio chat sale del ámbito E2EE, y así
  se dice en la ayuda.

---

## 8. Abuso y disponibilidad

El nodo es infraestructura pública: cualquiera de internet puede hablarle. Lo que hay hoy:

| Superficie | Límite |
|---|---|
| Stream de mensajes `/krypta/msg/1.0.0` | Lectura acotada a 1 MiB (móvil y nodo) |
| Depósito en buzón | Blob ≤ 64 KiB; 200 mensajes / 5 MiB por destinatario; TTL 7 días |
| Depósito en buzón, **por remitente** | Un remitente puede llenar el buzón solo si es el único que ha depositado; en cuanto hay otro, ninguno pasa de la mitad, y si el buzón se llena se desaloja lo más antiguo de quien se pasó de su reparto (`mailbox.store`) |
| Retirada del buzón | Solo entrega los sobres cuyo `to` es el PeerID autenticado del stream |
| Depósito en buzón, **por ritmo** | Cubo de fichas por remitente: ráfaga de 256 depósitos y una ficha por segundo. La ráfaga cubre de sobra un archivo troceado (~110 trozos); el régimen sostenido corta la avalancha |
| Wake | Una suscripción por peer; máximo 2000 simultáneas |
| Relay | 8 GiB y 6 h por conexión relayada; 4096 reservas, 256 por IP, 2048 por ASN |
| Lecturas de línea (buzón/wake en el móvil) | Búfer fijo (128 KiB / 4 KiB): un nodo que no cierre línea no puede hacer crecer la memoria |

**Vigilancia**: [`infra/node/check-nodes.sh`](../infra/node/check-nodes.sh) comprueba los tres
nodos —buzón, wake, relay con límites finitos e ida y vuelta real— y sale con error si alguno
falla, pensado para cron/launchd. Existe porque hasta ahora nadie se enteraba de nada: el 8 sep
2026 el nodo primario pasó dos días con un binario viejo y se descubrió mirando a mano.

**Lo que sigue abierto**: no hay lista de control de acceso, ni límite de ritmo en la
*retirada* del buzón (solo en el depósito), ni alertas automáticas —el chequeo hay que
programarlo—. Un atacante decidido puede seguir generando carga; lo que ya no puede es **dejar
a un usuario sin entrega**, usar el relay como proxy ilimitado ni machacar el nodo a
escrituras.

---

## 9. Resumen honesto de lo que Krypta NO protege

1. **Metadatos de la entrega diferida y del relay**: el operador del nodo ve quién habla con
   quién y cuándo (§6). Es el hueco más grande del modelo.
2. **El pasado, si te roban la identidad**: sin PFS, comprometer el dispositivo descifra todo
   el historial guardado (§4).
3. **Los metadatos locales frente a quien consiga el fichero de la base de datos** (§7): el
   contenido ya no se puede descifrar solo con `krypta.db`, pero sí se lee con quién habla,
   cuándo y cuánto. Y nada de esto protege frente a código ejecutándose **dentro** del proceso
   o con root: ahí el atacante le pide la clave al TEE igual que se la pide la app.
4. **Al contacto**: nada impide que quien recibe tus mensajes los guarde, los reenvíe o los
   fotografíe con otra cámara.
5. **La disponibilidad**: los nodos son pocos y de un solo operador; si caen todos, la entrega
   diferida y el relay se detienen (la entrega directa entre dos móviles alcanzables, no).
6. **El análisis de tráfico a gran escala**: no hay tráfico de relleno, ni batching, ni mezcla.
   Quien observe la red y el nodo a la vez puede correlacionar por tiempos.

---

## 10. Cambios que este documento pide (pendientes)

- **Depósito ciego en el buzón** (etiqueta derivada del secreto compartido en lugar de PeerID
  en claro), para que el grafo social deje de quedar escrito en el disco del nodo. Diseño
  redactado y pendiente de decisión en [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) — con una
  advertencia importante: **el relay filtra ese mismo grafo** y eso no lo arregla, así que lo
  que se gana es que no quede en disco, no que el operador no pueda saberlo en vivo.
- **PFS** (Noise/doble ratchet) para el contenido.
- **Cifrar Room** con clave del Keystore. Ya no protege el contenido —el secreto compartido
  salió de la base el 8 sep 2026— sino los metadatos locales: con quién habla, cuándo y cuánto.
- **Alertas** de verdad para el chequeo de nodos (hoy es un script que hay que programar), y
  límite de ritmo también en la retirada del buzón.
- **Diversidad de operadores**: guía de "monta tu nodo" para usuarios, y una forma cómoda de
  que dos contactos acuerden qué nodos usan.
