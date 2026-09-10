# Diseño: secreto hacia adelante (ratchet)

**Estado:** **decidido el 9 sep 2026** (§8 cerrado: ratchet por épocas, historial en claro dentro
de la base cifrada, anuncio de capacidad por contacto, llamadas y adjuntos dentro del alcance).
**Fases 1 a 9 hechas** (9-10 sep 2026): el núcleo (`Ratchet`, `RatchetState`, 17 tests), el X25519
del puente Go, la persistencia con su transacción atómica (Room **v7**, `RatchetSessions`) y el
historial en claro dentro de la base cifrada (Room **v8**, convertido en el TECNO sobre la base
real: 47 mensajes) la **recepción v2 + anuncio de capacidad** (Room **v9**) y el **envío por contacto** (fase 6).
**El envío se encendió el 10 sep 2026** (`ChatService.RATCHET_SEND = true`), por decisión del
autor y **antes** de la prueba con dos móviles que pedía el §10: la colaboradora que presta el
segundo móvil no responde y eso tenía el trabajo parado. Ojo a lo que eso significa de verdad:
como a quién se le escribe con ratchet lo decide `contact.peerProtocol`, **no cambia nada hasta
que el otro extremo actualiza**. La prueba sigue pendiente
([PRUEBAS-PENDIENTES.md §16](PRUEBAS-PENDIENTES.md)) y conviene hacerla con un contacto
desechable en cuanto los dos móviles tengan este build. Nada de esto está cableado todavía: la app sigue cifrando exactamente igual que
antes. Lo aprendido al implementarlo está en el §1.8.
**Fecha:** 9 de septiembre de 2026.
**Origen:** [AUDITORIA-2026-09-07.md](AUDITORIA-2026-09-07.md) A-5 y
[security-model.md](security-model.md) §4 y §10 — «sin PFS, comprometer el dispositivo descifra
todo el historial». Es, junto con el depósito ciego, el trabajo criptográfico grande que queda.

---

## 0. Léase esto antes de decidir

Hay un hecho de Krypta que cambia el problema respecto a Signal, y del que sale casi todo lo
demás de este documento:

> **El secreto compartido no se puede perder ni caducar: es una función pura de las dos
> identidades.** `S = X25519(mi_identidad, PeerID_del_contacto)`. Quien tenga la identidad puede
> recalcularlo hoy, mañana y dentro de diez años, sin hablar con nadie.

Tres consecuencias:

1. **Un ratchet solo simétrico (cadena de claves sin DH) no sirve para nada aquí.** Sería
   `CK₀ = HKDF(S, …)` y de ahí una cadena; pero quien tenga la identidad recalcula `S`, recalcula
   `CK₀` y desenrolla la cadena entera. Se cumpliría la letra del «borramos la clave de cada
   mensaje» sin cumplir nada del espíritu. **Todo el valor está en el ratchet DH**: en
   aleatoriedad efímera que nunca se pueda derivar de la identidad y que se borre.
2. **A cambio, la sesión nunca se puede romper del todo.** Signal, si pierde el estado, necesita
   volver a hacer X3DH contra el servidor de prekeys. Krypta siempre puede volver a la época 0,
   que es derivable de `S` por los dos lados sin negociar. Eso convierte «se corrompió el
   ratchet» de un fallo permanente en una reconexión automática. Es la mejor propiedad que
   tenemos y hay que diseñar alrededor de ella.
3. **No hace falta X3DH, ni servidor de prekeys, ni ronda de establecimiento.** El estado
   inicial se deriva de `S` a los dos lados. El primer mensaje sale sin ida y vuelta, como hoy.

Y un límite que conviene decir ya, porque acota lo que el documento de seguridad podrá prometer:

| Qué protege el ratchet | Qué **no** |
|---|---|
| Tráfico capturado en el relay o en el buzón: robar la identidad mañana ya no lo abre | Los metadatos: el nodo sigue viendo el grafo en vivo (relay) y la presencia (wake) |
| El historial ya entregado, **si deja de guardarse cifrado con la clave estática** (§4) | Las etiquetas de buzón y el rendezvous, que se derivan de `S` y por tanto siguen siendo recalculables para siempre |
| Las llamadas, si la clave por llamada pasa a viajar dentro del ratchet (§6) | Los adjuntos de `krypta_files/`, que siguen en claro en disco |
| Post-compromiso: tras un robo del móvil, una vez que ambos giran claves el atacante pasivo se queda fuera | Un atacante **activo** dentro del proceso o con root: ahí no hay criptografía que valga |

---

## 1. El mecanismo: ratchet por épocas

Es un doble ratchet (cadena simétrica por mensaje + ratchet DH por época) con **una desviación
deliberada respecto al de Signal**: la época no la define «quién habló último» sino **el par de
claves públicas efímeras vigentes**, y la transición es simétrica y determinista. El §2 explica
por qué.

### 1.1 Época 0 — derivable de `S`, sin PFS, a propósito

