# Especificación del protocolo de Krypta

**Qué es.** La descripción **normativa** de lo que hace el código de Krypta en `main` a fecha
**15 de septiembre de 2026** (protocolo anunciado: **v3**), escrita para que alguien de fuera pueda
revisarlo sin leerse las 1600 líneas de `ChatService`. Los `DISENO-*.md` cuentan **por qué** se
decidió cada cosa; este documento cuenta **qué** hace: los bytes, las derivaciones y las reglas.
**Si este documento y el código discrepan, manda el código y el documento está mal**: se corrige
aquí, en el mismo cambio.

**Para qué existe.** Es la pieza central del paquete de revisión externa que pide
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §5. Lo que se afirma va
**numerado** (§12) para que una revisión pueda decir «P4 no se cumple», y lo que ya se sabe que no
se cumple va **declarado** (§13), para que nadie gaste horas redescubriéndolo.

---

## 0. Alcance, notación y primitivas

**Dentro:** identidad y secreto compartido (§2), sobre de aplicación (§3), transporte v1 (§4) y v2
—el ratchet por épocas— (§5), relleno (§6), negociación de capacidades (§7), llamadas (§8),
etiquetas del buzón ciego (§9), rendezvous (§10), número de seguridad y copia `.krbk` (§11).

**Fuera** (solo se cita lo que tocan): libp2p y Noise, la lógica del nodo (buzón, wake, relay:
[security-model.md](security-model.md) §6 y §8), el cifrado en reposo (SQLCipher, `FileVault`,
Keystore: security-model §7) y la interfaz.

**Notación.** `‖` es concatenación. Las cadenas van en UTF-8, y los números que aparecen **dentro**
de una cadena `info`, en decimal ASCII sin ceros a la izquierda. Los enteros en binario van en
big-endian. `∅` es la cadena vacía. `dir(de, para)` vale `0` si el PeerID `de` es menor que `para`
en orden lexicográfico de la cadena, y `1` si no.

| Símbolo | Definición | Implementación |
|---|---|---|
| `HKDF(ikm, salt, info, L)` | HKDF-SHA256, RFC 5869. Con `salt = ∅` se usan 32 bytes a cero, como manda la RFC | `Hkdf.kt` (propia, 38 líneas, sobre `javax.crypto.Mac`) |
| `HMAC(k, m)` | HMAC-SHA256 | JCA |
| `AEAD(k, n, p, a)` | AES-256-GCM, nonce de 96 bits, tag de 128 bits, AAD `a` | JCA `AES/GCM/NoPadding` |
| `X25519(priv, pub)` | RFC 7748 | Móvil: `golang.org/x/crypto/curve25519` por el puente (`Bridge.RatchetAgree`, `SharedSecretFor`), porque Android no trae `XDH` hasta la API 33 y el `minSdk` es 30. Tests JVM: `KeyAgreement("XDH")` del JDK (`JdkCurve25519`) |
| Ed25519 | Identidad libp2p | go-libp2p |
| Aleatorio | — | `SecureRandom` (Kotlin), `crypto/rand` (Go) |

---

## 1. Adversarios

| Id | Quién | Qué tiene y qué puede hacer |
|---|---|---|
| **A1** | La red y el operador de un nodo | Ve todo lo que viaja: ciphertext, tamaño, hora, los PeerID de cada conexión y las etiquetas del buzón. Puede retrasar, perder, duplicar, reordenar y reproducir. **No tiene claves** |
| **A2** | Un extraño que conoce tu PeerID | Puede depositar en el buzón, bajo tu PeerID o bajo cualquier etiqueta. No puede abrirte conexión: el `ConnectionGater` solo deja entrar a contactos y nodos (security-model §5.1) |
| **A3** | Un contacto malicioso | Tiene `S` contigo por ser la pareja. Lo que se afirme frente a A3 es lo que queda frente a quien ya habla contigo |
| **A4** | Quien obtiene **una identidad** en un instante *t* | Por extracción de la clave o con un `.krbk` y su frase. Tiene `S` con todos los contactos de esa identidad, y **para siempre**, porque no hay revocación |
| **A5** | Quien obtiene **identidad y estado** en *t* | El dispositivo entero: identidad, estado del ratchet y base |
| **A6** | Un adversario cuántico futuro | El tráfico grabado hoy (ver [DISENO-postcuantico.md](DISENO-postcuantico.md)) |

Fuera del modelo: código ejecutándose **dentro** del proceso, o con root, mientras la app vive.

---

## 2. Identidad y secreto compartido

- **Identidad**: par Ed25519 generado en el dispositivo. El **PeerID** es la identidad libp2p, que
  **embebe la clave pública**. La privada se guarda envuelta por una clave AES del Android Keystore
  (`IdentityStore`).
- **Secreto compartido** con el contacto *C* (`Bridge.SharedSecretFor`):

  ```
  s    = SHA-512(semilla_Ed25519)[0..32], con el clamp de RFC 7748
         (s[0] &= 248; s[31] &= 127; s[31] |= 64)
  u_C  = coordenada u de Montgomery de la pública Ed25519 que va dentro de PeerID_C
  S    = X25519(s, u_C)          32 bytes; un resultado todo ceros se rechaza
  ```

  Es **simétrico** (`S_AB = S_BA`) y **función pura de las dos identidades**: no se negocia, no
  caduca y no se guarda en disco (se deriva al leer el contacto, base v6). Quien tenga
  **cualquiera** de las dos identidades lo recalcula. De este hecho salen casi todas las
  propiedades de §12 y casi todos los límites de §13.
