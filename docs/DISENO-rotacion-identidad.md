# Diseño: rotación voluntaria de identidad

**Estado:** diseño revisable, **sin código** (12 sep 2026). No se implementa antes de pasar las
pruebas con dos móviles de PRUEBAS-PENDIENTES §16: toca el camino de envío y recepción, que está
congelado hasta entonces, y conviene que entre en el paquete de la revisión externa.
**Origen:** [PLAN-privacidad-y-confianza.md](PLAN-privacidad-y-confianza.md) §5.1 y la comparación
con Signal ([REVISION-comparacion-signal-2026-09-12.md](REVISION-comparacion-signal-2026-09-12.md)):
Signal tiene revocación y recuperación; Krypta, ninguna de las dos.

---

## 0. Léase antes de decidir

Hoy **el PeerID es la identidad** y no hay forma de cambiarlo sin perder a todos los contactos:
generar una identidad nueva obliga a que cada contacto te vuelva a añadir y a verificar
([security-model.md](security-model.md) §3). Este diseño permite **pasar a una clave nueva
conservando contactos y conversaciones**, con un aviso firmado por la clave anterior.

Tres cosas conviene tenerlas claras antes de leer el resto:

1. **No protege contra quien ya tiene tu clave.** Quien la haya robado puede rotar igual que tú, y
   para tus contactos su aviso es tan válido como el tuyo. Lo único que el diseño añade frente a eso
   es **detectar la bifurcación**: si un contacto recibe dos rotaciones distintas firmadas por la
   misma clave, congela la conversación y avisa (§3.2). Sin servidor no hay orden global, así que
   esto es más débil que el bloqueo de registro de Signal, que se apoya en un PIN guardado por su
   servidor.
2. **No sirve si has perdido la clave.** Rotar exige firmar con la identidad anterior. Un móvil
   perdido sin `.krbk` sigue significando volver a añadirse.
3. **Sí sirve** para lo que hoy no tiene salida:
   - **Sospecha de exposición con la clave aún en tu mano**: una compilación de depuración
     repartida (`run-as`), un móvil prestado, un PeerID que acabó publicado. Pasó el 12 sep 2026:
     el PeerID del móvil del autor quedó en el historial público del repositorio.
   - **Higiene**: cambiar de identidad cada cierto tiempo sin coste social.
   - **El camino hacia una autenticación post-cuántica**: [DISENO-postcuantico.md](DISENO-postcuantico.md)
     §6 dice que la autenticación no pasa a ser post-cuántica. Cambiar de formato de identidad algún
     día (ML-DSA) exige exactamente este mecanismo.

---

## 1. Lo que depende hoy del PeerID (comprobado en el código, 12 sep 2026)

| Pieza | Dónde | Qué pasa al rotar |
|---|---|---|
| Secreto compartido `S = X25519(identidad, PeerID)` | `Bridge.sharedSecretFor`, `Libp2pNode.sharedSecretWith` | Cambia para **todas** las parejas |
| Sesiones del ratchet | `ratchet_sessions` / `ratchet_seen`, por `conversationId` | Nacen de `S`: hay que abrir un linaje nuevo |
| Etiquetas del buzón ciego | `MailboxLabel.inbox/outbox(S, miPeerId, suPeerId, semana)` | Cambian; las viejas siguen valiendo para el correo ya depositado |
| Rendezvous | `RendezvousService` sobre `S` y la fecha | Cambia solo, al recalcularse cada ciclo |
| Número de seguridad | `SafetyNumber.compute(peerIdA, peerIdB)` | Cambia para todas las parejas |
| Filtro de conexiones | `pushAllowedPeers`, lista de `contact.peerId` | Hay que meter el PeerID nuevo |
| Buzón v1 | Direccionado por PeerID y autenticado por la identidad del stream | Retirar lo depositado al PeerID viejo exige un host con la clave vieja |
| Clave de llamada | Negociada por llamada, con `salt = S` | Se renegocia sola: nada que migrar |
| `Contact.id` | Nace igual al PeerID (`addContact`) y es la clave de `messages.conversationId` y de las tablas del ratchet; `peerId` es otra columna | Puede **quedarse como identificador local** y cambiar solo `peerId` |
| Identidad local | `private val identity by lazy` (`Libp2pNode.kt:56`) | Cambiarla exige reiniciar el proceso, como ya hace la importación del `.krbk` |
| Firma Ed25519 | El puente Go **no exporta** ni firma ni verificación | Hay que añadirlas |

