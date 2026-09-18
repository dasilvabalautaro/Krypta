# Modelo de seguridad de Krypta

**Nota del 18 de septiembre de 2026:** el §6.2 se amplía con lo que el **wake v2** le entrega al
nodo además de la presencia (el conjunto entero de etiquetas del suscriptor, atado a su PeerID),
que es la correlación en vivo del §6.1 en un solo paso. No cambia ningún mecanismo: es algo que
el depósito ciego nunca ocultó y que no estaba escrito.

**Última actualización:** 14 de septiembre de 2026 — **revisión del protocolo**. Se arreglaron dos
fallos de implementación del ratchet (H-0: clave y nonce repetidos con operaciones simultáneas;
H-1: mensajes perdidos para siempre tras volver a añadir un contacto) y se declararon dos límites de
diseño frente a quien roba una identidad y actúa (§9, puntos 11 y 12;
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md)). La especificación normativa
del protocolo está en [ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md).

**Actualización anterior:** 10 de septiembre de 2026 — **cifrado en reposo y ratchet**. Desde la
revisión previa: la base de datos va cifrada entera (SQLCipher, 9 sep), los adjuntos también
(9 sep), la clave de cada llamada se negocia en vez de derivarse de la identidad (9 sep), y el
**doble ratchet está desplegado con el envío encendido** (10 sep) — pero **por pareja** y **sin
prueba en dos móviles reales todavía**, así que §4 lo cuenta como mecanismo, no como garantía.
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
(Una rotación y revocación firmadas con la identidad anterior están **diseñadas, sin
implementar**, en [DISENO-rotacion-identidad.md](DISENO-rotacion-identidad.md); este párrafo
sigue siendo cierto hasta que pasen las pruebas con dos móviles.)

---

## 4. Cifrado del contenido, y sus límites

- **AES-256-GCM**, nonce aleatorio de 96 bits antepuesto, tag de 128 bits. Un mensaje
  manipulado **falla la autenticación y se descarta**; no se pinta nada a medias.
- Clave = `HKDF-SHA256(secreto_compartido, info="krypta-msg-key-v1")`.
- Las **llamadas** usan una clave por llamada y cada frame de audio y de vídeo va cifrado con
  ella; el relay solo mueve bytes opacos. Desde el 9 sep 2026 esa clave **se negocia**: cada lado
  sortea 32 bytes al azar y los manda dentro del sobre de señalización (invite/accept), y la
  clave sale de las dos mitades. Antes se derivaba del secreto estático, así que quien robara la
  identidad podía abrir cualquier llamada que hubiera grabado; ahora necesita además el sobre de
  señalización de esa llamada concreta. Con un contacto que aún no lo entiende se sigue por el
  camino antiguo.
- Los frames de una llamada **no llevan contador propio**. Lo que impide que el relay los repita,
  los reordene o se los devuelva a quien los mandó es que el stream va dentro del cifrado de
  transporte de libp2p (TLS 1.3 o Noise) de teléfono a teléfono, también cuando pasa por un relay.
  Comprobado con tests el 15 sep 2026: manipular esos bytes corta la conexión, y el relay no ve lo
  que viaja por el circuito. Y un **invite de llamada repetido** ya no suma otra «llamada perdida»,
  y no vuelve a sonar salvo en un caso: si la app se reinicia en los 10 min 45 s siguientes, puede
  sonar una vez más (H-7 de la revisión del protocolo, y W-14 de la especificación).

### Lo que NO se garantiza (importante)

- **El secreto hacia adelante (PFS) existe, pero solo por pareja y probado en vivo a medias.**
  Krypta tiene un **doble ratchet por épocas** (ver [DISENO-ratchet.md](DISENO-ratchet.md)) y el
  envío se encendió el 10 sep 2026. Ahora bien:
  - **se usa solo con los contactos cuya app también lo anuncia**; con el resto la clave sigue
    siendo estática mientras dure la identidad, y para ellos sigue siendo cierto que **quien
    obtenga tu identidad puede descifrar lo que haya capturado de la red** (lo guardado en el
    móvil lo protege el cifrado de la base, §7 y §9.2);
  - **entre dos móviles reales solo se ha verificado la conversación normal** (10 sep 2026). Los
    escenarios que de verdad podían romperlo —reentrega del buzón, un archivo grande cruzando un
    cambio de época, pérdida de estado con importación de `.krbk`, y una llamada— siguen
    pendientes (`PRUEBAS-PENDIENTES` §16), así que este documento no lo cuenta aún como una
    garantía, solo como un mecanismo desplegado;
  - **no es retroactivo**: los mensajes anteriores no ganan PFS, y el historial guardado se
    protege con el cifrado de la base (§7), no con el ratchet.