- No hay acuerdo de claves en línea ni prekeys: añadir un contacto es pegar su PeerID.

---

## 3. Sobre de aplicación (`MessageEnvelope`)

Viaja **dentro** del cifrado (v1 o v2). Es texto por líneas separadas por `0x0A`: el primer byte es
el tipo y el segundo, un salto de línea.

| Tipo | Formato | Notas |
|---|---|---|
| `T` texto | `T\n<id>\n<cuerpo>` | `<id>` lo elige el emisor (UUID) y es la clave primaria local en los dos extremos |
| `I` imagen | `I\n<id>\n<JPEG o WebP>` | En línea, ≤ ~58 KiB |
| `R` leído | `R\n<id1>\n<id2>…` | Solo marca mensajes **propios** de **esa** conversación |
| `F` meta de archivo | `F\n<fileId>\n<trozos>\n<tamaño>\n<mime>\n<nombre>` | |
| `K` trozo de archivo | `K\n<fileId>\n<índice>\n<bytes>` | 48 KiB |
| `C` llamada | `C\n<tipo>\n<callId>\n<ts>[\n<mitad de clave en hex>]` | §8 |
| `Y` cita | `Y\n<replyToId>\n<sobre interior>` | Envoltorio; no anida |
| `V` capacidad | `V\n<versión>[\n<versión que tengo apuntada de ti>]` | §7. La segunda línea existe desde el 14 sep 2026 |
| `D` descriptor | `D\n<tamaño>\n<mime>\n<ruta>\n<nombre>` | **Local**: nunca se envía. Uno recibido se descarta (y se confirma) |

Hasta el 21 sep 2026 el cliente **incumplía** la regla del `D`: reintentar un archivo fallido
reenviaba su fila, que es el descriptor, y el receptor lo guardaba como una burbuja de archivo sin
archivo. Desde entonces el emisor reintenta mandando `F` + todos los `K` con el mismo `fileId`, y el
receptor descarta cualquier `D` que llegue. No cambia ningún formato.

Un tipo `A`–`Z` que la versión no conoce decodifica como `Unsupported` y se descarta sin pintar
nada. Unos bytes sin forma de sobre decodifican como `null`, y la capa superior los trata como texto
legado (mensajes anteriores a que existieran los sobres).

---

## 4. Transporte v1: clave estática

```
K1    = HKDF(S, ∅, "krypta-msg-key-v1", 32)
wire  = n ‖ AEAD(K1, n, sobre, ∅)          n = 12 bytes aleatorios
```

Sin cabecera, sin relleno y sin estado. Se **envía** a quien no ha anunciado v2 (§7) y se
**acepta al recibir de cualquier contacto, siempre** (W-5). El historial anterior a la base v8
sigue guardado así.

---

## 5. Transporte v2: ratchet por épocas

Doble ratchet donde la **época** es el *par* de claves públicas efímeras vigentes y se pasa a la
siguiente **en cuanto se tienen las dos**, sin iniciador ni respondedor. El porqué de apartarse del
doble ratchet de Signal está en [DISENO-ratchet.md](DISENO-ratchet.md) §2.

### 5.1 Estado por conversación (`RatchetState`)

| Campo | Qué es |
|---|---|
| `lineage` | *L*: unix millis de cuando nació la sesión |
| `epoch` | *e* ≥ 0 |
| `sendDir` | `dir(yo, contacto)` |
| `rootKey` | `RK(e)` |
| `sendChain`, `sendN` | Clave de cadena de envío y número del próximo mensaje |
| `sendPN` | Mensajes enviados en la época anterior |
| `recvChain`, `recvN` | Clave de cadena de recepción y siguiente número esperado |
| `myCurPub` | Mi pública de la época actual (32 ceros en la época 0) |
| `nextPriv`, `nextPub` | Mi propuesta para la época `e+1` |
| `peerNextPub` | La propuesta del otro para `e+1`, si ha llegado |
| `past` | Hasta 3 cadenas de recepción retiradas: `(L, e, clave de cadena, n)` |
| `skipped` | Hasta 2000 claves de mensajes saltados: `(L, e, N, mk)` |

Se serializa (`RatchetState.encode`, formato 1) y se guarda como blob opaco en la base cifrada.
Dentro va `nextPriv`; lo que **no** va es `S`, que se deriva cuando hace falta.

### 5.2 Derivaciones

```
RK(L, 0)   = HKDF(S,                   ∅,        "krypta-rtc-root:0:" ‖ L, 32)
RK(e)      = HKDF(X25519(priv_e, pub_e), RK(e−1), "krypta-rtc-root:" ‖ e,   32)    e ≥ 1
CK(e, d)   = HKDF(RK(e),               ∅,        "krypta-rtc-chain:" ‖ e ‖ ":" ‖ d, 32)
mk_N       = HMAC(CK_N, 0x01)          CK_{N+1} = HMAC(CK_N, 0x02)
k ‖ nonce  = HKDF(mk_N, ∅, "krypta-rtc-msg", 44)                (32 + 12 bytes)
```

El linaje solo entra en `RK(L, 0)`; las épocas siguientes lo heredan porque la raíz encadena. El
nonce **se deriva**, no se sortea: la corrección entera descansa en que ninguna `mk` se use dos
veces (P3).