```
RK₀      = HKDF(S, info = "krypta-rtc-root:0")
CK₀(dir) = HKDF(RK₀, info = "krypta-rtc-chain:0:" ‖ dir)      dir = 0 si peerID_emisor < peerID_destinatario, si no 1
```

Los dos lados la calculan sin hablar. Cualquiera de los dos puede enviar primero, sin esperar
nada, y **sin la carrera de «los dos empiezan a la vez»** que sale en cuanto hay roles.

Que la época 0 no tenga PFS no es un descuido: es exactamente la propiedad que tiene el primer
mensaje de una sesión de Signal antes de que el destinatario responda, y aquí es lo que compra
poder arrancar sin servidor. **Lo que importa es salir de ella pronto** (§1.3).

### 1.2 Época e ≥ 1 — el ratchet DH

Cada lado mantiene un par efímero X25519 por época. La época `e` queda definida por el **par**
`(pub_A(e), pub_B(e))`, y su raíz encadena con la anterior:

```
RK(e)      = HKDF(ikm = X25519(priv_propia(e), pub_del_otro(e)), salt = RK(e-1), info = "krypta-rtc-root:e")
CK(e, dir) = HKDF(RK(e), info = "krypta-rtc-chain:e:" ‖ dir)
```

`X25519` es simétrico, así que A y B obtienen el mismo `RK(e)` desde lados opuestos. La raíz
encadena (`salt = RK(e-1)`) para que la seguridad de la época `e` no dependa de que *ese* DH
concreto fuera bueno, sino de que **alguno** de los anteriores lo fuera — es la propiedad de
composición del doble ratchet y no conviene perderla.

### 1.3 Cómo se avanza de época

Cada mensaje lleva en su cabecera **mi propuesta para la época siguiente** (`next_pub`). La regla
es una sola, y es lo que hace que no haya carrera:

> **Se pasa a la época `e+1` en cuanto se tienen las dos claves públicas de `e+1`** (la propia,
> que uno genera cuando quiere, y la del otro, que llega en cualquier cabecera).

No hay iniciador ni respondedor, no hay «el que habla cambia la cadena»: los dos aplican la misma
condición sobre los mismos datos y llegan al mismo sitio. Un mensaje dice siempre en qué época
va, así que el desorden entre épocas se resuelve guardando las cadenas de las últimas `K` (§1.5).

En la práctica se avanza rápido y sin que el usuario haga nada: **el acuse de lectura también es
un mensaje**, y sale solo con abrir el chat. Con que el otro abra la conversación una vez, la
conversación sale de la época 0.

### 1.4 Cadena simétrica y clave de mensaje

Como en Signal, con HMAC-SHA256 sobre la clave de cadena:

```
mk      = HMAC(CK, 0x01)      →   clave del mensaje N
CK'     = HMAC(CK, 0x02)      →   clave de cadena para N+1
k ‖ iv  = HKDF(mk, info = "krypta-rtc-msg", 44)      →   AES-256 (32) ‖ nonce (12)
```

El nonce se **deriva**, no se sortea: cada `mk` se usa una sola vez, así que un nonce aleatorio
solo gastaría 12 bytes por mensaje. `CK` se sustituye por `CK'` y `mk` se borra en cuanto el
mensaje queda persistido (§4.2, que es donde está el peligro).

### 1.5 Desorden, pérdidas y duplicados

- **Huecos dentro de una cadena** (llegan N=5 y N=3 falta): se derivan y **guardan** las claves
  saltadas, hasta `MAX_SKIP = 1000` por cadena. Más allá se rechaza el mensaje: si no, un peer
  malicioso que anuncie `N = 2³¹` nos pone a derivar dos mil millones de HMAC.
- **Huecos entre épocas**: la cabecera lleva `PN`, cuántos mensajes hubo en la cadena de la época
  anterior, para poder cerrar sus claves saltadas antes de abandonarla. Se conservan las cadenas
  de las últimas `K = 3` épocas.
- **Duplicados**: el buzón reentrega lo que no se acusa, así que el mismo ciphertext puede llegar
  dos veces — y a la segunda su `mk` ya está borrada. Hoy eso es inofensivo (clave estática);
  con ratchet hay que **deduplicar antes de descifrar**, por hash del ciphertext, con una tabla
  acotada. Ver §4.2.
- **Estado perdido o ilegible**: se vuelve a la época 0 (§1.6). Nunca se pierde una conversación.

### 1.6 Reinicio de linaje

La cabecera lleva un `lineage` (unix millis de cuándo se creó ese linaje). Si un lado pierde el
estado —reinstalación, importación de un `.krbk` en un móvil nuevo, corrupción— arranca un linaje
nuevo con la hora actual y envía en época 0, que el otro **siempre** sabe descifrar. El que recibe
un linaje **estrictamente mayor** que el suyo tira su estado y adopta el nuevo; uno menor se
intenta contra el linaje anterior que aún conserve (mensajes en vuelo) y si no, se descarta.

