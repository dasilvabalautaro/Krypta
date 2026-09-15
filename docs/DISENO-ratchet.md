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

**Coste con el reloj** (W-6; fijado el 15 sep 2026 en `RatchetTest`). La regla da por hecho que el
linaje nuevo es mayor que el vigente, porque es «la hora actual». No lo es si quien pierde el estado
tiene el reloj por detrás del linaje vigente de la pareja: por ejemplo, al restaurar con la fecha mal
puesta, o si el linaje vigente lo creó un móvil con el reloj adelantado. Entonces:

- lo que escribe **llega**, por la época 0 derivable, pero el otro no adopta un linaje menor, así que
  **no sale nunca de la época 0**;
- lo que le escribe el otro, en una época > 0 del linaje vigente, **no se puede abrir y se pierde**;
- y **corregir el reloj no lo arregla**, porque el linaje se fija al crear la sesión. Lo arregla que
  el otro arranque un linaje nuevo (borrar y volver a añadir el contacto).

Repetir claves, en cambio, exigiría crear un linaje en el mismo milisegundo que uno anterior.

Un **linaje monótono duradero** (guardar el último emitido y no bajar nunca de él) no se hace
ahora, por tres motivos. Toca la regla del linaje, congelada hasta la revisión externa. No cubriría
la importación de un `.krbk` en un móvil nuevo, salvo que el último linaje viajara en el respaldo. Y
el rediseño de H-4 puede absorberlo.

### 1.7 Formato de la cabecera

Va **en claro** (el AEAD la autentica como AAD, así que no se puede tocar) delante del ciphertext:

```
0        0x02          versión del sobre de transporte (v1 era: nonce(12) ‖ ct+tag)
1        flags         bit 0 = el texto en claro va relleno por tramos (§1.10)
                       el resto, reservado; un receptor ignora los que no conoce
2..9     lineage       u64 BE, unix millis
10..13   epoch         u32 BE
14..17   N             u32 BE, nº de mensaje en la cadena
18..21   PN            u32 BE, mensajes de la cadena de la época anterior
22..53   cur_pub       X25519 (32), mi pública de ESTA época
54..85   next_pub      X25519 (32), mi propuesta para epoch+1
86..     ct ‖ tag      AES-256-GCM con AAD = bytes 0..85
```

**Corrección respecto al diseño sobre el papel** (que decía 70 bytes y ponía `next_pub` en
22..53): la cabecera son **86 bytes**, porque hace falta mandar *además* la pública de la época
actual — sin ella, un lado que no hubiera recibido ningún mensaje de la época anterior no podría
alcanzar la del otro. Con el tag de GCM son **102 bytes** de sobrecoste por mensaje frente a los
28 de la v1. Irrelevante para un trozo de 48 KiB; para un acuse de lectura, que eran ~60 bytes,
lo triplica — y sigue siendo irrelevante, porque desde §1.10 ese acuse se rellena hasta 160
bytes de todas formas.

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
4. **Al añadirse dos contactos, si el primer mensaje del usuario tiene secreto hacia adelante
   depende de los relojes, y no se puede arreglar en el ratchet sin romper otra cosa** (12 sep
   2026). Cada lado crea su sesión por su cuenta con su hora local como linaje. En el caso
   normal, quien recibe el anuncio del otro crea la suya **después**, con linaje mayor: abre el
   sobre por `openOld` (la época 0 de cualquier linaje es derivable) pero no adopta el linaje
   menor ni consume la propuesta que venía, así que lo primero que escriba va en la época 0 de
   su propio linaje, y solo la primera respuesta del otro saca a los dos. Si en cambio su reloj
   va por detrás, el linaje del otro es mayor, lo adopta, consume la propuesta y su primer
   mensaje ya sale en la época 1. Se creía que el intercambio de anuncios de capacidad (§5)
   hacía esa ida y vuelta solo; dos tests de `ChatService` fijan los dos casos (el primer
   intento del test **pasaba o fallaba según el milisegundo**, que fue la pista). Se probó a que una sesión **que aún no ha cifrado nada**
   adoptara el linaje menor, y `RatchetPropertyTest` lo tumbó en su primera corrida (semilla
   102): tras una reinstalación la sesión también está «virgen», y adoptar un linaje viejo del
   otro reutiliza ternas `(linaje, época, N)` que esta identidad ya gastó antes de perder el
   estado. **Los linajes tienen que ser monótonos por identidad**, y como el estado perdido no
   puede decir qué linajes usó, la regla «un linaje menor no se adopta nunca» no admite
   excepciones. Lo que sí sería sano, si algún día se quiere cerrar el hueco: que el receptor,
   al abrir por `openOld` un sobre de linaje menor sin tener sesión, **conteste** con un sobre
   de control en su linaje (mayor), para que el otro lo adopte y responda con material
   efímero antes de que el usuario escriba — dos sobres de 160 B, sin cambio de formato. No se
   ha hecho: es más código en el camino sensible antes de la revisión externa.