### 5.3 Cabecera y sobre de red

```
0        versión = 0x02
1        flags        bit 0 = relleno (§6); los demás, reservados e ignorados al recibir
2..9     L            u64 (se lee como Long con signo; no se valida su valor, ver W-4)
10..13   e            u32 (se lee como Int con signo; negativo = cabecera rechazada)
14..17   N            ídem
18..21   PN           ídem; se transmite pero el receptor no lo usa (W-9)
22..53   cur_pub      mi pública de la época e
54..85   next_pub     mi propuesta para e+1
86..     AEAD(k, nonce, p', AAD = bytes 0..85)
```

`p'` es el sobre, relleno si el bit 0 está puesto. Sobrecoste: 86 + 16 = **102 bytes** por mensaje.
Un receptor decide si **parece** v2 con `len > 86 ∧ byte[0] = 0x02` (`looksLikeRatchet`). Es solo
una pista: 1 de cada 256 ciphertext v1 de más de 86 bytes empieza igual, así que ante un fallo de v2
se prueba v1 (§7.5).

### 5.4 Cifrar

```
encrypt(st, sobre, pad):
    st ← si st.peerNextPub ≠ null: avanzar(st, st.peerNextPub) si no: st
    H  ← cabecera(st.L, st.e, st.sendN, st.sendPN, st.myCurPub, st.nextPub, pad)
    mk ← HMAC(st.sendChain, 0x01)
    devolver ( st con sendChain = HMAC(st.sendChain, 0x02), sendN = sendN + 1 ,
               H ‖ AEAD(clave(mk), nonce(mk), pad ? relleno(sobre) : sobre, H) )
```

### 5.5 Avanzar de época

```
avanzar(st, pub_otro):
    dh ← X25519(st.nextPriv, pub_otro);  si dh = 0³²: fallo
    e' ← st.e + 1
    RK ← HKDF(dh, st.rootKey, "krypta-rtc-root:" ‖ e', 32)
    (priv, pub) ← par efímero nuevo
    devolver st con  e = e', rootKey = RK,
                     sendChain = CK(e', sendDir), sendN = 0, sendPN = st.sendN,
                     recvChain = CK(e', 1 − sendDir), recvN = 0,
                     myCurPub = st.nextPub, nextPriv = priv, nextPub = pub, peerNextPub = null,
                     past = últimas 3 de (past + (L, st.e, st.recvChain, st.recvN))
```

La privada consumida (`st.nextPriv`) no se guarda en ningún sitio: eso es lo que da secreto hacia
adelante a partir de la época 1. La cadena de **envío** anterior se descarta; la de **recepción**
se retira a `past` para lo que aún esté en vuelo.

### 5.6 Descifrar

```
decrypt(st, S, wire):                        -- función pura: si lanza, el estado no cambia
    H ← cabecera(wire)            (ilegible → fallo)
    si H.L > st.L:  st ← reset(st, S, H.L)   -- adopta el linaje mayor
    si H.L = st.L y H.e = st.e + 1:  st ← avanzar(st, H.cur_pub)
    si H.L ≠ st.L o H.e ≠ st.e:  devolver abrirViejo(st, S, H, wire)
    -- cadena viva
    si H.N < st.recvN:  mk ← quitar de skipped (L, e, N)   (no está → fallo: clave gastada)
    si no:  si H.N − st.recvN > 1000: fallo
            guardar en skipped las mk de st.recvN .. H.N−1;  mk ← la de H.N
    p ← AEAD⁻¹(mk, wire, AAD = H);  si flag de relleno: p ← quitarRelleno(p)
    st ← st con recvChain/recvN avanzados y peerNextPub = H.next_pub
    devolver (avanzar si procede(st), p)

abrirViejo(st, S, H, wire):
    si (H.L, H.e, H.N) está en skipped: usar esa mk y quitarla
    si no, cadena ← past[(H.L, H.e)]
           o, si H.e = 0, CK(RK(H.L, 0), 1 − sendDir) derivada de S       -- época 0 de CUALQUIER linaje
           o fallo
         si H.N < cadena.n: fallo;  si H.N − cadena.n > 1000: fallo
         guardar en skipped las intermedias; abrir; actualizar esa cadena en past
    -- no adopta linaje, no avanza de época y no apunta la propuesta del otro
```

`reset(st, S, L')` vuelve a la época 0 del linaje `L'` con un par efímero nuevo, retirando la
cadena de recepción actual a `past` y conservando `skipped`.

### 5.7 Reglas del linaje

- Una sesión nace con `L = System.currentTimeMillis()`: al primer uso, al añadir el contacto o
  cuando el estado guardado no se puede leer.
- Un linaje **estrictamente mayor** se adopta. Uno **menor no se adopta nunca** (ver
  DISENO-ratchet §1.8.4: adoptarlo reutilizaría ternas `(L, e, N)` ya gastadas tras una
  reinstalación).
- **Requisito**: los linajes que emite una identidad tienen que ser monótonos. Hoy lo garantiza el
  reloj, no el protocolo (W-6).
- **Si no se cumple** (W-6, fijado en `RatchetTest`): quien pierde el estado con el reloj por detrás
  del linaje vigente de la pareja crea un linaje menor. Lo que escribe llega por `openOld`, pero el
  otro no lo adopta y no sale nunca de la época 0; lo que escribe el otro, en una época > 0 del
  linaje vigente, no se puede abrir y se pierde. El linaje se fija al crear la sesión, así que
  **corregir el reloj no lo arregla**: lo arregla un linaje nuevo del otro lado (borrar y volver a
  añadir el contacto).