Un detalle que simplifica mucho: **las resoluciones que llegan de la red ya buscan por `peerId`**
(`ChatService.kt:549` y `:1077`, `findByPeerId`), y las que buscan por `id` usan el
`conversationId` local. La única que asume `id == PeerID` es `addContact` (`findById(peerId)` y
`id = peerId`): con un contacto rotado, volver a añadirlo por su PeerID nuevo crearía una
conversación duplicada. Hay que cambiarla a `findByPeerId`.

---

## 2. El mecanismo

### 2.1 El aviso de rotación

Un sobre nuevo, tipo `M` (migración). Los clientes anteriores lo reciben como
`Decoded.Unsupported` y lo ignoran sin romper nada, igual que pasó con `V`. Su contenido es un
texto canónico más dos firmas:

```
krypta-rotation-v1
old=<PeerID antiguo>
new=<PeerID nuevo, o vacío para revocar sin sustituto (§3.3)>
seq=<1, 2, 3… — cuántas rotaciones lleva esta identidad>
ts=<unix millis>
```

- `sig_old` = Ed25519 de la **clave antigua** sobre ese texto: autoriza el cambio.
- `sig_new` = Ed25519 de la **clave nueva** sobre el mismo texto: demuestra que quien rota tiene la
  clave nueva. Sin ella, quien tuviera la clave vieja podría hacer que tus contactos «migraran» al
  PeerID de un tercero.
- La primera línea es **separación de dominio**. La misma clave Ed25519 firma otras cosas en libp2p
  (el handshake de Noise, los registros de identify); el prefijo impide que una firma de rotación se
  confunda con cualquiera de ellas, o al revés.

El aviso **viaja dentro del cifrado de extremo a extremo**, por el camino normal (`seal`, directo y
luego buzón). No se publica en la DHT ni en ningún sitio en claro: el vínculo «este PeerID viejo es
este PeerID nuevo» es un metadato, y el nodo no tiene por qué aprenderlo.

### 2.2 Quien rota

1. **Prepara.** Genera la clave nueva sin activarla, firma el aviso con las dos y enseña al usuario
   qué contactos **no lo van a entender** (han anunciado un protocolo menor que
   `ROTATION_MIN_PROTOCOL`). Esos tendrán que volver a añadirle; se dice antes de confirmar, no
   después.
2. **Avisa.** Manda el aviso a cada contacto **no bloqueado** con la identidad todavía vieja y anota
   por contacto si salió, igual que `announceCapabilities`. A los bloqueados **no**: el bloqueo
   corta todo envío (`requireNotBlocked`) y la persona bloqueada no recibe ninguna señal, así que
   avisarle le daría justo el PeerID nuevo que el bloqueo existe para no darle. Tras rotar, sus
   mensajes van a una identidad que ya no se atiende. Si el usuario desbloquea después, esa persona
   tendrá que volver a añadirle.
3. **Cambia.** Persiste la clave nueva como activa y la vieja como **anterior** (envuelta por el
   Keystore, con su fecha de caducidad) y reinicia el proceso. Es el mismo flujo que ya existe para
   importar un `.krbk`.
4. **Periodo de gracia**, 14 días (el doble del TTL del buzón). Durante ese tiempo la clave anterior
   se conserva **solo** para:
   - retirar el correo depositado bajo las **etiquetas viejas**. Son credenciales al portador, así
     que no hace falta un host con la identidad vieja;
   - abrir los mensajes que lleguen tarde cifrados con el `S` viejo;
   - reenviar el aviso a quien todavía no lo haya recibido.

   Lo que **no** funciona en la gracia: conexiones directas y rendezvous con la identidad vieja,
   porque el host solo tiene la nueva. Un contacto que aún no se haya enterado cae al buzón ciego y
   el mensaje llega igual. El buzón v1 por PeerID viejo **no se atiende**: el mínimo de protocolo de
   la rotación implica el del depósito ciego (≥ 2).
5. **Borra** la clave anterior al vencer la gracia.

### 2.3 Quien recibe

1. **Verifica**: el remitente es el contacto C y `old` es su `peerId` actual; `sig_old` valida
   contra `old` y `sig_new` contra `new`; `seq` es mayor que el último visto de C; `new` no es el
   PeerID propio ni el de otro contacto.
2. **Aplica, en una sola transacción**: `peerId = new`, `previousPeerId = old`,
   `previousUntil = ahora + gracia`, `rotationSeq = seq`; olvida la sesión del ratchet de esa
   conversación (el siguiente mensaje abre un linaje nuevo sobre el `S` nuevo); marca la verificación
   según §3.1; y guarda una burbuja de sistema: «X cambió de identidad. El número de seguridad es
   otro». `id`, el historial y el nombre no cambian.