- **La negación (deniability) no se promete, y tampoco se ha renunciado a ella.** Los sobres se
  autentican con el secreto compartido de la pareja, no con una firma, así que cualquiera de los
  dos pudo fabricarlos: frente a un tercero son negables de hecho. Pero no hay análisis que lo
  respalde, y los metadatos (§5 y §6) pueden delatar igualmente quién habló con quién. Firmar los
  sobres para resistir KCI (§9, punto 11) la perdería; se decide con la revisión externa.
- **No hay protección de metadatos por diseño.** Ver §5 y §6.
- **El tamaño se cuantiza, no se oculta.** Desde el 11 sep 2026 el texto en claro va **relleno
  por tramos** dentro del cifrado (160 B hasta 4 KiB, 1 KiB hasta 64 KiB), así que los mensajes
  de control —acuses de lectura, anuncios de capacidad, señales de llamada— y los textos cortos
  **pasan a medir todos lo mismo**, y con ellos se va la estructura que antes se leía sin abrir
  nada (quién leyó qué y cuándo). Tres límites que hay que decir: solo aplica con los contactos
  que anuncian la v3 del protocolo (con el resto el tamaño sigue siendo el real); un **archivo
  troceado sigue siendo reconocible**, porque 48 KiB rellenados a un tramo de 1 KiB siguen
  siendo 48 KiB y una ráfaga de ellos sigue pareciendo lo que es; y **una foto sigue
  distinguiéndose** de un mensaje corto. Ocultar eso pide tráfico de relleno y batching, que no
  están hechos (ver el punto 6 de §9).

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
No puede leer nada.

**Corrección importante (10 sep 2026): sí puede saber quiénes son.** Este documento decía lo
contrario y era falso. Los **dos** miembros de una pareja anuncian y buscan **la misma** clave de
rendezvous (`ChatService.announceAndFind`), y los móviles son clientes de la DHT: los únicos que
guardan esos registros son los nodos de infraestructura. O sea que **cada nodo tiene, día a día,
qué dos PeerID comparten cada punto de cita**: el grafo social completo de las parejas activas,
incluidas las que hablan en directo y nunca tocan el buzón ni el relay. No queda en disco (los
registros viven en memoria del nodo), pero está ahí. **El depósito ciego no arregla esto.**

### 5.1 Tu IP queda expuesta (verificado el 10 sep 2026)

> **Estado:** la vía que entregaba la IP a un desconocido (la 2, abajo) **está cerrada desde el
> mismo 10 sep 2026** con un `ConnectionGater`. Sigue abierta la 1, pedirle tus direcciones al
> nodo, que hoy solo da direcciones de relay. Lo que sigue es cómo se encontró y cómo se cerró.

**Cualquiera que conozca tu PeerID podía sacar tu IP pública en segundos**, sin ser contacto
tuyo y sin que tú hicieras nada. El PeerID no es un secreto: se comparte por WhatsApp o por QR.

Reproducido contra el móvil del autor con dos sondas
(`native-bridge/libp2p/ip_leak_probe_test.go`), desde una identidad efímera cuya única ventaja
es saber los nodos —que van dentro del APK— y el PeerID de la víctima:

1. `TestIPLeakAgainstLiveNode`: le pregunta al nodo por ese PeerID (`FindPeer`). El nodo
   entregó **4 direcciones** del móvil a un desconocido. `handleFindPeer` de la DHT devuelve las
   direcciones que tenga de cualquier peer conectado, sin filtro. En datos móviles solo salen
   direcciones de relay: revelan **presencia y por dónde alcanzarte**, no la IP.
2. `TestIPLeakViaRelayDial`: con una de esas direcciones, el extraño **marca al móvil por el
   relay**. La conexión **se acepta** (no hay `ConnectionGater`: el filtro de PeerID desconocido
   de `ChatService.onReceived` está una capa por encima y llega tarde), y ante una conexión
   entrante por relay **libp2p inicia el hole punching solo** y le manda sus direcciones
   públicas. Resultado real: `/ip4/<IP pública del móvil>/udp/<puerto>/quic-v1`, en 1,7 s.

Contribuye a que sea tan fácil que el móvil **anuncie todas sus direcciones**:
`circuitAddrsFactory` añade las del relay sin quitar ninguna. El **mDNS**, que hasta el 10 sep
2026 se arrancaba siempre y anunciaba el PeerID y la dirección local a toda la WiFi, ahora va
**apagado de serie** y se enciende en Ajustes → "Red local" (surte efecto en el momento en los
dos sentidos, y al apagarlo se suelta el `MulticastLock`).