- Recuperación: como la época 0 de cualquier linaje es derivable de `S`, el lado que pierde el
  estado siempre puede volver a hablar, y el otro adopta su linaje (que es mayor). Si el otro sigue
  escribiendo en el viejo, el que perdió el estado responde con un `V` (reengache, §7.6).

### 5.8 Límites

`MAX_SKIP = 1000` claves por hueco; `MAX_SKIPPED_KEYS = 2000` en total (se descartan las más
antiguas); `MAX_PAST_CHAINS = 3`.

### 5.9 Persistencia, exclusión y deduplicación (`RatchetSessions`)

```
send(C, sobre, pad, persistir):
    con el cerrojo de C:
        (st', wire) ← encrypt(cargar(C), sobre, pad)
        en una transacción: persistir(wire); guardar(C, st')
    -- los bytes salen DESPUÉS: si el envío falla, el estado ya avanzó y no se repite clave

receive(C, wire, persistir):
    con el cerrojo de C:
        si visto(C, SHA-256(wire)): devolver Duplicado        -- se confirma en el buzón
        (st', p) ← decrypt(cargar(C), S, wire)                -- si lanza, nada se guarda
        en una transacción: persistir(p); guardar(C, st'); marcarVisto(C, SHA-256(wire))
```

- **Cerrojo por conversación** (`Mutex`, desde el 14 sep 2026, H-0). Sin él, dos operaciones que
  se crucen parten del mismo estado y **repiten clave y nonce**.
- **Atomicidad**: el avance del ratchet y la persistencia del mensaje van en la misma transacción.
  Si el proceso muere entre medias, el buzón reentrega y el sobre sigue siendo legible.
- **Deduplicación**: huella SHA-256 del sobre de red. Se conserva si tiene menos de **8 días**
  (margen sobre los 7 de TTL del buzón) **o** está entre las **500** más recientes de la
  conversación; se poda una de cada 64 inserciones. Es una **pieza de seguridad**: es lo único que
  impide reproducir un sobre de la época 0 (W-2).
- **Estado ilegible** → sesión nueva en la época 0 con `L` = ahora (la conversación se reengancha
  sola). **Borrar el contacto** olvida la sesión; **vaciar el chat** no.

---

## 6. Relleno por tramos (v3)

```
relleno(p)       = p ‖ 0x80 ‖ 0x00^k,   con longitud total = tramo(|p| + 1)
tramo(n)         = múltiplo de 160 si n ≤ 4096;  de 1024 si n ≤ 65536;  n si es mayor
quitarRelleno(q) = q sin los 0x00 finales ni el 0x80 que les precede
                   (si ese byte no es 0x80: fallo, el mensaje se descarta)
```

Solo existe en v2 (el bit va en la cabecera, que es AAD). Se aplica a quien anuncie ≥ 3 (§7).

---

## 7. Negociación de capacidades

### 7.1 Versiones y umbrales

`PROTOCOL_VERSION = 3` es **lo que se anuncia**, no el umbral de nada. Cada capacidad tiene el suyo:

| Capacidad | Mínimo del contacto | Constante |
|---|---|---|
| Envío por ratchet (v2) | 2 | `RATCHET_MIN_PROTOCOL` |
| Clave de llamada negociada | 2 | `RATCHET_MIN_PROTOCOL` |
| Depósito ciego | 2 | `BLIND_MIN_PROTOCOL` |
| Relleno | 3 | `PADDING_MIN_PROTOCOL` |

Hay dos interruptores globales de emergencia, `RATCHET_SEND` y `BLIND_DEPOSIT`: puestos a `false`,
devuelven todo al camino v1.

### 7.2 Qué se guarda por contacto

- `peerProtocol`: la versión que el contacto ha demostrado o anunciado. **Nunca baja**; solo vuelve
  a 0 si se borra el contacto. El SQL lo impone en una sola sentencia (`ContactDao.UPSERT_SQL`,
  `RAISE_PEER_PROTOCOL_SQL`, desde el 14 sep 2026, H-2/H-3).
- `announcedProtocol`: la versión que ya le hemos anunciado.

### 7.3 Enviar

```
usaRatchet(C) = RATCHET_SEND ∧ S ≠ null ∧ C.peerProtocol ≥ 2
rellena(C)    = usaRatchet(C) ∧ C.peerProtocol ≥ 3
sellar(C, s)  = usaRatchet(C) ? RatchetSessions.send(C, s, rellena(C)) : v1(C, s)
```

Todos los caminos de salida pasan por `sellar`: texto, foto, archivo (meta y trozos), acuses de
lectura, señales de llamada, anuncios de capacidad y reintentos. Hay **una sola excepción**: el
anuncio `V` de §7.6, que va por v1 a propósito.

### 7.4 Anunciar

En cada ciclo WAN, a cada contacto no bloqueado con `announcedProtocol < PROTOCOL_VERSION`:
`sellar(C, "V\n3\n" ‖ C.peerProtocol)`. Solo si sale (directo o buzón) se marca
`announcedProtocol = 3`.

### 7.5 Recibir y elegir versión