Coste honesto: quien pueda **reproducir** un mensaje antiguo de época 0 y linaje alto fuerza una
degradación a época 0 durante una ronda. Lo acota la deduplicación (§1.5), que descarta el
duplicado antes de mirarlo. Forjar uno nuevo exige `S`, y quien tiene `S` ya tiene la identidad.

### 1.7 Formato de la cabecera

Va **en claro** (el AEAD la autentica como AAD, así que no se puede tocar) delante del ciphertext:

```
0        0x02          versión del sobre de transporte (hoy: nonce(12) ‖ ct+tag)
1        flags         reservado, 0
2..9     lineage       u64 BE, unix millis
10..13   epoch         u32 BE
14..17   N             u32 BE, nº de mensaje en la cadena
18..21   PN            u32 BE, mensajes de la cadena de la época anterior
22..53   next_pub      X25519 (32), mi propuesta para epoch+1
54..     ct ‖ tag       AES-256-GCM con AAD = bytes 0..53
```

70 bytes de sobrecoste por mensaje frente a los 28 de hoy. Irrelevante para un trozo de 48 KiB;
para un acuse de lectura, que hoy son ~60 bytes, lo dobla — y sigue siendo irrelevante.

**Compatibilidad**: un ciphertext v1 son bytes arbitrarios, así que no hay magia que distinga uno
de otro con certeza. No hace falta: se intenta v2 y, si el AEAD falla, se intenta v1. **El AEAD
es el árbitro; el byte de versión solo decide en qué orden se prueba.**

### 1.8 Lo que enseñó implementarlo (9 sep 2026)

Tres cosas que el diseño sobre el papel no decía, y que están fijadas en `RatchetTest`:

1. **La época avanza por mensaje recibido, no por turno de conversación.** Cada mensaje lleva una
   propuesta nueva para la época siguiente y se consume en el acto, así que quien recibe un
   mensaje de la época `e` entra en `e` y sigue hasta `e+1` con la propuesta que venía dentro.
   Sale más ratchet DH del previsto —uno por mensaje recibido, no uno por ida y vuelta— a cambio
   de un X25519 por mensaje, que a este volumen no se nota. Se mantiene el invariante del que
   depende `decrypt`: **los dos extremos nunca se separan más de una época**, porque avanzar
   exige una propuesta del otro y cada mensaje suyo trae exactamente una.
2. **Una ráfaga no dispara una época por trozo.** Los 85 trozos de un archivo van todos en la
   misma época y llevan la misma propuesta; el receptor avanza con el primero y los demás
   entran por la cadena retirada. Una época por ráfaga, no ochenta y cinco.
3. **Una cabecera forjada no puede mover el estado**, y no por una comprobación sino por la
   forma: `encrypt`/`decrypt` son funciones puras y el estado nuevo solo existe si el AEAD ha
   validado. Un linaje altísimo inventado —el ataque de degradación del §1.6— muere ahí. Lo que
   queda es la **reproducción de un mensaje genuino de época 0**, y de eso se encarga la
   deduplicación del §4.2, que hay que hacer igualmente.

---

## 2. Por qué no el doble ratchet tal cual

Merece la pena dejar escrito el callejón, porque el diseño de arriba es una desviación y las
desviaciones en criptografía hay que justificarlas.

El ratchet de Signal es **asimétrico**: hay un iniciador que arranca con la prekey firmada del
otro, y el ratchet DH avanza cada vez que cambia el sentido de la conversación. Sin servidor de
prekeys, la única forma de fijar quién es el iniciador es una regla como la del `MailboxLabel`
(orden canónico de los PeerID). Y ahí aparece el problema:

- El estado inicial del respondedor (su primer par efímero) tendría que ser **derivable de `S`**
  para que el iniciador pueda escribir sin ronda previa. Es asumible (equivale a la época 0).
- Pero **si los dos escriben primero a la vez**, cada uno ha girado ya su clave cuando llega el
  mensaje del otro, y las dos raíces se bifurcan: A derivó contra la clave inicial de B, B contra
  la inicial de A, y a partir de ahí cada uno tiene una cadena de raíz distinta. La cadena de
  raíz del doble ratchet **no tolera esa bifurcación**: no es que se pierda un mensaje, es que
  las dos sesiones divergen y no vuelven.

En Signal eso no pasa porque los roles vienen dados por X3DH y por un servidor que ordena. Aquí
no hay servidor. Se puede parchear (llevar en la cabecera contra qué clave del otro se derivó, y
guardar las privadas propias recientes), pero entonces hay que identificar **también** contra qué
raíz, y se acaba con un árbol de raíces y un montón de casos límite mal cubiertos por los tests.

La reformulación por épocas (§1.3) elimina la causa: la época es un **par** de claves, la
transición depende de datos que ambos ven, y no hay ningún estado que dependa del orden en que
ocurrieron las cosas. Se conservan las dos propiedades que importan —PFS por la cadena simétrica,
recuperación post-compromiso por el DH, encadenada por la raíz— y se pierde solo el gradiente
fino de Signal (allí el ratchet DH puede avanzar en cada cambio de turno; aquí una época necesita
que los dos hayan aportado clave, que en la práctica es lo mismo: una ida y vuelta).