3. **Durante la gracia**, resolver por PeerID mira también `previousPeerId`, para los mensajes que
   lleguen tarde por el camino viejo. El filtro de conexiones admite los dos PeerID.
4. **Contesta** con un sobre de control (el anuncio `V`) ya sobre el `S` nuevo. Motivo: rotar deja
   **a cada pareja en la época 0** de un linaje nuevo, sin secreto hacia adelante (§1.8.4 de
   [DISENO-ratchet.md](DISENO-ratchet.md)). Esa ida y vuelta automática la saca de ahí antes de que
   nadie escriba.

---

## 3. Verificación, el ladrón y la revocación

### 3.1 ¿Se hereda la verificación?

Una rotación firmada por una clave verificada tiene **la misma autenticidad que esa clave**: si la
clave vieja era de verdad de tu contacto, la nueva también. El problema es el caso para el que más
importa: si la clave vieja estaba robada, heredar la verificación le da al ladrón un escudo verde.

- **Opción A — heredarla**, con una marca «verificada por su identidad anterior». Cómoda, pero hace
  invisible justo el ataque del ladrón.
- **Opción B — perderla** y mostrar un aviso visible: «cambió de identidad; la nueva está firmada por
  la anterior. Si no te lo esperabas, compara el número de seguridad fuera de banda». Es lo que hace
  Signal ante un cambio de clave.

**Recomendación: B.** Un escudo que se hereda sin que nadie compare nada deja de significar «lo he
comprobado».

### 3.2 Bifurcación: dos rotaciones de la misma clave

Si un contacto recibe dos avisos válidos con el mismo `old` y el mismo `seq` pero distinto `new`, la
clave vieja está en dos manos. Entonces:

- la conversación pasa a **identidad en disputa**: no se envía nada y no se aplica ninguna de las dos;
- se avisa al usuario de forma prominente: solo una verificación fuera de banda resuelve cuál es la
  buena;
- los dos PeerID quedan anotados para poder elegir.

Límites que hay que decir: solo se detecta si **los dos avisos llegan** al contacto. Quien avisa
primero gana en los contactos que no vean el segundo. Si el dueño sospecha un robo, rotar cuanto
antes es su mejor carta, no una garantía.

### 3.3 Revocar sin sustituto

El mismo aviso con `new` vacío (y sin `sig_new`) significa «esta identidad está comprometida, no me
escribas». El contacto la marca como **revocada**: no envía nada hasta que se vuelva a añadir y a
verificar. Un ladrón también puede mandarla, pero lo único que consigue es **cortar** la
comunicación, no suplantar a nadie. Es el interruptor de emergencia de quien tiene la clave y ya no
se fía de ella.

### 3.4 Repeticiones

Un aviso viejo repetido no hace nada: o su `old` ya no es el `peerId` del contacto, o su `seq` no es
mayor que el último visto. Los avisos dentro de la gracia se deduplican como cualquier otro mensaje.

---

## 4. Estado y disco

- **`contacts`**, DB **v10**, migración aditiva:
  - `previousPeerId TEXT NULL` y `previousUntil INTEGER NULL`;
  - `rotationSeq INTEGER NOT NULL DEFAULT 0`;
  - `identityState INTEGER NOT NULL DEFAULT 0` (0 normal, 1 en disputa, 2 revocada);
  - `rotationNoticeSeq INTEGER NOT NULL DEFAULT 0`, el último aviso **que le hemos entregado**,
    para reenviarlo en la gracia sin repetirlo en cada ciclo.
- **Identidad local**, en `krypta_identity`: además de `ed25519_wrapped`, `ed25519_prev_wrapped`,
  `prev_until` y `rotation_seq`, envueltos igual por el Keystore y con la misma disciplina de «nunca
  perder la identidad» de `IdentityStore`.
- **`.krbk`**: dos líneas nuevas, `prev=` y `prev_until=`. `IdentityBackup.parse` ignora las líneas
  que no conoce, así que una versión anterior sigue leyendo el fichero (pierde la identidad anterior,
  que es lo aceptable).
- **`addContact`** deduplica por `findByPeerId`, mirando también `previousPeerId`.

---

## 5. Dónde vive