```
alRecibir(C, wire):
    si C bloqueado: descartar (y confirmar en el buzón)
    si parece v2: intentar RatchetSessions.receive; si falla → intentar v1 (marcando "parecía v2")
    si no: intentar v1
    si nada abre: descartar con línea de diagnóstico; si "parecía v2" → reengache (§7.6)
    si abrió por v2: C.peerProtocol ← máx(C.peerProtocol, relleno ? 3 : 2)     -- lo demostrado
```

La cabecera de un sobre v2 que abre está autenticada (es el AAD), así que lo que demuestra no se
puede fingir sin `S`.

### 7.6 Anuncio `V` recibido, y reengache

```
alRecibirV(C, versión, apuntada):
    si versión > C.peerProtocol: C.peerProtocol ← versión
    si versión < C.peerProtocol: ignorar (la versión no baja)
    si apuntada ≠ null ∧ apuntada < 3 ∧ C.announcedProtocol ≥ 3:
        lanzar V("3", C.peerProtocol) por v1                  -- lo perdió: repetírselo
    si no, si C.peerProtocol cruzó de < 2 a ≥ 2 ∧ usaRatchet(C):
        lanzar V("3", C.peerProtocol) por sellar              -- que adopte nuestro linaje

reengache(C):          -- un sobre que parecía v2 no abrió por ninguna vía
    si usaRatchet(C):              lanzar V por sellar (lleva nuestro linaje)
    si C.peerProtocol < 2:         lanzar V("3", C.peerProtocol) por v1
```

Todo lo «lanzado» sale fuera del camino de recepción, que es síncrono con la confirmación del
buzón, y como mucho **una vez por contacto cada 5 minutos**. Un `V` por v1 no lleva contenido del
usuario, pero sí delata por su tamaño que es un anuncio.

---

## 8. Llamadas

- **Señalización**: sobres `C` por el camino normal (§7.3). Al recibir un `invite`, en este orden
  (`CallService.onInvite`; H-7 de la revisión):

  1. **Una vez por llamada.** Si ese `(contacto, callId)` ya se atendió, se ignora. La memoria es
     local y en RAM, dura `45 s + 10 min` desde que se ve y guarda como mucho 256 entradas: es el
     intervalo entero en el que un mismo invite podría timbrar.
  2. **Fecha futura.** Si `ts − ahora > 10 min`, no timbra: queda como llamada perdida.
  3. **Rancio.** Si `ahora − ts > 45 s`, no timbra: queda como llamada perdida.
  4. Si no hay otra llamada, timbra; si la hay, se responde `busy`.

  `ahora` es el reloj del **receptor** y `ts` el del **emisor**, así que las dos ventanas absorben
  el desfase entre ambos. La **fila de llamada perdida es idempotente**: su id es
  `hex(SHA-256("krypta-missed-call-v1" ‖ 0x00 ‖ id_contacto ‖ 0x00 ‖ callId))` y, si ya existe, no
  se guarda ni se avisa otra vez. `accept`, `reject`, `busy` y `hangup` solo actúan sobre la llamada
  en curso (mismo `callId`, que es aleatorio), así que no necesitan memoria.
- **Clave** (`CallService`):

  ```
  con quien anuncia ≥ 2:   k_llamante, k_contestador ← 32 bytes aleatorios cada uno
                           (van en la 5.ª línea del invite y del accept)
                           K_call = HKDF(k_llamante ‖ k_contestador, S, "krypta-call-key-v2:" ‖ callId, 32)
  con el resto:            K_call = HKDF(S, "krypta-call-v1", callId, 32)
  ```

  El contestador sortea su mitad **al recibir el invite**, así que el primer byte de medios ya va
  con la clave negociada.
- **Medios**: streams libp2p `/krypta/call/1.0.0` (audio, marco `uint16`) y `/krypta/video/1.0.0`
  (vídeo, marco `uint32`, 1 MiB). Cada frame va como `AesGcmMessageCipher.encrypt(K_call, frame)`,
  es decir `n ‖ AEAD(HKDF(K_call, ∅, "krypta-msg-key-v1", 32), n, frame, ∅)` con `n` aleatorio. El
  primer frame de cada stream es `HELLO:<callId>` (audio) o `VHELLO:<callId>` (vídeo) cifrado, y el
  receptor lo valida. **Los frames no llevan contador** (W-8). Lo que impide que la red los repita,
  los reordene o se los devuelva al emisor es que el stream va dentro de la seguridad de transporte
  de libp2p (TLS 1.3 o Noise) de teléfono a teléfono, también cuando pasa por un relay. Está
  comprobado con tests (Go `TestTransporteRechazaBytesManipulados`,
  `TestRelayNoVeLoQueViajaPorElCircuito`), no supuesto.

---

## 9. Etiquetas del buzón ciego

```
semana    = díaDesdeÉpoca(UTC) / 7            (división entera)
etiqueta  = HKDF(S, ∅, "krypta-mbx:" ‖ semana ‖ ":" ‖ dir(emisor, destinatario), 32)   → hex
```

- **Deposita** bajo la etiqueta de la semana en curso quien escribe a un contacto que anuncia ≥ 2.
  Al resto se le deposita por PeerID, y ahí el nodo fija `from` con la identidad del stream.
- **Retira** las etiquetas de la semana en curso y de la anterior, de todos sus contactos. El nodo
  guarda `etiqueta → sobre, hora` y no ve ni `from` ni `to`.