---

### 1.9 Lo que encontró la prueba de propiedades (10 sep 2026)

`RatchetPropertyTest` sortea secuencias de envíos, entregas desordenadas, pérdidas, duplicados y
pérdidas de estado con semillas fijas, y comprueba que ciertas cosas nunca pasan. En su primera
corrida encontró algo que no estaba escrito:

**El ratchet no detecta la reproducción de un mensaje de la época 0.** Cuando el receptor ya ha
dejado atrás esa época y su cadena ha salido de las retiradas (`MAX_PAST_CHAINS` = 3), `openOld`
la **re-deriva del secreto compartido** —que es justo lo que hace que la época 0 no tenga secreto
hacia adelante— y con ella vuelve a abrir el mensaje. Dentro de la cadena viva no pasa (la clave
está gastada), y en una época retirada distinta de la 0 tampoco (su cadena se fue).

Consecuencia, y es la parte que importa: **la deduplicación previa no es una comodidad, es una
pieza de seguridad**. Y tiene ventana finita: `RoomRatchetStore` conserva las **500 huellas más
recientes por conversación** (`SEEN_PER_CONVERSATION`). O sea que reproducir un sobre de época 0
más viejo que esas 500 lo volvería a entregar, y el usuario vería un mensaje repetido. No es una
falsificación —hace falta un sobre genuino, capturado del buzón o de la red— pero sí un mensaje
que aparece dos veces, y con la marca de tiempo del original.

**La ventana se arregló el mismo día.** La poda pasa a ser la **unión** de dos reglas: se
conserva una huella si es más nueva que **8 días** (margen sobre el TTL de 7 del buzón, que es lo
que de verdad acota la reentrega legítima) **o** si está entre las **500 últimas**. Antes solo
había la de cantidad, y se comportaba al revés de lo que hace falta: en una pareja muy activa 500
mensajes pueden ser medio día, así que dejaba de proteger justo a quien más habla; en una
tranquila podían ser meses. Con la unión, ningún caso empeora. Va en `RatchetDao.pruneSeen`
—borra solo lo que es **a la vez** viejo y sobrante— y no cambia el esquema: la tabla ya tenía
`seenAt` con su índice.

**Y un hallazgo de producción que salió al probarlo (11 sep 2026): podar en cada mensaje era
demasiado caro.** `markSeen` se llama una vez por mensaje recibido, dentro de la transacción, y
la poda lleva un `ORDER BY seenAt DESC LIMIT 500` dentro. Una ráfaga de 600 —un archivo
troceado— **mató el proceso** en el TECNO. Eso no era una molestia del test: lo pagaba el usuario
en cada mensaje. Ahora se poda **una de cada 64 inserciones** (`RoomRatchetStore.PRUNE_EVERY`),
que no cambia lo que se conserva —la ventana sigue siendo "8 días o 500 últimas" y la tabla nunca
crece más de 64 filas por encima del tope— y convierte esa ráfaga en 9 podas en vez de 600.