**Esta es la parte del proyecto que más se beneficiaría de una revisión externa.** No es «rodar
tu propia criptografía» en el sentido malo —las primitivas son X25519, HMAC-SHA256, HKDF y
AES-GCM, todas estándar y ya en uso aquí— pero sí es un protocolo propio, y los protocolos se
rompen en los casos límite.

La tercera opción, **usar `libsignal-client`**, se descarta por lo mismo que obliga a desviarse:
su API exige `PreKeyBundle`s firmados por la identidad del otro, que no podemos fabricar sin una
ronda previa por el canal actual; encima añade una dependencia nativa por ABI a un APK que ya
pesa 66 MB. Si algún día hay un servidor de prekeys, esta decisión se revisa.

---

## 3. Dónde vive

| Pieza | Módulo | Por qué |
|---|---|---|
| `Ratchet` (épocas, cadenas, claves saltadas, cabecera, serialización del estado) | `:p2p-signaling` | Kotlin puro y testable en JVM, como `Hkdf`/`MailboxLabel`/`SafetyNumber` |
| `Curve25519` (generar par, acordar) | interfaz en `:core` | Android no trae `XDH` hasta API 33 y el `minSdk` es 30 |
| — implementación en dispositivo | `:native-bridge` → Go | el puente ya hace X25519 en `SharedSecretFor`; son ~15 líneas y un AAR nuevo |
| — implementación en tests | `:p2p-signaling` (test) | el JDK trae `KeyAgreement("XDH")` desde Java 11 |
| Persistencia del estado | `:data` (Room v7) | va cifrado con el resto de la base (SQLCipher, 9 sep) |
| Cableado (cifrar/descifrar, época, contactos) | `ChatService` + `AesGcmMessageCipher` | es el único sitio que hoy llama a `cipher.encrypt/decrypt` |

`MessageCipher` tal como está (`encrypt(sharedSecret, plaintext)`) **no vale**: no tiene identidad
de conversación ni estado. Pasa a `encrypt(contact, plaintext)` con el ratchet detrás, y el modo
estático se queda como el camino de descifrado v1 para el historial y para los contactos que aún
no han actualizado.

---

## 4. La consecuencia estructural: el historial no se puede seguir guardando así

**Esto es lo más importante del documento y lo que más código toca.**

Hoy `Message.ciphertext` guarda **los bytes de la red**, y toda la lectura descifra al vuelo con
la clave estática: `decrypt`, `decodeMessage`, `content`, `notificationText`, la vista previa de
la lista de conversaciones. Funciona porque la clave nunca cambia.

Con ratchet la clave de cada mensaje **se borra al usarla**. Guardar el ciphertext de transporte
significa guardar algo que dentro de un minuto ya no se puede abrir: la conversación entera se
volvería ilegible en el siguiente repintado. Y no solo lo entrante — `persistFile` y todos los
`send*` guardan también su propio ciphertext, así que las burbujas **propias** se perderían igual.

Es decir: **PFS obliga a separar la clave de transporte de la clave de reposo.** No es un efecto
colateral que se pueda esquivar; es lo que significa PFS.

### 4.1 Qué guardar en su lugar

Recomendación: **guardar el sobre en claro** (`MessageEnvelope` ya decodificado a bytes), dentro
de la base, que desde el 9 de septiembre está cifrada con SQLCipher y clave envuelta en el
Keystore. Es lo que hace Signal, y aquí encaja porque el trabajo del que depende ya está hecho.

Frente a la alternativa (volver a cifrar cada mensaje con una clave local de dispositivo), tiene
la ventaja de que **quita criptografía del camino de pintado** en vez de añadirla: hoy abrir un
chat deriva y descifra por mensaje y por repintado (de ahí la caché LRU y el `flowOn` que hubo
que meter el 8 de septiembre). Y frente a un atacante con el fichero, las dos opciones dependen
de lo mismo: una clave envuelta en el TEE.

Lo que además **mejora**: hoy quien tenga un `.krbk` con su contraseña y una copia de `krypta.db`
reconstruye la identidad, deriva `S` y abre el historial. Con el historial en claro dentro de la
base cifrada, el `.krbk` deja de ser una llave del pasado.

Migración (v6 → v7): no se puede descifrar dentro de una `Migration` (no hay identidad allí). El
patrón que ya funcionó con `DatabaseEncryption` es el bueno: columna nueva + bandera por fila
(`sealed`), conversión perezosa al leer y una pasada en segundo plano al arrancar; sin big-bang y
sin ventana en la que el historial pueda perderse. Los mensajes anteriores al cambio siguen
siendo descifrables con la clave estática **para siempre** — el ratchet no da PFS retroactivo, y
eso hay que decirlo en la ayuda.

### 4.2 Atomicidad: el fallo que hay que evitar sí o sí

Hoy el orden es: descifrar → persistir → acusar en el buzón. Si el proceso muere entre descifrar
y persistir, no pasa nada: el nodo reentrega y se vuelve a descifrar igual.