Aparte de esto, tus **contactos** ven tu IP por diseño en cuanto hay conexión directa, y el
operador del nodo la ve siempre (como el servidor de Signal). La diferencia con Signal era que
allí un desconocido con tu número no puede sacarte la IP, y aquí uno con tu PeerID sí podía.

**Estado (10 sep 2026, el mismo día): el vector 2 está cerrado.** El puente instala un
`ConnectionGater` (`native-bridge/libp2p/gater.go`): las **salidas** nunca se filtran —hay que
poder marcar a los nodos y a los contactos— y las **entradas** solo pasan si el PeerID está en
una lista que la app rellena con sus contactos no bloqueados y los nodos, al arrancar y en cada
ciclo WAN (`ChatService.pushAllowedPeers`). Corta en `InterceptSecured`, o sea en cuanto el
handshake revela quién llama y **antes** de que exista conexión: así no hay identify ni hole
punching. Los **bloqueados quedan fuera**, así que tampoco pueden sacar la IP.

Verificado en el móvil: el panel de Diagnóstico dice `filtro de conexiones: 4 permitido(s)` y la
sonda `TestIPLeakViaRelayDial`, que antes entregaba la IP pública en 1,7 s, ahora responde
*"el filtro cortó al extraño: la conexión no es usable"*. Sin regresión: `DHT: conectado`,
`relay: OK (alcanzable por circuit)` y rendezvous anunciando, con los dos nodos viendo al móvil.

**Lo que queda de la vía 1 (preguntar al nodo).** Sigue abierta, y da lo que el móvil anuncie:
direcciones de relay y presencia. Hoy **no** da la IP, porque las direcciones que el móvil
publica son de circuito; pero si algún día anuncia una pública confirmada (WiFi con UPnP), el
nodo la repartiría igual. Detalle útil: en datos móviles la sonda recibe `routing: not found`,
porque el `PublicQueryFilter` de kad-dht descarta del resultado a los peers cuyas direcciones
son **todas** de relay — o sea que esa vía solo devuelve algo cuando hay algo público que
devolver.

**Lo que sigue pendiente**, y una trampa que conviene no repetir:

- Que el nodo no entregue direcciones de móviles por `FindPeer`. El arreglo aparente —
  `dht.AddressFilter` en el nodo— **no vale**: en kad-dht ese filtro se aplica también a los
  registros de proveedor (`handlers.go:325`, `GetProviders`), que es el **rendezvous**, así que
  dejaría a los contactos sin las direcciones con las que se encuentran. Separar los dos casos
  exige parchear kad-dht. La alternativa sin parche —que el móvil no publique direcciones
  públicas directas y todo entre por relay— depende de que DCUtR funcione, que es el gate de NAT
  que nunca se ha medido.
- No anunciar direcciones de red local (mismo condicionante).
- Un modo "solo relay" para ocultar la IP también a los contactos, que la ven por diseño en
  cuanto hay conexión directa.

Ninguna de estas oculta la IP al **operador**: para eso solo sirve una VPN o Tor, y eso le pasa
igual a Signal.

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
remitente), pero tiene este coste en privacidad. El diseño que lo evita —depósitos ciegos bajo
una etiqueta derivada del secreto compartido, estilo rendezvous— está implementado y **encendido
desde el 12 sep 2026, por contacto**: se deposita bajo etiqueta a quien haya anunciado protocolo
≥ 2 (su cliente ya retira por etiquetas), y por PeerID al resto. Para un contacto sin actualizar,
**la tabla de arriba sigue siendo lo que hay en el disco del nodo**; para una pareja actualizada
el nodo guarda una etiqueta opaca y el sobre, sin `from` ni `to`. Ver
[DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) y §10.

### 6.2 Wake

El móvil mantiene abierto un stream `/krypta/wake/1.0.0`. El nodo, por tanto, **sabe en tiempo
real qué PeerIDs están conectados**. El aviso en sí no lleva payload ni remitente, pero el
patrón de conexión es un registro de presencia bastante fino.

Con el depósito ciego hay que decir algo más, porque el wake v2 entrega más que presencia. Como
el nodo ya no sabe de quién es cada buzón, el cliente se suscribe mandando sus **etiquetas**
(`{"v":2,"labels":[…]}`) por un stream que libp2p autentica como cualquier otro: le llegan
atados el PeerID del suscriptor y su **conjunto entero de etiquetas**. O sea que el nodo sabe,
sin esperar a que nadie deposite, con cuántos contactos activos cuenta ese PeerID; y cada
etiqueta que después aparezca en un depósito —donde ve el PeerID del depositante— le dice quién
es cada uno. **Es la correlación en vivo del §6.1, pero en un paso.** La etiqueta es opaca para
quien mire el disco después, no para quien mire las conexiones mientras ocurren. Ver
[DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) §4.2: cerrarlo exige que quien se suscribe
tampoco sea el PeerID real, o sea la fase 2 aplicada también al wake y no solo al depósito.