Cubierto por `RatchetSeenPruneSqlTest`, que ejecuta **la misma cadena** que va en el `@Query`
(`RatchetDao.PRUNE_SEEN_SQL`) contra SQLite en la JVM, en 0,6 s. Empezó siendo un test
instrumentado y verificar cuatro líneas de SQL costó dos corridas y horas de reloj: la primera
murió porque el móvil se desconectó del USB —Gradle esperó 1h 21m y reportó `FAILED` **sin
mensaje**, con el motivo real (`device not found`) escondido en `system-err`— y la segunda mató
el proceso en el dispositivo. La lección, más allá de este caso: **si la lógica es SQL, se prueba
donde se pueda repetir**.

Queda sin hacer la otra salida posible: rechazar de plano los sobres de época 0 cuyo linaje ya no
es el vigente y que llegan cuando la sesión lleva épocas avanzadas.

**Y una asimetría de la regla del linaje que tampoco estaba escrita.** Cuando un extremo pierde
el estado, su linaje nuevo es mayor, y el §1.6 dice que **un linaje menor se descarta**. O sea
que quien no se ha enterado sigue escribiendo en el linaje viejo y **sus mensajes se pierden**
—se descartan al no poder abrirse y, si venían del buzón, se acusan igual— hasta que el que
reinstaló **escribe algo**. Solo entonces el otro adopta el linaje nuevo y la conversación
vuelve. No es un bloqueo permanente (eso es lo que promete el §1.6 y se cumple), pero es una
**ventana de pérdida silenciosa** que dura hasta el primer mensaje del que reinstaló.

**Arreglado el mismo día (`ChatService.rehook`).** El receptor que falla al abrir un sobre con
cabecera de ratchet **sabe** que el otro va atrasado, así que no hay que esperar a que nadie
escriba: le manda el anuncio de capacidades (`V`), que ya viaja por el ratchet con su linaje, y
con eso el otro extremo lo adopta y vuelve a ser legible. Tres decisiones que importan:

- **Se reutiliza el sobre `V`** en vez de inventar uno: un cliente anterior ya lo ignora
  limpiamente como `Unsupported`.
- **Va lanzado**, no en línea: el camino del buzón es síncrono —el acuse depende de que
  `onReceived` vuelva— y bloquearlo con una llamada de red retrasaría la entrega.
- **Un reengache por contacto cada 5 minutos**, y solo hacia contactos que ya hablan v2.
  Cualquiera de tus contactos podría mandar basura a propósito; sin tope, eso nos haría emitir
  un mensaje por cada una.

Lo que **no** arregla: el mensaje que provocó el fallo ya está perdido. El reengache salva los
siguientes, así que la ventana pasa de "hasta que la otra persona escriba" a "un mensaje".
Queda sin hacer la otra salida posible: que el emisor cuya racha de mensajes no se abre nunca
vuelva por su cuenta a la época 0.

Las otras invariantes que la prueba fija y que sí se cumplieron: nada se abre como otro mensaje
(ni entre sentidos, ni entre épocas, ni entre linajes), ninguna terna `(linaje, época, N)` se
repite en un mismo emisor —la forma observable de que ninguna clave ni nonce se reutiliza—, las
dos épocas nunca se separan más de una, y tras el caos la conversación se recupera **en una
ronda**: si al que quedó atrasado no se le lee, basta con que hable el otro.

---

### 1.10 Relleno por tramos (11 sep 2026)

Fase 2.3 de [PLAN-privacidad-y-confianza.md](PLAN-privacidad-y-confianza.md), implementada en
`Padding` + el bit 0 de los flags de la cabecera.

**Qué arreglaba.** El nodo nunca ve el contenido, pero sí **cuántos bytes** tiene cada depósito.
Con eso distinguía un acuse de lectura (~60 B) de un anuncio de capacidad (~4 B) de un «vale» —
es decir, **la estructura de la conversación**: quién leyó qué y cuándo, legible sin romper nada
y sin necesidad de descifrar. Cuantizar el tamaño borra esa diferencia: todos esos mensajes pasan
a medir 160 bytes.