Con ratchet, descifrar **muta el estado** y borra `mk`. Si el estado se ha guardado y el mensaje
no, la reentrega ya no se puede abrir: **el mensaje se pierde para siempre**. Es exactamente la
clase de fallo que costó las notas de voz del 5 de julio.

Regla: **el avance del ratchet y la persistencia del mensaje se confirman en la misma transacción
de Room**, y solo después se acusa en el buzón. La `mk` consumida se guarda como clave saltada
hasta que la transacción cierra. Va con test dedicado: matar entre medias y comprobar que la
reentrega sigue siendo legible.

Y el otro lado del mismo problema, ya citado en §1.5: **deduplicar por hash del ciphertext antes
de descifrar**, porque una reentrega legítima es indistinguible de una repetición.

> **Hecho (fase 3, 9 sep 2026).** La garantía no queda como una convención que haya que recordar
> en cada punto de llamada: `RatchetSessions.receive`/`send` **reciben el guardado como lambda** y
> lo ejecutan dentro de `TransactionRunner.inTransaction` junto con el avance del ratchet, así que
> no hay forma de escribir uno sin el otro. El descifrado se queda **fuera** de la transacción a
> propósito (es puro y puede costar un X25519). La deduplicación va en `ratchet_seen`, consultada
> antes de descifrar y escrita dentro de la misma transacción; se poda a las 500 huellas más
> recientes por conversación, que cubre de sobra el cupo de 200 sobres del buzón más una ráfaga de
> trozos. Cubierto por `RatchetSessionsTest`, cuyo runner de mentira **deshace lo escrito** si el
> bloque lanza — sin eso, el test del rollback pasaría por accidente.

### 4.3 `retry` de un mensaje FAILED

Hoy reenvía el ciphertext guardado. Con el sobre en claro, **vuelve a cifrar** con la clave de
mensaje que toque, conservando el id (el receptor deduplica por id). Efecto secundario benigno:
la cadena avanza por un mensaje que quizá nunca llegue, y el receptor se queda con esa clave
saltada hasta que caduque. Está acotado por `MAX_SKIP`.

### 4.4 Lo que enseñó implementarlo (9 sep 2026)

- **La conversión no puede atascarse.** La pasada de fondo va por lotes y **salta con
  desplazamiento** lo que no puede abrir (un contacto que ya no está, una fila corrupta). Sin
  eso, la consulta «dame las que siguen cifradas» devolvería siempre la misma fila ilegible y el
  resto del historial no se convertiría jamás. Lo saltado se queda como está —se sigue leyendo—
  y se reintenta en el arranque siguiente. Tiene test propio.
- **Renombrar, no copiar.** `ALTER TABLE … RENAME COLUMN` mueve el mismo dato sin reescribir el
  fichero, así que no hay ninguna ventana en la que el historial exista a medias. Copiar la tabla
  —lo que hizo la v5→v6— era aquí un riesgo gratuito sobre datos sin copia de seguridad.
- **La lectura tolera las dos formas para siempre**, no solo durante la transición: una fila que
  no se pueda convertir nunca debe desaparecer de la conversación.
- **Verificado en el TECNO sobre la base real**: 47 mensajes convertidos («🗄 historial
  convertido: 47 mensaje(s)» en el diagnóstico), conversación intacta, y el fichero sigue sin
  filtrar nada — `grep` de los nombres y del contenido en `krypta.db` **y en su WAL** da 0, que es
  la comprobación que importa ahora que el texto en claro vive dentro.

---

## 5. Compatibilidad y despliegue

La lección del buzón ciego, tal cual: **primero todos saben recibir, después se enciende el
envío.** Un móvil con la versión actual que reciba un sobre v2 falla el AEAD y lo pinta como
mensaje ilegible.

Pero aquí se puede hacer mejor que con un interruptor global, porque el canal ya es E2EE y el
`MessageEnvelope` tolera tipos desconocidos (`Decoded.Unsupported` → se ignora sin crear
burbuja, comprobado en `onReceived`). Así que:

1. **Anuncio de capacidad en banda**: un tipo de sobre nuevo `V\n<versión>\n<capacidades>`, que
   se envía al añadir un contacto y en el primer contacto tras actualizar. El que lo recibe
   guarda `contact.ratchetPeer = true` (Room, v7). Los clientes actuales lo ignoran limpiamente.
2. **El envío se enciende por contacto**, no por versión: se cifra con ratchet solo con quien lo
   haya anunciado. No hace falta una publicación posterior que cambie una constante, ni esperar a
   que actualice el contacto más rezagado.
3. **La recepción acepta las dos** durante todo el periodo de transición (y el historial v1 para
   siempre).
4. Si el anuncio se pierde, no se pierde nada: se sigue en v1 hasta el siguiente anuncio.

Nada de esto toca el nodo: el ratchet va **dentro** del blob opaco. Es la primera pieza grande de
este proyecto que no necesita desplegar infraestructura.

### 5.1 Lo que enseñó implementarlo (9 sep 2026)