### 6.3 Relay

Cuando DCUtR no perfora el NAT, el tráfico —incluidas las llamadas— pasa por el nodo. El nodo
ve **qué dos PeerIDs hablan entre sí, cuándo y cuánto**, con la granularidad de una conexión.
No ve el contenido (doble cifrado: Noise + E2EE).

### 6.4 Concentración de operador

Los dos nodos de `Libp2pNode.DEFAULT_BOOTSTRAP` (VPS en São Paulo y en Dallas, de dos proveedores
distintos desde el 10 sep 2026) los opera **4000MSNM S.R.L.**, la empresa responsable de
Krypta. Eso significa que, hoy, **un solo operador** está en posición de observar todo lo
anterior para todos los usuarios. Que el titular sea una empresa y no una persona física **no
cambia esa propiedad**: la concentración depende de cuántos actores independientes hay, no de la
forma jurídica. Quién tiene acceso operativo a las máquinas (root, claves SSH, `node.key`) es
una decisión **todavía sin definir**, y conviene que lo esté antes de publicar. Es coherente con la decisión de "descentralizar la confianza, no la
infraestructura" —el operador no puede leer nada— pero **no** con una lectura ingenua de
"descentralizado".

Mitigación disponible hoy: el campo "Nodo WAN (bootstrap)" de Ajustes acepta una **lista de
nodos** y es editable, así que cualquiera puede levantar el suyo
([infra/node/README.md](../infra/node/README.md)) y usarlo, solo o combinado. Falta: que dos
usuarios que quieran hablar entre sí compartan al menos un nodo, y una guía de "monta tu nodo"
orientada a usuarios y no a operadores.

### 6.5 Registros del nodo

Lo que un nodo imprime **se queda en disco**: bajo systemd en Ubuntu el journal es persistente
y rsyslog lo copia a `/var/log/syslog`, y bajo launchd en la Mac va a un `node.log` que no se
rota. Así que la regla es que **el nodo no emite identificadores de usuario** (PeerIDs, IPs,
etiquetas de buzón) a su log: solo el arranque y errores fatales.

No siempre fue así. Hasta el 10 sep 2026 el handler de `/krypta/msg` imprimía el PeerID
remitente y el ciphertext de cada mensaje directo, y el nodo de São Paulo tenía 12 de esas
líneas guardadas desde el 7 ago — lo que contradecía la política de privacidad. El volcado
queda tras `-debugmsg`, solo para nodos locales de prueba, y
`TestMsgHandlerNoRegistraIdentificadores` fija el comportamiento por defecto. Lo que ya estaba
escrito hay que purgarlo a mano en cada máquina ([OPERACION.md](../infra/node/OPERACION.md)).
Estado a 10 sep 2026: el VPS ya corre el binario nuevo y su `/var/log/syslog` está purgado; su
**journal conserva esas líneas hasta que caduquen** —se decidió no vaciarlo entero, porque
también se perderían los logs de SSH—; con la retención de 30 días que fija ahora
`deploy-vps.sh`, se borran hacia el 10 oct 2026. Los nodos del Mac y de Windows siguen con el binario anterior,
y el `node.log` del Mac está sin purgar.

Este es un buen ejemplo de qué es la **confianza operativa**: el diseño decía «nada en disco» y
la operación decía otra cosa, sin que nadie lo supiera. Se descubrió mirando.

La misma regla vale para **Caddy**, que desde el 10 sep 2026 sirve `wss/443` en los dos VPS: sin
log de accesos y con un filtro que borra IP, puerto y cabeceras del cliente de cualquier línea de
error (`infra/node/deploy-caddy.sh`). Comprobado tras tráfico real: la IP de origen no aparece ni
en su journal ni en `/var/log/syslog`. Y sigue valiendo lo que se dijo arriba: el proveedor del
VPS puede ver el tráfico de red de la máquina, esto solo evita que el nodo lo escriba.

---

## 7. El dispositivo