- **Atribución**: el receptor asigna el sobre al contacto **por la etiqueta**. Como la etiqueta
  sale de `S`, **la autenticación del remitente en este camino es la de `S`**, no la de la identidad
  libp2p (W-3).

---

## 10. Rendezvous (descubrimiento)

```
rdv(fecha) = HKDF(S, ∅, "krypta-rdv:" ‖ AAAA-MM-DD (UTC), 32)
```

Los dos extremos anuncian y buscan la misma clave en la DHT. Durante las 2 horas a cada lado de la
medianoche UTC se usan también la del día contiguo.

---

## 11. Número de seguridad y copia `.krbk`

- **Número de seguridad** = los 60 dígitos decimales menos significativos de
  `SHA-256("krypta-safety-number-v1" ‖ min(PeerID_A, PeerID_B) ‖ max(…))`, en grupos de 5. El QR
  lleva `krypta:verify:<PeerID propio>`.
- **`.krbk`** = `"KRBK1" ‖ sal(16) ‖ nonce(12) ‖ AEAD(PBKDF2-HMAC-SHA256(frase, sal, 310 000, 256 bits), nonce, carga, AAD = "KRBK1")`.
  La carga son líneas `v=1`, `id=<identidad Base64>`, `c=<nombre Base64>|<PeerID>|<0|1 verificado>`
  y `b=<PeerID bloqueado>`. **No** lleva `S` (se re-deriva), ni el estado del ratchet, ni
  `peerProtocol`: al importar, los contactos empiezan sin sesión y sin versión, y lo resuelve §7.6.

---

## 12. Propiedades que se afirman

«Probada» significa que hay tests que la ejercitan, no que esté demostrada formalmente: **nada de
este protocolo tiene todavía análisis formal ni revisión externa** (ver
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §4 y §5).

| Id | Propiedad | Frente a | Estado | Evidencia |
|---|---|---|---|---|
| **P1** | Confidencialidad del contenido | A1, A2 | Probada | `RatchetTest`, `AesGcmMessageCipherTest`, `RatchetPropertyTest` («nada se abre como otro mensaje») |
| **P2** | Integridad del sobre y de la cabecera frente a quien no tiene `S` | A1, A2 | Probada | `RatchetTest` (cabecera forjada), `RatchetPaddingTest` (bit de relleno), `ParserFuzzTest` |
| **P3** | Ninguna terna `(L, e, N)` se repite en un mismo emisor, y por tanto ninguna clave ni nonce | — | Probada, **condicionada** a W-6 | `RatchetPropertyTest` (secuencial) y `RatchetSessionsTest` (concurrente, desde el 14 sep 2026) |
| **P4** | Secreto hacia adelante para mensajes de época ≥ 1: comprometer identidad y estado en *t* no abre lo entregado antes de *t* | A5 | Afirmada, **con excepciones**: la época 0 (W-1), y las claves saltadas y cadenas retiradas que sigan guardadas en *t* | Sin test que modele el compromiso en *t*: es la primera cosa que debe cubrir el modelo formal |
| **P5** | Recuperación tras un compromiso frente a un atacante **pasivo**: tras una ronda de DH que no ha visto, vuelve a no leer | A5 pasivo | Afirmada, no probada | Ídem |
| **P6** | Recuperación tras un compromiso frente a un atacante **activo** | A4, A5 activos | **No se cumple** | W-3, W-4 (`RatchetTest`: «un linaje forjado secuestra la sesión») |
| **P7** | Un sobre genuino se entrega como mucho una vez dentro de la ventana de deduplicación | A1 | Probada | `RatchetSessionsTest`, `RatchetSeenPruneSqlTest`, `ChatServiceTest` |
| **P8** | Una conversación nunca queda rota del todo porque un extremo pierda el estado, borre y vuelva a añadir el contacto o importe un `.krbk` | — | Probada, **condicionada** a W-6 | `RatchetPropertyTest`, `ChatServiceTest` (re-añadir, `.krbk`, desde el 14 sep 2026) |
| **P9** | La red no puede degradar una pareja a v1, y una copia vieja del contacto tampoco | A1, A2 (y A4 por el camino del anuncio) | Probada desde el 14 sep 2026 | `ContactUpsertSqlTest`, `ChatServiceTest` |
| **P10** | El avance del ratchet y la persistencia del mensaje se confirman juntos | — | Probada | `RatchetSessionsTest`, `ChatServiceTest` |
| **P11** | Con v3, los mensajes de control y los textos cortos miden lo mismo | A1 | Probada | `PaddingTest`, `RatchetPaddingTest` |
| **P12** | La clave de una llamada no se deriva de la identidad (con quien negocia) | A4 sin la señalización | Probada | `CallServiceTest` |
| **P13** | El disco del nodo no liga etiquetas del buzón con PeerIDs | A1 (volcado) | Probada en el nodo | Go `TestMailboxV2NoGuardaNiRemitenteNiDestinatario`, `TestMailboxV2LaEtiquetaEsLaLlave`, [DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) |
| **P14** | Separación de dominio entre todas las derivaciones de `S` | — | Por inspección | Etiquetas `info` de §4, §5.2, §8, §9 y §10, todas distintas |
| **P15** | Un `invite` genuino hace sonar como mucho una vez y deja como mucho una fila de llamada perdida, lo entregue quien lo entregue y cuantas veces | A1 | Probada desde el 15 sep 2026, **condicionada**: si el proceso se reinicia dentro de los 10 min 45 s puede sonar una vez más (W-14), y vaciar el chat borra la fila y con ella su memoria | `CallServiceTest` (7 tests de H-7), `ChatServiceTest` |