- **El byte de versión es una pista, y hay que tratarlo como tal.** Un ciphertext v1 es
  `nonce(12) ‖ ct+tag` con el nonce aleatorio, así que **uno de cada 256 mensajes v1 de más de
  86 bytes empieza por el byte del ratchet** — fotos, trozos de archivo y cualquier texto de dos
  líneas. No es un caso rebuscado: es el ~0,4% del tráfico. Por eso `onReceived` intenta v2 y
  **cae a v1**, y hay un test que fabrica el disfraz a propósito. (El test se escribió primero
  con un mensaje corto y falló: lo corto nunca se confunde, porque no llega al tamaño mínimo de
  una cabecera. Lo descubrió él solo.)
- **Lo ilegible ya no pinta una burbuja de basura.** Antes, si el descifrado fallaba, se
  persistía el ciphertext como «texto legado» y salía una burbuja con caracteres sueltos. Ahora
  se descarta con una línea de diagnóstico. Esto además es lo que hace segura la caída v2→v1: un
  sobre de ratchet que no se pueda abrir no acaba pintado como basura.
- **El anuncio se marca solo si salió** (directo o buzón), así que un contacto apagado recibe
  como mucho **un** depósito de anuncio, no uno por arranque. Y se registran **los dos
  desenlaces** en el diagnóstico —anunciado y pendiente—: sin la segunda línea, «no aparece
  nada» tanto puede significar «ya estaba dicho» como «falla siempre en silencio», y eso en un
  panel de diagnóstico no vale.
- **El aviso al usuario salió de la transacción.** `persistFile` avisaba por su cuenta y
  `onReceived` avisa ahora al salir con lo que devuelva la rama; dejarlo dentro habría avisado
  dos veces de un archivo y habría alargado el bloqueo de la base con trabajo de notificación.

### 5.2 Lo que enseñó la fase 6 (9 sep 2026)

- **El orden de guardar y enviar no es un detalle.** Al enviar, el estado avanzado se persiste
  **antes** de que los bytes salgan. Si se guardara después y el envío fallara, el siguiente
  mensaje se cifraría desde el mismo estado: misma clave de mensaje y, con ella, **el mismo
  nonce de AES-GCM** — la forma clásica de romper del todo un cifrado autenticado. Tiene test:
  un envío que falla por las dos vías deja el ratchet avanzado igualmente.
- **Los caminos de salida son más de los que parecen.** Además de texto y foto hay: trozos de
  archivo y meta, señales de llamada, acuses de lectura, el propio anuncio de capacidad y el
  reintento de un FALLIDO. Todos pasan por dos funciones (`seal` y `sealAndPersist`), que es lo
  que hace que la puerta por contacto sea una sola decisión y no siete.
- **La sesión se va con el contacto, no con el chat.** Borrar el contacto olvida el ratchet
  (volver a añadirlo arranca un linaje nuevo); **vaciar** el chat no lo toca, porque vaciar no
  es romper la sesión.

---

## 6. Llamadas

Hoy la clave de una llamada es `HKDF(S, callId)`: separa llamadas entre sí pero hereda el problema
de `S`. Cifrar frame a frame con el ratchet **no** es la respuesta (50 frames/s, pérdidas,
desorden y una cadena que reventaría por `MAX_SKIP`).

La respuesta barata y correcta: **que la clave de la llamada sea aleatoria y viaje dentro del
sobre ratcheteado del `invite`** (y del `accept`, para que aporten los dos: `k = HKDF(k_A ‖ k_B)`).
El streaming no cambia ni un byte; la llamada gana PFS porque su clave nace de una `mk` que se
borra. Es un campo nuevo en el sobre `C` y unas líneas en `CallService`.

> **Hecho (fase 7, 9 sep 2026).** Una quinta línea **opcional** en el sobre `C` con la mitad de
> clave en hexadecimal, y `HKDF(k_llamante ‖ k_contestador, salt = secreto_compartido)` como
> clave de la llamada. Tres cosas que enseñó:
>
> - **La línea nueva no se le puede mandar a cualquiera.** El parser anterior parte la cabecera
>   `C` en tres trozos, así que con cinco líneas `toLongOrNull` falla, la señal entera se
>   descarta y **la llamada ni siquiera suena**. Va detrás de la misma puerta que el ratchet
>   (`peerProtocol`), y con el resto se sigue por el camino de siempre: una llamada que suena
>   vale más que una llamada perfecta que el otro no puede recibir.
> - **La mitad del que contesta se sortea al recibir el invite, no al aceptar**, para que la
>   clave esté cerrada antes de que se abra el stream; el primer byte de medios ya va con ella.
> - **La ganancia de hoy es real aunque el envío por ratchet siga apagado**: antes la clave era
>   `HKDF(secreto_estático, callId)`, así que quien robara la identidad abría **cualquier
>   llamada grabada**. Ahora necesita además el sobre de señalización de esa llamada concreta —
>   y cuando se encienda la fase 6, ese sobre tendrá secreto hacia adelante y la llamada lo
>   heredará entero sin tocar el streaming.