| Dato | Dónde | Protección |
|---|---|---|
| Identidad Ed25519 | `krypta_identity` (prefs) | **Envuelta con una clave AES del Android Keystore** (TEE, no exportable). Desde el 8 sep 2026; antes estaba en claro. La migración es automática y solo borra la copia en claro tras verificar que la envuelta se recupera igual (`IdentityStore`) |
| Mensajes y contactos | `krypta.db` (Room) | **Cifrada entera con SQLCipher** desde el 9 sep 2026; la frase-clave (32 bytes al azar) vive envuelta por el Keystore. La conversión de la base en claro anterior se hizo una sola vez, verificando tabla a tabla antes de sustituir el fichero |
| Contenido de los mensajes | dentro de `krypta.db` | Desde la v8 (9 sep 2026) se guarda el **sobre en claro**, no el ciphertext de la red: con ratchet la clave de un mensaje se borra al usarla y lo guardado dejaría de poder abrirse. Lo que protege el historial es el cifrado de la base |
| Adjuntos, notas de voz, GIF | `filesDir/krypta_files/` | **Cifrados** (AES-256-GCM, clave envuelta por el Keystore) desde el 9 sep 2026. Los que ya estaban en el móvil siguen en claro: se leen igual, pero no se convierten. Abrir uno con otra app le entrega una copia en claro (queda en la caché hasta el siguiente arranque) |
| Estado del ratchet por conversación | `krypta.db` (v7) | Dentro de la base cifrada. Es material que abre lo que está **por llegar**: borrarlo no rompe la conversación (se reengancha sola en la época 0) |
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

- **La base ya va cifrada** (SQLCipher, 9 sep 2026). Lo que esto tapa son los **metadatos
  locales** —nombres de contacto, PeerIDs, marcas de tiempo, quién habla con quién y el tamaño
  de cada mensaje—; el contenido ya dependía de la identidad. La conversión de la base en claro
  se hace una sola vez al arrancar, sobre un fichero aparte, y **solo sustituye el original tras
  comprobar que la copia tiene las mismas tablas y las mismas filas**: el historial de mensajes
  no tiene copia de seguridad de ninguna clase, así que ahí no vale el "casi seguro".
- **Los adjuntos de `krypta_files/` también van cifrados** desde el 9 sep 2026 (AES-256-GCM,
  clave envuelta en el Keystore). Krypta los descifra en memoria para pintarlos o
  reproducirlos, así que no queda una copia en claro en disco — salvo cuando el usuario elige
  **abrir uno con otra aplicación**, que por definición se lo lleva en claro (queda en la caché
  hasta el siguiente arranque). Los adjuntos anteriores a esa fecha siguen en claro: se leen
  igual, pero no se convierten.
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
| Retirada del buzón, **por ritmo** (12 sep 2026) | Cubo por PeerID del stream, aparte del de depósitos: ráfaga de 60 y una ficha cada 5 s (v1 y v2 comparten cubo; un cliente abre las dos en cada retirada). Más un cubo **global** de 2000 con 200/s, porque las identidades libp2p son gratis y un cubo por peer no frena a quien las rota. Pasado el límite se contesta una retirada **vacía siguiendo el protocolo** (fin de lista + lectura del ack): el correo se queda en el nodo y sale en la siguiente, sin tocar disco |
| Wake | Una suscripción por peer; máximo 2000 simultáneas |
| Relay | 8 GiB y 6 h por conexión relayada; 4096 reservas, 256 por IP, 2048 por ASN |
| Lecturas de línea (buzón/wake en el móvil) | Búfer fijo (128 KiB / 4 KiB): un nodo que no cierre línea no puede hacer crecer la memoria |

**Vigilancia**: [`infra/node/check-nodes.sh`](../infra/node/check-nodes.sh) comprueba los
nodos de `DEFAULT_BOOTSTRAP` (lee la constante, así que sigue a la lista: hoy, los dos VPS)
—buzón, wake, relay con límites finitos e ida y vuelta real— y sale con error si alguno
falla, pensado para cron/launchd. Existe porque hasta ahora nadie se enteraba de nada: el 8 sep
2026 el nodo primario pasó dos días con un binario viejo y se descubrió mirando a mano.

**La vía `wss/443` debilita los límites por IP** (desde el 10 sep 2026). Detrás de Caddy, el nodo
ve a todos esos clientes llegar desde `127.0.0.1`: libp2p no limita conexiones por IP en loopback,
y el cupo de 256 reservas de relay por IP se reparte entre todos ellos. Quien quiera saltarse los
límites por IP puede entrar por el 443. Lo que no cambia son los límites que no dependen de la IP:
el ritmo y el reparto del buzón van por remitente autenticado, y el relay sigue con sus topes por
conexión y totales. Para que los usuarios legítimos no acaben ahí, el puente marca la vía
WebSocket 1 s por detrás de la directa (`dial_ranker.go`) y, desde el 12 sep 2026, **cierra la
WebSocket si con ese nodo ya hay una conexión directa** (`conn_prune.go`): se vio en el móvil del
autor que en un arranque en frío podían quedar las dos abiertas a la vez, y con la de Caddy el nodo
lo veía llegar desde `127.0.0.1`. El diagnóstico de la app muestra ahora las conexiones por nodo
(`1 conn: tcp`) y cuántas WebSocket sobrantes ha cerrado.