**Los tramos.** 160 B hasta 4 KiB, 1 KiB hasta 64 KiB, y por encima nada. Los 160 son los de
Signal y valen por lo mismo: es el grano donde vive casi todo el tráfico de control. El de 1 KiB
cubre la foto en línea (≤58 KiB) y el trozo de archivo (48 KiB) con menos del 2 % de sobrecoste.
Por encima de 64 KiB **no se rellena**, y no es pereza: nada legítimo pasa de ahí —el buzón
rechaza blobs mayores— y lo único que puede llegar tan grande es un texto enorme por envío
directo, donde el receptor corta en 1 MiB; rellenar ahí arriesgaría cruzar ese tope para no
esconder nada, porque un mensaje de ese tamaño ya se delata solo.

**Tres decisiones de sitio**, que son lo que hace que esto sea barato:

1. **Dentro del ratchet, no dentro del sobre.** Así una sola decisión cubre *todos* los tipos
   —acuse de lectura, hello, señal de llamada, meta y trozos de archivo, texto, foto— en vez de
   una por camino de envío. Los siete caminos de envío ya pasaban por `seal`/`sealAndPersist`
   (fase 6), así que no hubo que tocar ninguno.
2. **El bit va en la cabecera**, que ya era el AAD. Hay que saber si el texto va relleno
   **antes** de interpretar lo que sale del AEAD, y ponerlo ahí lo deja autenticado sin gastar
   un byte más: encenderlo por el camino haría que se comiera el final del mensaje, apagarlo que
   se entregara el relleno como contenido, y **las dos cosas rompen el AEAD**. Los bits que una
   versión no conoce se ignoran, para que otra futura pueda usarlos.
3. **`texto ‖ 0x80 ‖ 0x00…`**, el esquema de Signal, y por su misma razón: no necesita un campo
   de longitud explícito, que sería un dato más a la vista. Se recupera buscando el último byte
   no nulo, que tiene que ser el terminador. Funciona con contenido que **acabe en ceros** (los
   suyos quedan antes del `0x80`) y con uno que acabe **en el propio `0x80`**; los dos casos
   están fijados en `PaddingTest`.

**La trampa de la versión, que es lo que más cerca estuvo de costar un fallo de seguridad.**
Rellenar exige que el otro sepa quitarlo, así que hay versión nueva: `PROTOCOL_VERSION = 3`. Pero
`usesRatchet` y la clave de llamada negociada comparaban `peerProtocol >= PROTOCOL_VERSION`, de
modo que subir la constante **habría apagado el ratchet y la negociación de clave con todos los
contactos que anunciaron 2** — una regresión de seguridad por añadir una función de privacidad, y
silenciosa, porque el camino v1 funciona. De ahí que ahora haya un mínimo **por capacidad**
(`RATCHET_MIN_PROTOCOL = 2`, `PADDING_MIN_PROTOCOL = 3`) y que `PROTOCOL_VERSION` sea solo *lo que
se anuncia*. **Regla: una versión nueva no es el umbral de nada; cada capacidad tiene el suyo.**

**Qué no arregla**, y hay que decirlo igual de claro: un **archivo troceado sigue siendo
reconocible** (48 KiB rellenados a un tramo de 1 KiB siguen siendo 48 KiB, y una ráfaga sigue
pareciendo un archivo) y una foto sigue distinguiéndose de un texto. Para eso hace falta tráfico
de relleno y batching, que es otra cosa y no está hecha.

Cubierto por `PaddingTest` (los bordes de tramo, el contenido que se parece al relleno, el
sobrecoste acotado y que un trozo relleno **siga cabiendo** en el blob de 64 KiB del buzón) y
`RatchetPaddingTest` (el bit atravesando los **dos** caminos de descifrado —el normal y el de una
época ya retirada, que es código aparte—, relleno y sin relleno mezclados en la misma sesión, y
que tocar el bit rompa la autenticación). Además `RatchetPropertyTest` ahora **sortea el relleno
por mensaje**, así que el caos lo cubre donde de verdad puede romperse: entregas desordenadas,
duplicados, épocas retiradas y pérdidas de estado.

### 1.11 Lo que encontró revisar el protocolo (14 sep 2026)

Una revisión interna del código contra este documento encontró siete cosas. El detalle, las
reproducciones y el plan de revisión externa están en
[REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md); la especificación normativa
que se escribió para ella, en [ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md). Lo que
toca a este diseño:

- **Faltaba exclusión por conversación (H-0, crítica).** El §4.2 garantizaba que el avance del
  ratchet y la persistencia se confirmaran juntos, pero no que dos operaciones no se cruzaran.
  Cargar el estado suspende, así que dos envíos simultáneos salían con **la misma terna
  `(L, e, N)`**: la misma clave de mensaje y el mismo nonce de AES-GCM. Una recepción cruzada con
  un envío devolvía el contador hacia atrás. `RatchetPropertyTest` es secuencial y no podía verlo.
  `RatchetSessions` lleva ahora un `Mutex` por conversación.
- **La negociación del §5 perdía la pista y no la recuperaba (H-1, alta).** El anuncio sale una
  vez por versión, y `peerProtocol` se va con el contacto (tampoco viaja en el `.krbk`). Tras
  borrar y volver a añadir un contacto, o importar un respaldo, quien había perdido la sesión creía
  que el otro hablaba v1. Su reengache (§1.9) no salía **por eso mismo**, y **todo lo que el otro
  escribía por ratchet se perdía para siempre**: el punto 5 de PRUEBAS-PENDIENTES §16 habría fallado
  en el móvil. Ahora:
  - el `V` lleva la versión que tengo apuntada del otro;
  - a quien nos tiene atrasados se le repite el anuncio por la clave estática;
  - al saber que alguien habla ratchet se le manda un primer sobre por ratchet, para que adopte
    nuestro linaje.
- **`peerProtocol` podía bajar (H-2 y H-3)**, por dos vías:
  - por **copias viejas del contacto** (verificar, bloquear, renombrar, importar);
  - por **un `V` forjado**. Esta dejaba a quien tiene `S` degradar la pareja con un solo depósito y
    **leer en pasivo**, contra lo que promete la tabla del §0 («una vez que ambos giran claves, el
    atacante pasivo se queda fuera»).

  Ahora la versión no baja nunca (el SQL lo impone en una sola sentencia), y un sobre v2 que abre
  la vuelve a subir.
- **La regla del linaje del §1.6 permite secuestrar la sesión a quien tiene `S`, sin vuelta atrás
  (H-4).** Un sobre de época 0 con un linaje forjado muy alto se adopta, se avanza con la propuesta
  del atacante y el extremo legítimo queda fuera. El §1.6 lo daba por asumido («quien tiene `S` ya
  tiene la identidad»). Lo que no decía es que convierte **un** acto activo en lectura pasiva, y
  que no se recupera. **No se arregla aquí, a propósito**: es la regla más frágil del ratchet, y la
  decisión de no tocarla antes de la revisión externa sigue en pie. Lo fija `RatchetTest` y va como
  pregunta a la revisión.

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
6. **Un adversario cuántico futuro.** Y conviene entender por qué el ratchet no ayuda **nada**
   aquí: todo su material es X25519 —`S`, la época 0 y el DH de cada época—, y la raíz encadena,
   así que quien rompa la curva lo abre todo de principio a fin. Como el **PeerID *es* la clave
   pública**, no hace falta robar nada: basta con haber **grabado el tráfico**. El ratchet
   protege del robo de la identidad *hoy*; esto es otra amenaza y pide otra cosa. Diseñada y
   medida el 11 sep 2026 en [DISENO-postcuantico.md](DISENO-postcuantico.md), a propósito **sin
   implementar** hasta que haya revisión externa.

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
6. ~~**Revisión externa antes de encender el envío**: no se busca por ahora. El protocolo es propio
   y esto queda anotado como riesgo asumido; si aparece la ocasión, el punto natural para pararse
   es el final de la fase 5.~~ **Revertido el 14 sep 2026: se busca.**
   - El envío ya estaba encendido, así que el «antes» ya no se puede cumplir. Lo que se decide es
     **no añadir más protocolo** (cambios de linaje, post-cuántico, rotación) hasta tener la
     revisión.
   - Lo que lo decidió: una revisión interna ese mismo día encontró un fallo **crítico** (H-0) y
     uno de **pérdida permanente de mensajes** (H-1) en un protocolo que ya tenía pruebas de
     propiedades y fuzzing.
   - Ver §1.11, y el plan en [REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §5.

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