---

## 13. Debilidades conocidas

| Id | Qué | Frente a | Dónde se trata |
|---|---|---|---|
| **W-1** | La **época 0** no tiene secreto hacia adelante, y si el primer mensaje del usuario sale en ella **depende de los relojes** | A4 | DISENO-ratchet §1.8.4 |
| **W-2** | Un sobre de época 0 **reproducido** fuera de la ventana de deduplicación (8 días ∪ 500) se vuelve a entregar | A1 | DISENO-ratchet §1.9 |
| **W-3** | **La autenticación del origen es la de `S`**. Quien tenga *cualquiera* de las dos identidades puede inyectar como el otro por tres vías: v1, época 0 de un linaje ajeno (se abre sin adoptarlo) y un linaje nuevo. Con el depósito ciego eso incluye **suplantar a cualquier contacto ante quien perdió su propia clave** (KCI). **La resistencia a KCI no se promete** (decisión del 15 sep 2026, hasta la revisión externa). Por el buzón **con PeerID** esa variante solo entra si el nodo miente sobre el remitente | A4 | H-5 de la revisión; fijado en `ChatServiceTest` (buzón ciego, PeerID con nodo honrado y con nodo que miente) |
| **W-4** | **Secuestro por linaje**: un sobre de época 0 con un linaje forjado muy alto es adoptado, el receptor avanza con la propuesta del atacante y le escribe con material que este conoce; el extremo legítimo queda fuera **sin vuelta atrás** hasta borrar la sesión | A4 activo | H-4; fijado en `RatchetTest` |
| **W-5** | La recepción v1 se acepta siempre, de cualquier contacto, aunque la pareja ya use v2 | A4 | Ligado a W-3: cerrar solo esta vía no compra nada |
| **W-6** | La monotonía del linaje la da el reloj. **El efecto real es de disponibilidad**: quien pierde el estado con el reloj por detrás del linaje vigente (restaurar con la fecha mal puesta, o un linaje vigente creado por un móvil con el reloj adelantado) crea un linaje menor; lo que escribe llega pero sin salir de la época 0, lo que le escriben **se pierde**, y corregir el reloj no lo arregla (§5.7). Repetir claves exigiría crear un linaje en el mismo milisegundo que uno anterior | — | Sin arreglo; fijado en `RatchetTest`. Un linaje monótono duradero toca la regla congelada (REVISION §9.2) |
| **W-7** | Sin post-cuántico: todo es X25519, y el PeerID *es* la clave pública | A6 | [DISENO-postcuantico.md](DISENO-postcuantico.md) |
| **W-8** | Los **frames de una llamada** no llevan contador ni ventana, y la clave es la misma en los dos sentidos: **la capa de aplicación** no distingue un frame repetido, reordenado o devuelto al emisor. **Impacto limitado mientras la seguridad de transporte de libp2p (TLS 1.3 o Noise) mantenga autenticidad, orden y anti-replay de extremo a extremo**, y eso está comprobado (15 sep 2026): un intermediario que duplica, reordena o refleja bytes corta la conexión sin entregar nada repetido, y un relay no ve ni el frame ni lo negociado dentro del circuito. En los tests se negoció TLS 1.3. Sin comprobar: la vía QUIC directa tras DCUtR (por inspección, go-libp2p no usa 0-RTT). No da frescura frente a un extremo comprometido ni frente a código que reinyecte frames después del transporte, y no sustituye a un contador si cambia el transporte. Y la app no puede verificarlo en ejecución: go-libp2p deja vacío `ConnState().Security` en las conexiones relayed | A1, si cambiara el transporte | Pregunta para la revisión (§15.5); evidencia en [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §8 |
| **W-9** | `PN` se transmite y no se usa; las claves de la época anterior salen de la cadena retirada, acotadas por `MAX_SKIP` | — | Pregunta para la revisión |
| **W-10** | Etiquetas del buzón y rendezvous **derivables de `S` para siempre**: quien obtenga una identidad puede decir qué etiquetas eran de esa pareja en cualquier volcado pasado | A4 | DISENO-ratchet §7.2 |
| **W-11** | El `.krbk` solo lo protege la frase (PBKDF2, 310 000 iteraciones): quien lo obtenga puede probar frases sin límite, y acertar es A4 | A4 | security-model §7 |
| **W-12** | La misma semilla Ed25519 sirve para firmar (Noise de libp2p) y, convertida, para X25519 | — | Pregunta para la revisión |
| **W-13** | Metadatos: el nodo ve el grafo de parejas del día (DHT), la presencia (wake) y quién habla con quién en vivo (relay); y, por identify, qué protocolos admite cada teléfono | A1 | security-model §5 y §6 |
| **W-14** | Si la app se reinicia en los 10 min 45 s siguientes a un `invite`, quien pueda reenviarlo (el nodo, por el buzón) lo hace **sonar una vez más**: la memoria de invites atendidos vive en RAM. La fila de llamada perdida no se repite, porque su id va en la base | A1 | Aceptado el 15 sep 2026: persistir esa memoria dejaría metadatos de llamadas fuera de SQLCipher o tocaría la tabla de vistos del ratchet (REVISION §9.3) |

---

## 14. Mapa de código y pruebas

| Pieza | Código | Pruebas |
|---|---|---|
| Secreto compartido | `native-bridge/libp2p/bridge.go` (`SharedSecretFor`), `Libp2pKeyExchange` | Go `TestSharedSecret*` |
| Sobre de aplicación | `MessageEnvelope.kt` | `MessageEnvelopeTest`, `ParserFuzzTest` |
| v1 | `AesGcmMessageCipher.kt` | `AesGcmMessageCipherTest` |
| Ratchet | `Ratchet.kt`, `RatchetState.kt` | `RatchetTest`, `RatchetPropertyTest`, `RatchetPaddingTest`, `ParserFuzzTest` |
| X25519 efímero | `bridge.go` (`RatchetKeyPair`, `RatchetAgree`), `BridgeCurve25519` | Go `TestRatchetKeyPairAgreement`, `JdkCurve25519` |
| Persistencia, cerrojo, deduplicación | `RatchetSessions.kt`, `RoomRatchetStore.kt`, `RatchetDao` | `RatchetSessionsTest`, `RatchetSeenPruneSqlTest` |
| Relleno | `Padding.kt` | `PaddingTest`, `RatchetPaddingTest` |
| Capacidades | `ChatService` (`usesRatchet`, `pads`, `seal`, `onHello`, `learnFromRatchet`, `rehook`, `announceCapabilities`), `ContactDao` | `ChatServiceTest`, `ContactUpsertSqlTest` |
| Llamadas | `CallService.kt`, `ChatService.recordMissedCall` | `CallServiceTest`, `ChatServiceTest`, Go `TestCallStreamEcho`, `TestVideoStreamEcho` |
| Transporte bajo las llamadas (W-8) | go-libp2p: TLS 1.3 o Noise por defecto, también en el circuito del relay | Go `TestTransporteRechazaBytesManipulados`, `TestRelayNoVeLoQueViajaPorElCircuito`, `TestRelayMessagingLocal` |
| Buzón ciego | `MailboxLabel.kt`, `ChatService` (`outboxLabel`, `inboxLabels`), `infra/node/mailbox.go` | `MailboxLabelTest`, `ChatServiceTest`, Go `TestMailboxV2NoGuardaNiRemitenteNiDestinatario`, `TestMailboxV2LaEtiquetaEsLaLlave` |
| Rendezvous | `RendezvousService.kt` | `RendezvousServiceTest` |
| Número de seguridad | `SafetyNumber.kt`, `QrCode` | `SafetyNumberTest`, `QrCodeTest` |
| `.krbk` | `IdentityBackup.kt`, `BackupManager.kt` | `IdentityBackupTest`, `ParserFuzzTest` |

---

## 15. Preguntas concretas para quien revise

1. ¿La reformulación **por épocas** (§5, DISENO-ratchet §2) conserva el secreto hacia adelante y la
   recuperación tras compromiso del doble ratchet? En particular: una época avanza por **mensaje
   recibido**, no por turno, y la raíz de las épocas ≥ 1 no lleva el linaje en `info`.
2. **Linaje** (W-4, W-6): ¿hay una regla que conserve la recuperación automática tras perder el
   estado sin permitir que quien tenga `S` secuestre la sesión? Candidatas que no se han hecho:
   acotar `L` a «ahora + margen», exigir prueba de época ≥ 1 para adoptar, no adoptar nunca un
   linaje de una sesión con épocas avanzadas sin confirmación del usuario. La misma regla tendría que
   resolver W-6: tras perder el estado con el reloj atrasado, un sentido queda roto y no se recupera
   solo (fijado en `RatchetTest`).
3. **Época 0 derivable** (W-1, W-2): ¿es aceptable que la protección contra reproducciones dependa de
   una tabla de deduplicación con ventana, o conviene rechazar la época 0 de un linaje que ya no es
   el vigente?
4. **Autenticación a nivel de `S`** (W-3, W-5): ¿vale la pena firmar los sobres con la identidad
   (resistencia a KCI) a cambio de perder la negación, que Krypta no promete? ¿Cuándo debería dejar
   de aceptarse v1? Hoy **no se promete ninguna de las dos**: ni resistencia a KCI (W-3) ni negación.
   Los sobres no van firmados y en la práctica son negables, pero no hay análisis que lo respalde.
5. **Llamadas** (W-8, P15): hoy lo que impide repetir, reordenar o reflejar frames es el transporte
   de libp2p, comprobado con tests. ¿Conviene un contador por frame y claves separadas por sentido
   para no depender de él? ¿Es suficiente la regla del `invite` de §8 (una vez por `callId` en RAM,
   10 min hacia el futuro, 45 s hacia el pasado, fila de perdida idempotente)?
6. **`PN` sin usar** (W-9) y `MAX_SKIPPED_KEYS = 2000` global con descarte de las más antiguas:
   ¿hay pérdida o abuso que no se haya visto?
7. **Negociación de capacidades** (§7): ¿se puede forzar una degradación por alguna vía que no sea
   el anuncio `V`?
8. **Reutilización de la semilla** Ed25519 para firmar y para X25519 (W-12).
9. El **ratchet post-cuántico lento** de [DISENO-postcuantico.md](DISENO-postcuantico.md) frente a
   SPQR de Signal.
10. La **rotación de identidad** de [DISENO-rotacion-identidad.md](DISENO-rotacion-identidad.md),
    que depende de todo lo anterior.