### 6.1 Los adjuntos (fase 8)

`krypta_files/` era lo último que quedaba en claro en el dispositivo, y con el ratchet la
incoherencia se veía más: no tiene mucho sentido proteger el **viaje** de una foto y dejar su
**reposo** a la vista. `FileVault` cifra lo que escribe el almacén —trozos y meta del staging,
archivo ensamblado y copia propia del emisor— con AES-256-GCM y una clave de 32 bytes envuelta
por el Keystore, con el mismo «no perder nunca la clave» que la base: si la guardada no se puede
abrir, **falla a la vista** en vez de estrenar otra y dejar los adjuntos anteriores ilegibles.

Lo que enseñó:

- **La lectura tiene que tolerar lo de antes.** Un fichero sin la marca `KFV1` es un adjunto
  anterior y se devuelve tal cual. Verificado en el móvil: el GIF que ya estaba en `sent/` se
  sigue animando, y ni siquiera hace falta la clave para leerlo.
- **`MediaRecorder` solo sabe escribir en claro.** Una nota de voz se graba en la caché y pasa
  al almacén ya cifrada, borrando el temporal. No hay forma de interponerse antes.
- **`MediaPlayer` tampoco abre un fichero cifrado**: la nota de voz se reproduce con un
  `MediaDataSource` sobre los bytes descifrados en memoria (~360 KB por minuto), sin dejar una
  copia en claro en disco — que es justo lo que este trabajo viene a evitar.
- **Abrir con otra app es entregar el archivo en claro**, y eso no lo arregla nada: se deja una
  copia en `cacheDir/krypta_abrir/`, que se limpia al arrancar el proceso (y no al volver a la
  app: si el usuario está viendo un PDF en otro visor, quitarle el fichero de debajo es peor).

---

## 7. Qué no arregla

1. **Los metadatos.** El relay sigue viendo los dos extremos en vivo y el wake sigue delatando
   presencia (§6 de `security-model.md`). El ratchet es contenido, no tráfico.
2. **El rendezvous y las etiquetas del buzón**, que se derivan de `S` y por tanto **siguen siendo
   recalculables para siempre** por quien tenga la identidad. Quien guarde hoy un volcado del
   nodo y robe tu identidad dentro de un año no podrá leer los mensajes, pero sí podrá decir qué
   etiquetas eran tuyas. Cegarlo exige meter el material efímero también en la derivación de las
   etiquetas, y eso rompe la propiedad de que ambos las calculan sin estado compartido. Queda
   fuera.
3. ~~**Los adjuntos en claro** de `krypta_files/`.~~ **Hecho** (fase 8, 9 sep 2026): lo que
   escribe el almacén va cifrado con AES-256-GCM y una clave envuelta por el Keystore. Con dos
   límites que conviene decir: **abrir un adjunto con otra app le entrega una copia en claro**
   (se deja en la caché y se limpia al arrancar el proceso; no hay otra forma de que un visor
   externo lo lea), y **los adjuntos que ya estaban en el móvil no se convierten** — se siguen
   leyendo, pero siguen en claro; reescribir el almacén entero de un usuario para tapar lo que
   ya estuvo a la vista no compensa el riesgo, y vaciar el chat los borra.
4. **El historial anterior al cambio**, que sigue abriéndose con la clave estática.
5. **Un atacante dentro del proceso o con root.** Como siempre.

---

## 8. Preguntas abiertas ~~(hay que decidirlas antes de escribir código)~~ — cerradas el 9 sep 2026

1. ~~**¿La construcción del §1 o el doble ratchet clásico con parches?**~~ **Decidido: el §1**,
   ratchet por épocas. Es la decisión de fondo y la que más caro sale cambiar después; el §2
   explica por qué el clásico no encaja sin servidor de prekeys.
2. ~~**¿El historial pasa a guardarse en claro dentro de la base cifrada?**~~ **Decidido: sí**
   (§4.1). Es el trabajo más voluminoso y toca datos del usuario que no tienen copia de
   seguridad, así que va con el mismo cuidado que la conversión a SQLCipher: conversión perezosa,
   verificar antes de tirar nada, y comprobación sobre la base real del móvil.
3. ~~**¿Anuncio de capacidad por contacto o interruptor global?**~~ **Decidido: anuncio en banda**
   (§5). Evita la publicación de seguimiento que sí necesita `BLIND_DEPOSIT`, a cambio de un tipo
   de sobre nuevo y un campo en `contacts`.
4. ~~**¿Entran las llamadas?**~~ **Decidido: sí** (§6, fase 7).
5. ~~**¿Y los adjuntos en claro de `krypta_files/`?**~~ **Decidido: sí, dentro de este trabajo**
   (fase 9). Era el punto 3 del §7 «qué no arregla»; deja de estarlo. Sin ello, el ratchet
   protegería el viaje de una foto y no su reposo, que es donde de verdad se la llevan.