**Lo que sigue abierto**: no hay lista de control de acceso. El límite de ritmo de la *retirada*
existe desde el 12 sep 2026 (tabla de arriba); su coste es que, durante una ráfaga de avisos de
wake (un archivo troceado), algunas retiradas vuelven vacías y lo depositado llega unos segundos
más tarde, sin perderse. El chequeo corre cada 15 min desde la Mac de
desarrollo con aviso al cambiar el estado (12 sep 2026). Solo vigila mientras la Mac está
despierta; se decidió dejarla sin suspensión en vez de montar un canal de alerta externo. Un atacante decidido puede seguir generando carga; lo que ya no puede es **dejar
a un usuario sin entrega**, usar el relay como proxy ilimitado ni machacar el nodo a
escrituras.

---

## 9. Resumen honesto de lo que Krypta NO protege

1. **Metadatos de la entrega diferida y del relay**: el operador del nodo ve quién habla con
   quién y cuándo (§6). Es el hueco más grande del modelo.
2. **El pasado, si te roban la identidad**: con los contactos que aún no tienen ratchet, quien
   obtenga tu identidad puede descifrar lo que hubiera capturado de la red (§4). El **historial
   guardado en el móvil** ya no depende de eso —está en la base cifrada—, pero sí de que no se
   saquen también las claves del Keystore, cosa que un atacante con el proceso vivo o con root
   sí puede (punto 3).
3. **Nada protege frente a código ejecutándose *dentro* del proceso o con root** (§7): ahí el
   atacante le pide la clave al TEE igual que se la pide la app, y el cifrado en reposo —de la
   identidad o de la base— no cambia nada. Lo que sí queda cubierto es llevarse los ficheros:
   desde el 9 sep 2026, adjuntos incluidos (los anteriores a esa fecha siguen en claro).
4. **Al contacto**: nada impide que quien recibe tus mensajes los guarde, los reenvíe o los
   fotografíe con otra cámara.
5. **La disponibilidad**: los nodos son pocos y de un solo operador; si caen todos, la entrega
   diferida y el relay se detienen (la entrega directa entre dos móviles alcanzables, no).
6. **El análisis de tráfico a gran escala**: no hay tráfico de relleno, ni batching, ni mezcla.
   Quien observe la red y el nodo a la vez puede correlacionar por tiempos.
7. **Tu dirección IP frente a tus contactos y al operador** (§5.1): los contactos la ven por
   diseño en cuanto hay conexión directa, y el operador del nodo siempre. Frente a
   **desconocidos**, la vía que la entregaba en segundos (marcar al móvil por el relay y dejar
   que libp2p iniciara el hole punching) está **cerrada desde el 10 sep 2026** con el
   `ConnectionGater`. Lo que sigue abierto es que el nodo reparta tus direcciones por
   `FindPeer`: hoy solo son de relay, pero una pública confirmada se repartiría igual.
8. **Quiénes son tus contactos, frente al operador del nodo** (§5): los dos lados de cada
   pareja anuncian la misma clave de rendezvous, así que los nodos tienen el grafo de parejas
   activas de cada día. Es el hueco que el depósito ciego **no** cierra.
9. **La reproducción de un mensaje muy viejo** (10 sep 2026): quien haya capturado un sobre
   genuino de la **época 0** de una conversación puede hacer que se entregue otra vez, porque esa
   época se re-deriva del secreto compartido. Lo para la deduplicación previa, que desde el 10 sep
   2026 conserva una huella si es de los **últimos 8 días** (margen sobre el TTL de 7 del buzón)
   **o** está entre las **500 últimas**: antes solo lo segundo, que en una conversación muy activa
   podía ser medio día. Más allá de esa ventana, el mensaje se volvería a mostrar (repetido, con
   la fecha del original). No permite falsificar nada, solo repetir algo que ya se dijo. Lo
   encontró la prueba de propiedades del ratchet; el detalle está en
   [DISENO-ratchet.md](DISENO-ratchet.md) §1.9, incluido por qué la poda no corre en cada mensaje
   (hacerlo tumbaba el proceso con una ráfaga de archivo troceado).
10. **Un adversario cuántico futuro que haya grabado tu tráfico hoy** (*harvest now, decrypt
    later*). **Krypta no tiene nada post-cuántico**, y encaja peor que en otros sistemas: el
    **PeerID *es* la clave pública**, así que no hay que robar nada para tenerla; `S` se deriva de
    dos identidades de larga vida; y todo el material del ratchet —la época 0 y el DH de cada
    época— es X25519 sobre una raíz que encadena. O sea que **el ratchet no ayuda aquí**: quien
    rompa la curva abre una conversación grabada de principio a fin. No es una vulnerabilidad
    presente, es una apuesta sobre cuándo existe la máquina, y equivocarse no tiene arreglo
    retroactivo. Diseñado y medido el 11 sep 2026 en
    [DISENO-postcuantico.md](DISENO-postcuantico.md), **sin implementar** a propósito (ver §10).