| Pieza | Módulo | Nota |
|---|---|---|
| `Bridge.SignWithIdentity(identidad, msg)`, `Bridge.VerifyForPeerID(peerId, msg, firma)`, `Bridge.NewIdentity()` | `native-bridge/libp2p` | libp2p ya tiene la clave pública dentro del PeerID; verificar no necesita nada más |
| `IdentitySigner` (interfaz) | `:core` | Mismo patrón que `Curve25519` y `Kem`: el puente en el móvil, Ed25519 del JDK en los tests JVM |
| `RotationNotice` (texto canónico, codificar, verificar) | `:p2p-signaling` | Kotlin puro, con un **vector cruzado**: firmado en Go y verificado con el JDK, como el KAT de ML-KEM |
| Envío, recepción, gracia, bifurcación | `ChatService` | Detrás de `ROTATION_MIN_PROTOCOL = 4`, cada capacidad con su mínimo |
| Cambio de identidad y reinicio | `Libp2pNode` + `BackupManager` | Reutiliza el flujo de importación |
| UI | Ajustes («Cambiar de identidad», con la lista de quién no lo entenderá) y burbuja de sistema en el chat | |

---

## 6. Qué **no** arregla (leer antes de prometer nada)

- **Perder la clave sin copia**: sin clave no hay firma, así que toca volver a añadirse.
- **Un ladrón con la clave**: solo se detecta la bifurcación si los dos avisos llegan (§3.2).
- **Que tus contactos sepan que el PeerID viejo y el nuevo son la misma persona**: es el propósito.
  El nodo no lo aprende (va cifrado), pero una ráfaga de depósitos a todos tus contactos a la vez es
  un patrón de tráfico visible.
- **La época 0 de cada pareja** justo después de rotar: se mitiga con la ida y vuelta automática
  (§2.3.4), no se elimina.
- **Los contactos con un protocolo anterior** pierden la conversación y tienen que volver a añadirte.
- **Lo ya publicado**: un PeerID viejo que está en el historial de git lo sigue estando. Rotar
  hace que deje de servir para localizarte, no que desaparezca.

---

## 7. Preguntas abiertas (hay que decidirlas antes de escribir código)

1. **Verificación heredada o perdida.** Recomendación: perdida (§3.1, opción B).
2. **Duración de la gracia.** Propuesta: 14 días.
3. **Revocación sin sustituto en la misma entrega.** Recomendación: sí; es poco código más y es la
   mitad de lo que Signal tiene y Krypta no.
4. **Permitir rotar si hay contactos con un protocolo anterior.** Recomendación: sí, con la lista
   explícita antes de confirmar.
5. **Aplicar solo o pedir confirmación al receptor.** Recomendación: aplicar solo y con aviso
   visible; congelar únicamente ante una bifurcación.
6. **Espera mínima entre rotaciones** (por ejemplo, una cada 24 h), para que un ladrón y el dueño no
   jueguen al ping-pong con los contactos. Recomendación: sí.

---

## 8. Plan por fases

| Fase | Qué | Dónde | Toca el protocolo |
|---|---|---|---|
| 0 | Decidir §7 | — | no |
| 1 | Firma y verificación Ed25519 en el puente y en `:core`, con vector cruzado Go↔JDK | `native-bridge`, `:core` | no |
| 2 | `RotationNotice`: formato canónico, codificar, verificar, tests de los dos bordes (firmas, `seq`, dominio) | `:p2p-signaling` | no (sin cablear) |
| 3 | DB v10, `addContact` por `findByPeerId`, resolución por `previousPeerId`, tests de migración | `:data`, `ChatService` | no |
| 4 | Recepción: aplicar, bifurcación, revocación, ida y vuelta de control | `ChatService` | **sí** |
| 5 | Envío y gracia: aviso a todos, cambio de identidad con reinicio, retirada por etiquetas viejas, apertura con el `S` viejo | `ChatService`, `Libp2pNode` | **sí** |
| 6 | UI | `:app` | no |
| 7 | Pruebas con dos (o tres) móviles, escritas en PRUEBAS-PENDIENTES antes de encender nada | — | — |
| 8 | Docs: `security-model.md` §3 deja de decir «no hay revocación» **solo cuando pase la fase 7** | `docs/` | — |

Las fases 1–3 no cambian nada de lo que viaja ni de cómo se abre, igual que las fases 1–2 del diseño
post-cuántico; las 4 y 5 sí, y por eso esperan a §16 y a la revisión externa.

---

## 9. Recomendación

Aprobar el diseño con las respuestas recomendadas del §7 y **no escribir nada de las fases 4–5 antes
de pasar §16**. Las fases 1–3 pueden adelantarse sin romper la congelación del protocolo, pero no
aportan nada al usuario por sí solas; solo tienen sentido si la rotación va a hacerse de verdad.

Una nota para Nyx: comparte transporte y criptografía, así que todo lo anterior le aplica igual, y el
formato del aviso debería fijarse **antes** de que ninguno de los dos lo cablee, para que no diverjan.