6. **Revisión externa antes de encender el envío**: no se busca por ahora. El protocolo es propio
   y esto queda anotado como riesgo asumido; si aparece la ocasión, el punto natural para pararse
   es el final de la fase 5.

---

## 9. Plan por fases

| Fase | Qué | Dónde | Esfuerzo |
|---|---|---|---|
| 0 | Decidir el §8 | — | conversación |
| 1 | ✅ `Ratchet` + `RatchetState` + 17 tests (ida y vuelta, desorden, épocas, `MAX_SKIP`, reinicio de linaje, los dos hablan a la vez, linajes distintos, archivo troceado cruzando época, duplicado, cabecera forjada, serialización) | `:p2p-signaling` | hecho |
| 2 | ✅ `RatchetKeyPair`/`RatchetAgree` en Go + AAR `0.0.19`; `BridgeCurve25519` y `JdkCurve25519` para tests | `:native-bridge` | hecho |
| 3 | ✅ Estado en Room **v7** (`ratchet_sessions`, `ratchet_seen`, `MIGRATION_6_7` probada en el TECNO) + `RatchetSessions`, que impone la **transacción atómica** recibiendo el guardado como lambda | `:data`, `:p2p-signaling` | hecho |
| 4 | ✅ Historial en claro: `messages.ciphertext` → `payload` + bandera `encrypted` por fila (`MIGRATION_7_8`), lectura tolerante y `ChatService.unsealHistory` en segundo plano | `:data`, `:core`, `:app` | hecho |
| 5 | ✅ Recepción v2 y v1 conviviendo (`onReceived` → `openRatchet`/`openLegacy`), sobre `V` + `contacts.peerProtocol`/`announcedProtocol` (Room **v9**), anuncio una vez por contacto y versión desde el ciclo WAN | `:p2p-signaling`, `:data` | hecho |
| 6 | ✅ Envío por contacto (`usesRatchet` = lo que el contacto haya anunciado), `seal`/`sealAndPersist` en todos los caminos de salida, la sesión se olvida al borrar el contacto. **Encendido pendiente de la prueba de dos móviles** (§10 y PRUEBAS-PENDIENTES §16) | `:p2p-signaling` | hecho |
| 7 | ✅ Clave de llamada **negociada** dentro del sobre `C` (mitad cada lado, `HKDF(k_llamante ‖ k_contestador)`), solo hacia quien la entiende; el streaming no cambia | `CallService` | hecho |
| 8 | ✅ Adjuntos cifrados en reposo (`FileVault`: AES-256-GCM, clave envuelta en el Keystore; el almacén cifra trozos, meta, ensamblado y copia propia, y la UI los lee por `FileStore.read`) | `:app`, `:core` | hecho |
| 9 | ✅ Docs: `security-model.md` (§7, §9 y §10), política de privacidad §9, ayuda in-app, `architecture.md`, `CLAUDE.md`, `PRUEBAS-PENDIENTES` §16 | `docs/` | hecho |

Las fases 1 y 2 no cambian nada observable y se pueden hacer y probar sin riesgo. La 4 es la que
toca datos sin copia de seguridad y merece el mismo cuidado (y la misma verificación en el móvil
real) que la conversión a SQLCipher.

---

## 10. Recomendación

Hacerlo, en el orden del §9. El §8 quedó cerrado antes de la fase 1.

> **Cómo acabó la condición del encendido (10 sep 2026).** Este documento pedía no encender el
> envío sin probar antes en dos móviles reales la pérdida de estado, la reentrega del buzón y un
> archivo grande cruzando un cambio de época. Se encendió sin ella, a decisión del autor, porque
> la disponibilidad del segundo móvil tenía el trabajo parado. Queda dicho aquí para que no se
> lea como un olvido: **la prueba sigue debiéndose**, y el riesgo que corre mientras tanto es que
> un mensaje enviado con ratchet que el otro extremo no pueda abrir **se pierde** (se descarta y
> se acusa). Lo acota que solo afecta a parejas donde **ambos** tengan este build, y que
> `RATCHET_SEND = false` devuelve todo a v1 en la siguiente publicación.

Y una nota de honestidad para la documentación de cara al usuario, como en el buzón ciego: con
esto Krypta **sí** podrá decir que robar la identidad no abre el pasado. Lo que seguirá sin poder
decir es que el nodo no sabe con quién hablas.

> **Cómo quedó la documentación (10 sep 2026).** Mientras `RATCHET_SEND` siga en `false`, los
> textos de cara al usuario **no dicen** que haya secreto hacia adelante: la ayuda in-app y la
> §9 de la política siguen advirtiendo de que la clave no cambia con el tiempo y de que quien
> saque la identidad puede descifrar el historial guardado. Prometerlo antes de encenderlo
> sería exactamente el tipo de cosa que este proyecto lleva un año evitando. Lo que sí se
> añadió es lo que **ya** es cierto: que la base y los adjuntos están cifrados en reposo con
> claves del almacén seguro, que abrir un adjunto con otra app le entrega una copia en claro, y
> que cada llamada usa una clave propia sorteada para ella.