11. **La recuperación tras un robo de identidad, frente a quien actúa** (revisión del 14 sep 2026,
    [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md)). El ratchet deja fuera
    a quien **solo escucha** después de un robo, en cuanto los dos giran claves. A quien tiene la
    identidad **y además manda algo**, no:
    - Con un sobre de época 0 y un linaje forjado **secuestra la sesión** y lee en pasivo lo que
      escriba el otro, sin vuelta atrás hasta borrar el contacto (H-4).
    - Como la autenticación del remitente es la del secreto compartido, **puede escribir como
      cualquiera de tus contactos**. Con el depósito ciego eso incluye ponerte palabras en boca de
      un contacto usando **tu propia** identidad robada, por ejemplo de tu `.krbk` (H-5). Está
      fijado en un test: por el buzón ciego entra; por el buzón con PeerID, solo si además el nodo
      miente sobre quién deposita.
    - La variante **silenciosa**, degradar la pareja a la clave estática con un anuncio forjado,
      estaba abierta y **se cerró el mismo día** (H-3).
    - **Krypta no promete resistencia a KCI** (decisión del 15 sep 2026). Mientras no haya revisión
      externa se declara como algo que no protege, no como algo a medio arreglar. Lo que lo
      cerraría, firmar los sobres con la identidad, cuesta la negación, y eso está por decidir.
12. **Dos fallos de implementación que ya no están, pero que estuvieron** (revisión del 14 sep
    2026). Con el envío por ratchet encendido desde el 10 sep:
    - dos operaciones simultáneas sobre la misma conversación podían **repetir clave y nonce**
      (H-0);
    - borrar y volver a añadir un contacto, o importar un `.krbk`, hacía que **sus mensajes se
      perdieran para siempre** (H-1).

    Solo afectaba a parejas con los dos móviles en un build del 10 al 13 sep, y no hay forma de
    saber desde fuera si llegó a ocurrir.
13. **Restaurar la identidad con el reloj del móvil atrasado** (W-6 de la especificación). Si al
    reinstalar o importar el `.krbk` la fecha del móvil es anterior al momento en que la pareja
    empezó su sesión actual, lo que escribes llega pero sin secreto hacia adelante, y **lo que te
    escribe el otro se pierde sin aviso**. Poner bien la hora después no lo arregla; lo arregla que
    el otro te borre y te vuelva a añadir. Con el reloj en hora automática no pasa.

---

## 10. Cambios que este documento pide (pendientes)

- ~~**Depósito ciego en el buzón**~~ (etiqueta derivada del secreto compartido en lugar de
  PeerID en claro), para que el grafo social deje de quedar escrito en el disco del nodo.
  **Encendido el 12 sep 2026, por contacto** (a quien anuncie protocolo ≥ 2; ver
  [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) §6). Con una advertencia que sigue en pie:
  **el relay filtra ese mismo grafo** y eso no lo arregla, así que lo que se gana es que no
  quede en disco, no que el operador no pueda saberlo en vivo. Y falta la prueba con dos
  móviles (PRUEBAS-PENDIENTES §16.10).
- **Híbrido post-cuántico** (ML-KEM-768 mezclado en la raíz del ratchet), que es lo que cierra el
  punto 10 del §9. **Diseñado y medido el 11 sep 2026, y deliberadamente no implementado**:
  [DISENO-postcuantico.md](DISENO-postcuantico.md). El orden es intencionado — el plan pide una
  **revisión externa del protocolo antes** de tocar esto, y un documento revisable sin código es
  la forma barata de respetarlo. Al medir cambió el diseño: el coste de CPU es despreciable
  (40–60 µs, como X25519) pero el tamaño no (1184 B de clave + 1088 B de ciphertext), así que
  llevarlo en cada mensaje pondría un acuse de lectura en 2434 B frente a 162; la solución es un
  **ratchet PQ lento**, porque al encadenar la raíz **una sola inyección protege todo lo
  posterior**. Dos huecos que quedarán igual: la **época 0 seguirá siendo clásica** (Signal sí
  cubre el acuerdo inicial, con servidor de prekeys) y la **autenticación tampoco** será
  post-cuántica.
- **PFS** (doble ratchet). Implementado entero el 9 sep 2026 —ver
  [DISENO-ratchet.md](DISENO-ratchet.md)— y con el **envío encendido el 10 sep 2026**
  (`RATCHET_SEND = true`), aunque **por pareja**: se usa solo con los contactos que también
  tengan una versión que lo anuncie, así que mientras el resto no actualice, §4 sigue siendo
  cierto para ellos. Con dos móviles reales **solo se ha probado la conversación normal** (10 sep
  2026); los escenarios límite se deben (`PRUEBAS-PENDIENTES` §16), así que este documento no lo
  cuenta todavía como una garantía. La clave de las llamadas también se negocia por llamada con
  esos mismos contactos; con el resto sigue el camino antiguo (§4).
- ~~**Cifrar los adjuntos** de `krypta_files/`.~~ **Hecho el 9 sep 2026**: lo que escribe el
  almacén va cifrado (AES-256-GCM, clave envuelta en el Keystore). Dos límites que hay que
  decir: **abrir un adjunto con otra app le entrega una copia en claro** (queda en la caché
  hasta el siguiente arranque), y **los adjuntos que ya estaban en el móvil no se convierten**
  — se siguen leyendo, pero siguen en claro; vaciar el chat los borra.
- **Alertas** de verdad para el chequeo de nodos (desde el 12 sep 2026 corre solo cada 15 min en
  la Mac de desarrollo, que se deja sin suspensión, y avisa al cambiar el estado). ~~Límite
  de ritmo también en la retirada del buzón.~~ Hecho y desplegado en los dos VPS el 12 sep 2026.
- **Confianza operativa** (hoja de ruta, 10 sep 2026). Hoy el nivel es «aficionado sin
  transparencia»: un solo operador, sin nada publicado sobre cómo opera. No se sale de ahí
  imitando el tamaño de Signal, sino haciendo que haga falta confiar menos en el operador y
  que lo que quede se pueda comprobar. Por orden de impacto:
  1. **Reducir lo que el nodo sabe**: el depósito ciego está **encendido por contacto desde el
     12 sep 2026** — con el primario en un proveedor ajeno, un volcado de ese disco era el grafo
     social con horas; ahora lo es solo para las parejas donde uno de los dos no ha
     actualizado —; medir y subir la tasa de
     conexión directa (la prueba de NAT con dos SIM, nunca hecha), porque cada conexión
     directa es una conversación que el relay no ve. El **relleno por tramos** de este punto
     está **hecho el 11 sep 2026** (§4): lo que se ganó es que el tráfico de control deje de
     distinguirse por el tamaño; lo que no, que un archivo troceado siga siendo reconocible.
  2. **Infraestructura** — *hecho en lo principal el 10 sep 2026*: los nodos caseros (Mac tras
     Cloudflare Tunnel, PC Windows de uso diario) salieron de `DEFAULT_BOOTSTRAP` y los
     sustituye un VPS en **otro proveedor** (InterServer, Dallas, EE. UU.), con SSH solo por
     clave y `ufw` desde antes de desplegar. Sube disponibilidad y seguridad y **saca a
     Cloudflare del camino** (terminaba el TLS del `wss`: no veía contenido, pero sí IPs,
     tiempos y volumen de cada usuario) — para los móviles actualizados; los que sigan con la
     versión anterior siguen pasando por los nodos caseros hasta actualizar. No reduce la
     concentración de operador: sigue siendo un solo operador. Logs sin identificadores (§6.5) y
     `node.key` respaldado fuera de cada máquina, hechos; quedan las alertas reales.
  3. **Transparencia del cliente**, que es la confianza más grande de todas — quien firma la
     APK puede leerlo todo, con E2EE o sin él —: código público del cliente y del nodo (**hecho
     el 12 sep 2026**: el repositorio de GitHub es público, con licencia MIT o Apache-2.0 a elección), builds
     reproducibles (APK, AAR y binario del nodo) con distribución por F-Droid a medio plazo, y
     custodia seria de la clave de firma.
  4. **Una página de operador**: quién opera cada nodo, en qué proveedor y país, qué se
     registra y cuánto se retiene, qué se hace ante una petición legal, y un informe periódico
     de peticiones recibidas aunque diga «0».
  5. **Diversidad de operadores**: guía de «monta tu nodo» para usuarios; que cada contacto
     comparta sus nodos de buzón preferidos **dentro del E2EE**, para depositar donde elige el
     destinatario y no donde elige el autor; y algún operador ajeno (una organización de
     derechos digitales) en la lista por defecto.
  6. **Largo plazo**: auditoría externa. La **entidad legal** ya existe —4000MSNM S.R.L. es quien
     desarrolla Krypta y opera los nodos— pero conviene no confundirla con el objetivo de este
     punto: constituir una empresa **no** hace que desaparezca el actor único al que presionar,
     solo cambia su forma. Eso lo da la diversidad de operadores del punto 5, no el registro
     mercantil.
