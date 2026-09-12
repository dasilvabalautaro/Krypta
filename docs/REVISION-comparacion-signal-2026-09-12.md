# Revisión: segunda comparación con Signal

**Fecha:** 12 de septiembre de 2026.
**Qué se revisa:** `comparacion2-signal.pdf` (8 páginas, generado desde la bóveda del autor), una
comparación de diseño y modelo de amenaza entre Krypta y Signal.
**Cómo:** cada afirmación sobre Krypta se contrastó con el código y los docs en `3abeada`. Las de
Signal no tienen fuente en el repo: se contrastan con conocimiento general de su arquitectura
pública (corte de mayo de 2026) y se marcan **[verificar]** donde el detalle importa.
**Por qué existe este documento:** igual que con la primera comparación, el PDF no se versiona (es
circunstancial). Se guardan las conclusiones: qué está mal, qué falta y qué cambia en
[PLAN-privacidad-y-confianza.md](PLAN-privacidad-y-confianza.md).

---

## 0. Veredicto

La comparación acierta en la forma: pone el peso en los metadatos, en el protocolo propio sin revisar
y en la transparencia, que es donde está. Pero se equivoca en los dos sentidos, y los errores no se
compensan:

- **Le concede a Krypta cosas que hoy no tiene**: que no haya un punto central que coaccionar, que no
  haya nada que pedir judicialmente, que degrade a P2P directo si caen los nodos y que tenga F-Droid
  propio. Son propiedades de la **arquitectura**, no del **despliegue** actual.
- **Se queda corta con Krypta** en la madurez del ratchet y en la entrega en segundo plano, porque
  hereda frases de nuestros propios docs que se quedaron viejas.
- **Tiene a Signal desactualizado en tres puntos**, y los tres ensanchan la distancia: el post-cuántico
  en el ratchet (SPQR), las copias de seguridad en la nube y una supuesta recompensa por fallos.

Balance corregido: **en metadatos, hoy Krypta está peor que Signal, no empatado**. Es lo que ya dice
el §0 del plan y lo que el PDF contradice. Y la conclusión con más consecuencias no es criptográfica:
**el depósito ciego se puede encender ya, contacto a contacto, sin esperar a otra publicación** (§4.1).

---

## 1. Errores sobre Krypta

| # | El PDF dice | Lo que hay | Fuente |
|---|---|---|---|
| K1 | «No hay un punto central que comprometer o coaccionar para correlación a escala» (§1 y Resumen, punto 3) | Los dos nodos de `DEFAULT_BOOTSTRAP` los opera una persona, y entre los dos ven: el **grafo diario de parejas** por la DHT (los dos lados anuncian la misma clave y los móviles son clientes), quién habla con quién por el relay, la presencia por el wake y, en disco, `from`/`to`/`ts` del buzón. Eso es un punto central de metadatos. Que *cualquiera pueda* montar su nodo es una propiedad de la arquitectura que hoy nadie usa | security-model §5, §6.1–6.4 |
| K2 | «Vía judicial: no hay cuentas → nada que pedir al proyecto» | Con `BLIND_DEPOSIT = false` (`ChatService.kt:1582`), el disco del VPS guarda **remitente, destinatario y hora de cada depósito durante hasta 7 días**. Una copia del disco pedida al proveedor entrega eso. Además, el journal de São Paulo conserva 12 líneas con PeerIDs hasta ~10 oct y el `node.log` del Mac está sin purgar. Lo que sí es cierto: no hay números de teléfono ni directorio | security-model §6.1, §6.5 |
| K3 | Si caen los nodos, «degrada a P2P directo (DCUtR) y a LAN» | DCUtR necesita el relay para coordinar el hole punching, la DHT solo vive en los nodos (los móviles son clientes) y el mDNS va **apagado de serie** desde el 10 sep. Sin nodos queda lo que ya estaba conectado, algún contacto con dirección directamente alcanzable y la LAN si el usuario la activa. Entre dos móviles con datos móviles, en la práctica, casi nada | security-model §9.5; CLAUDE.md (mDNS opt-in) |
| K4 | «17 tests unitarios, cero prueba en dos móviles» | Hay **43 tests JVM específicos** (ratchet 17, sesiones 5, propiedades 3 sobre muchas semillas, relleno 8 + 6, poda de la deduplicación 4), más la integración en `ChatServiceTest`; 246 tests JVM en total. Y **la conversación normal con dos móviles pasó el 10 sep**. Falta lo que de verdad podía romper: reentrega del buzón, archivo grande cruzando época, pérdida de estado / `.krbk` y llamada | PRUEBAS-PENDIENTES §16 |
| K5 | «Existe una vía permanente de degradar a época 0» | Exagerado. Una cabecera forjada no mueve el estado (funciones puras + AEAD), y forjar un linaje exige `S`. Reproducir un sobre genuino de linaje alto degrada como mucho una ronda, y la deduplicación lo corta. Lo que sí es permanente: la época 0 de cada linaje **no tiene PFS** porque se re-deriva, y un sobre de época 0 reproducido fuera de la ventana (más de 8 días **y** fuera de los últimos 500) se vuelve a mostrar repetido. Lo que venga después no pierde confidencialidad | DISENO-ratchet §1.6, §1.8.3, §1.9; security-model §9.9 |
| K6 | «Repo privado, F-Droid propio» | Era privado al revisar (la API pública de GitHub respondía 404); **es público desde el mismo 12 sep 2026**. **F-Droid no existe**: figura como objetivo «a medio plazo» | security-model §10, punto 3 |
| K7 | «Entrega en 2.º plano rota en el móvil del propio autor» | Funcionó el 10 sep, en una muestra, con el build de nodos TCP directos. El fallo venía de que HiOS congela el proceso. Lo exacto es «sin demostrar de forma estable», porque falta la medición de no regresión | PRUEBAS-PENDIENTES §13 |
| K8 | «Revisión externa descartada a propósito» | Así figuraba en DISENO-ratchet §8.6, pero el plan (§3.1, 10 sep) ya dice que **conviene revertirlo**. El estado real es «todavía no buscada» | PLAN §3.1 |

**De dónde salen K4 y parte de K1.** Varias frases del PDF copian fielmente nuestros docs, que se
contradicen entre sí:

- `security-model.md` §4 y §10 siguen diciendo que el ratchet «no se ha verificado todavía entre dos
  móviles reales». PRUEBAS-PENDIENTES §16 lo desmiente para la conversación normal.
- `security-model.md` §6.1 dice que el depósito ciego «no está hecho», mientras que §10 lo da por
  implementado con el envío apagado.
- `security-model.md` §9.7 sigue describiendo la fuga de IP por hole punching «antes de que la app
  pueda descartar a un desconocido», y §5.1 la da por cerrada con el `ConnectionGater`.
- `security-model.md` §8 habla de «los tres nodos», y desde el 10 sep son dos.

Una comparación generada desde los docs no puede ser mejor que ellos. **Corregidas el 12 sep 2026**,
junto con otras dos que salieron al releer (§4.5).

---

## 2. Signal, desactualizado

| # | El PDF dice | Lo que hay | Efecto en la comparación |
|---|---|---|---|
| S1 | El post-cuántico de Signal «cubre justo el primer intercambio» (PQXDH) | Desde octubre de 2025 Signal tiene además **SPQR** (*Sparse Post-Quantum Ratchet*), con ML-KEM-768 dentro del ratchet (el «triple ratchet»). Nuestro PLAN §3.2 y DISENO-postcuantico ya lo citaban | La distancia es **mayor** de lo que dice el PDF: aunque se implementara el diseño PQ de Krypta, Signal seguiría cubriendo el inicio y el ratchet, y Krypta solo el ratchet |
| S2 | Signal tiene «bug bounty» | Signal no tiene programa de recompensas, solo un correo de seguridad; el PLAN §4.2 ya lo había corregido **[verificar]** | A Krypta le faltaba la **vía de contacto**, no el dinero. **Hecho el 12 sep 2026**: `SECURITY.md` y `security.txt` servido en los dos VPS |
| S3 | Copia de seguridad de Signal: «opcional, frase de 30 dígitos» | Esa es la copia **local** antigua. Desde septiembre de 2025 Signal tiene **copias seguras en la nube** (E2EE, clave de recuperación de 64 caracteres) que restauran el **historial** **[verificar alcance y plataformas]** | Cambia las filas de «Recuperación» (§2 del PDF) y «Copia de seguridad» (§5 del PDF): ver §3.1 |
| S4 | «Un desconocido con tu número no puede sacarte la IP» | Frente a desconocidos, correcto. Pero en Signal las llamadas **entre contactos** van P2P por defecto y exponen la IP, salvo que se active «retransmitir siempre las llamadas» **[verificar]** | Iguala el punto «tus contactos ven tu IP» (PLAN §2.4, modo solo relay): pasa en las dos apps |

---

## 3. Lo que la comparación no mira

### 3.1 El historial no tiene copia de ninguna clase

El `.krbk` guarda **identidad y contactos**, no mensajes. Perder o romper el móvil es perder todas las
conversaciones, sin remedio. Con S3, Signal sí recupera el historial. El PDF presenta «Recuperación:
solo el `.krbk` exportado a mano» como una versión reducida de lo mismo, y no lo es: **no recupera lo
que un usuario entiende por «mis chats»**.

La política de privacidad lo dice con claridad («guarda tu identidad y tu lista de contactos, *no* los
mensajes»). **La ayuda de la app no**: a «¿Puedo recuperar mi cuenta si pierdo o cambio de teléfono?»
responde «solo si hiciste una copia de seguridad», que se lee fácilmente como «recupero mis chats»
(`HelpContent.kt:86`).

No está claro que haya que construir una copia del historial: sería más superficie (un fichero que,
con la frase-clave, lo abre todo), y el ratchet no lo cambia, porque el historial en reposo ya va en
claro dentro de SQLCipher. Pero eso es una **decisión** que hoy nadie ha tomado ni escrito.

### 3.2 El APK que se reparte es de depuración

La rutina de despliegue reparte `krypta-arm64-debug.apk`. El PDF dice que «las builds de depuración
debilitan de verdad (`run-as`)», pero no saca la consecuencia: **esa es la build que usan los
probadores**. Con la depuración USB activa y el móvil desbloqueado, `run-as` copia el directorio de
datos y un depurador puede engancharse al proceso. SQLCipher y el Keystore hacen que los ficheros
copiados sean ciphertext, pero con el proceso vivo esa protección no sirve (security-model §9.3).

Pasar a builds release (`assembleRelease -PslimAbi`, que ya firma con la clave de producción) tiene
una trampa: cambia la firma, Android obliga a desinstalar y **desinstalar borra la identidad**. Por
eso lo primero es exportar el `.krbk`, igual que antes de los tests instrumentados.

### 3.3 Llamadas

No aparecen en ninguna fila. En Krypta, cuando DCUtR no perfora, el audio y el vídeo pasan por el
relay, así que el operador ve **quién llama a quién, cuándo, cuánto dura y, por la tasa de bits, si es
voz o vídeo**. La clave por llamada se negocia desde el 9 sep, pero solo con contactos v2 o
posterior. En Signal la señalización viaja como mensaje y el medio va P2P o por TURN; cuánto ve su
servidor de cada llamada relayada es un detalle a contrastar **[verificar]**.

### 3.4 Contacto no solicitado

Krypta **no admite mensajes de desconocidos**: el `ConnectionGater` corta las conexiones entrantes de
PeerIDs que no son contactos, y `onReceived` descarta lo que llegue por el buzón. Es una ventaja de
privacidad que el PDF no cuenta: no hay spam ni acoso de desconocidos. Tiene un coste de producto: no
existe la «solicitud de mensaje», así que las dos partes tienen que añadirse. Lo que un desconocido sí
puede hacer es ocupar hasta la mitad de tu buzón (security-model §8).

### 3.5 La otra cara de no depender de Google

El PDF presenta FCM como una fuga de Signal y no cuenta lo que le cuesta a Krypta prescindir de él:
**la entrega en segundo plano depende de que el fabricante no congele el proceso** (PRUEBAS-PENDIENTES
§13, documentado en HiOS). Signal le cede metadatos a Google a cambio de que el mensaje llegue.
Krypta no se los cede, pero en móviles agresivos puede no avisar hasta que abres la app. Es un
intercambio, y el PDF muestra solo uno de sus lados.

### 3.6 Los nodos caseros

El PDF los despacha como «respaldo solo para clientes sin actualizar». Es verdad, y justo por eso
importan: siguen en la lista de bootstrap de cualquier móvil con un build anterior, **ejecutan el
binario viejo** (lectura sin límite en el handler de mensajes y log con PeerIDs) y el `node.log` del
Mac sigue sin purgar. Hoy son el eslabón más débil de la confianza operativa.

---

## 4. Qué cambia en el plan

Lo que el PDF confirma no se repite aquí: el orden de las fases 1–4 del plan sigue siendo el correcto.
Esto es lo que añade esta revisión.

### 4.1 Encender el depósito ciego por contacto, sin otra publicación (fase 2.2)

El plan pone como condición «que la versión que sabe recibir esté repartida», y el código la aplica
con una constante global. Pero el historial de git aporta el dato que desbloquea esto:

- la recepción a ciegas entró el **9 sep a las 10:48** (`63222d1`);
- el anuncio de capacidad `V` entró el **10 sep a las 03:18** (`afb576a`).

O sea que **todo contacto que haya anunciado `peerProtocol >= 2` ya sabe retirar por etiquetas**. El
depósito ciego puede seguir el mismo patrón que el ratchet y el relleno: `outboxLabel` devuelve la
etiqueta si `contact.peerProtocol >= BLIND_MIN_PROTOCOL` (= 2) y cadena vacía si no. Así cumple la
regla que dejó la v3 (**cada capacidad tiene su propio mínimo**), no hay que coordinar publicaciones y
se cierra K2 para el disco del nodo, que es la ganancia sólida según DISENO-buzon-ciego §0.

Antes de hacerlo: comprobar que los dos VPS sirven `/krypta/mbx/put/2.0.0` (si no, el cliente cae a v1
**en silencio**), un test en `ChatServiceTest` con un contacto v1 y otro v2, y verificar en el nodo
que el depósito para el contacto v2 aparece bajo una etiqueta. El límite no cambia: el relay y el wake
siguen viendo el grafo en vivo, y la DHT, el diario.

**Hecho el 12 sep 2026.** `TestBlindMailboxAgainstLiveNode` pasó contra São Paulo y Dallas (ida y
vuelta por etiqueta, sin remitente); `outboxLabel` decide con `peerProtocol >= BLIND_MIN_PROTOCOL`
y `BLIND_DEPOSIT` queda como interruptor global de vuelta atrás; el test cubre contacto v1, v2 y el
interruptor apagado. La verificación en el nodo con dos móviles es el nuevo punto 10 de
PRUEBAS-PENDIENTES §16.

**Lo que salió al intentar el test de la época 0 (el 0.2 reducido a test).** El test demostró que
el intercambio de anuncios **no** saca de la época 0 al que recibe primero: cada lado crea su
sesión con su hora local como linaje, el receptor la crea después (linaje mayor), abre el anuncio
por `openOld` sin adoptar el linaje menor y su primer mensaje sale en la época 0. Un arreglo obvio
—que una sesión que aún no ha cifrado nada adopte el linaje menor— lo tumbó `RatchetPropertyTest`
en la primera corrida (semilla 102): tras una reinstalación la sesión también está «virgen», y
adoptar un linaje viejo reutiliza ternas `(linaje, época, N)` ya gastadas. Los linajes tienen que
ser monótonos por identidad; se revirtió. Conclusión, en `DISENO-ratchet.md` §1.8.4: hoy el PFS del
primer mensaje **depende de los relojes** (si el receptor va por detrás, adopta y sale en época 1),
y cerrarlo de verdad pide que el receptor conteste con un sobre de control — dos sobres de 160 B,
sin cambio de formato — que no se hace antes de la revisión externa. Fijado por dos tests de
`ChatService` (uno por caso) y uno de `RatchetTest`.

**Fuzzing del parseo (0.3, el mismo día).** `ParserFuzzTest` (`:p2p-signaling`): fuzzing por
mutación con semilla fija, sin dependencias nuevas, de todo lo que parsea bytes de fuera —
`MessageEnvelope.decode` (nunca lanza), `Ratchet.decrypt` (solo `RatchetException`, nunca abre un
sobre alterado, no toca el estado), `Ratchet.Header.decode`, `Padding.strip`, `RatchetState.decode`
e `IdentityBackup.decode` (solo `InvalidBackup`) — con contratos por función, no solo «no explota».
Encontró uno de verdad, y no el que se esperaba. Se esperaba el contador de cadenas retiradas sin
acotar (`List(r.int())` reserva `n` huecos antes de leer nada); se acotó, y el fuzzer **siguió
fallando** con otro caso: la comprobación de límites de cada blob, `pos + n <= bytes.size`, **desborda**
con `n = 2³¹−1` — la suma sale negativa, pasa el `require`, y `Arrays.copyOfRange` calcula `to − from`,
que vuelve a dar `n` → `new byte[2³¹−1]` → `OutOfMemoryError`. Una revisión a ojo lo había dado por
bueno («comprueba que `pos + n` no pasa del final»); el desbordamiento solo se ve con el valor exacto.
Arreglado con `n <= bytes.size − pos`, que no suma. Severidad baja (el blob vive dentro de SQLCipher:
quien pueda escribirlo ya lo tiene todo, y `stateFor` lo captura y reengancha), pero es exactamente la
clase de cosa que un fuzzer encuentra en segundos y una revisión no. Los lectores
Go ya acotaban antes de reservar (`videoMaxFrame`, `uint16` en audio, `ReadSlice` con búfer fijo) y
el resto es `encoding/json`, así que no se añadió fuzz allí. Lo que esto **no** es: un fuzzer guiado
por cobertura; es lo que cabe en cada `testDebugUnitTest` (~7 s, casi todo PBKDF2 del respaldo).

### 4.2 Decidir la copia del historial y, mientras tanto, decirlo en la app (fase 5, nuevo)

Ver §3.1. Opciones: que el `.krbk` pueda incluir el historial de forma opcional, con la misma
frase-clave, o dejarlo fuera a propósito. Lo inmediato no depende de esa decisión: **corregir la
respuesta de `HelpContent.kt` para que diga que la copia no incluye mensajes ni archivos**, como ya
hace la política de privacidad.

**Hecha la parte inmediata (12 sep 2026):** la respuesta a «¿Puedo recuperar mi cuenta…?» dice ahora
que la copia recupera el PeerID y los contactos, no las conversaciones, y que no hay otra copia en
ningún sitio. Queda abierta la decisión de fondo.

### 4.3 Repartir builds release a los probadores (fase 1)

Ver §3.2. Es barato y quita un debilitamiento real de la build que se usa de verdad.

### 4.4 Actualizar o apagar los nodos caseros (fase 2, confianza operativa)

Ver §3.6. Como mínimo, el binario nuevo y purgar el `node.log` del Mac. Mejor aún, medir cuántos
móviles los siguen usando y ponerles fecha de apagado.

### 4.5 Quitar las contradicciones de `security-model.md`

Son las cuatro del §1. No es cosmético: **ese documento es la fuente de la que salen las
comparaciones**, y dos de los errores del PDF vienen de ahí.

**Hecho el 12 sep 2026.** Al releer salieron dos más, y se corrigieron las seis:

1. §4 y §10: el ratchet «sin probar entre dos móviles» pasa a «solo la conversación normal», con los
   cuatro escenarios pendientes nombrados. Sigue sin contarse como garantía.
2. §4: con los contactos sin ratchet, la identidad descifra «lo que haya capturado de la red», no
   «todo lo que tengas guardado». Lo guardado lo protege la base cifrada, como ya decía §9.2.
3. §6.1: el depósito ciego está implementado con el envío apagado, así que la tabla es lo que hoy
   hay en el disco del nodo.
4. §5.1 y §9.7: la fuga de IP a desconocidos por el relay está cerrada desde el 10 sep. Queda
   abierta la vía de `FindPeer` y la exposición a contactos y operador. §5.1 lleva ahora el
   estado arriba, antes del relato.
5. §8: `check-nodes.sh` comprueba los nodos de `DEFAULT_BOOTSTRAP` (hoy dos), no «los tres».
6. §10: la clave negociada por llamada se usa solo con los contactos que anuncian v2, como decía
   §4, no «para todos».

### 4.6 Tener SPQR delante al pedir la revisión externa

El diseño PQ no cambia. DISENO-postcuantico descartó fragmentar la clave entre mensajes, que es
justo lo que hace SPQR, con un argumento propio: el encadenado de la raíz ya da lo que se buscaba. Es
razonable, pero **una revisión externa lo va a preguntar**, porque Signal resolvió el mismo problema
de tamaño de otra forma. Conviene que el documento compare los dos enfoques explícitamente antes de
mandarlo.

---

### 4.7 Lo que salió al verificar en el móvil: dos conexiones con el mismo VPS

Al comprobar el build del 12 sep en el TECNO, el móvil tuvo dos veces (con la app en marcha y en
un arranque en frío; en otro arranque, no) con un mismo VPS una conexión por `tcp/4001` **y otra por
`wss/443`** — la de Caddy, por la que el nodo lo
ve llegar desde `127.0.0.1` y sus límites por IP dejan de contar. El `dial_ranker` del 10 sep
solo decide el *orden* de marcado. Se comprobó en proceso (proxy TCP lento) que un solo dial de
libp2p nunca deja dos conexiones — el worker cancela las que quedan en vuelo, y la carrera la
gana la wss dejando **una sola, por Caddy**, que es otro resultado malo —, así que las dos vienen
de episodios solapados; el disparador exacto no se llegó a fijar. El arreglo no depende de él:
`conn_prune.go` cierra toda WebSocket con un peer que ya tiene directa (Notifiee) — salvo si lleva
circuitos de relay, llamada o vídeo, en cuyo caso espera a que quede libre, porque cortarla colgaría
una llamada —, `StartDHT`
agrupa el bootstrap por PeerID, y el diagnóstico enseña las conexiones por nodo y las podadas.
Queda abierto el caso «solo wss porque la directa tardó más de 1 s»: ahí no hay nada que podar y
el móvil se queda por Caddy hasta que esa conexión caiga; una re-marcación directa diferida lo
cerraría, y no se ha hecho. Verificado en el TECNO tras regenerar el AAR (páginas de 16 KB en
las cuatro ABIs): 6 arranques en frío, todos solo con `tcp/4001` a los dos VPS, y el diagnóstico
diciendo `relay: OK (alcanzable por circuit; 1 conn: tcp | 1 conn: tcp)`. Límite honesto: en esas
corridas la poda no tuvo que actuar, así que en el móvil se confirma el estado final, no la poda en
sí; esa la cubren los tests de Go.

### 4.8 El repositorio se hizo público (12 sep 2026)

El mismo día el repositorio de GitHub pasó a ser público, lo que cierra la parte «código cerrado»
del eje de transparencia y hace visible **todo el historial de git**. Se revisaron sus 72 commits
antes de seguir:

- **Secretos: ninguno.** Nunca se versionaron keystores, `keystore.properties`, `node.key`,
  identidades, bases de datos ni `.krbk`. Las coincidencias de «contraseña» eran lecturas de
  `keystore.properties` y una frase de un test. `keys-git.md`, que está en la carpeta del autor,
  nunca se subió.
- **Datos personales que sí son públicos:**
  - el **PeerID del móvil del autor**, completo en `docs/AUDITORIA-2026-09-07.md` (truncado en el
    árbol actual, pero sigue en commits anteriores);
  - el **correo personal del autor** como autor de todos los commits;
  - el nombre de pila de un contacto real, en cinco líneas (no se repite aquí para no añadir
    una aparición más).

  No se reescribe el historial: el PeerID está pensado para compartirse y el filtro de conexiones ya
  corta a desconocidos. La salida limpia para ese PeerID es la rotación de identidad
  ([DISENO-rotacion-identidad.md](DISENO-rotacion-identidad.md)).
- **Medidas tomadas:**
  - `.gitignore` ignora también `*.krbk`, `*.db*` y los PDF de la bóveda;
  - los commits nuevos usan `info@4000msnm.com` (configuración local del repo);
  - licencia **MIT o Apache-2.0** a elección y `README.md` en la raíz.

## 5. Dónde el PDF acierta, y pesa

Para que no se pierda entre las correcciones:

1. **El protocolo propio sin revisar es el mayor riesgo del proyecto.** Los tests de propiedades no
   cambian eso: comprueban las propiedades que se nos ocurrieron.
2. **El relay ve el grafo en vivo**, y el depósito ciego no lo arregla. Solo lo mitiga subir la tasa de
   conexión directa (la prueba de NAT con dos SIM, que nunca se ha hecho).
3. **No hay revocación ni rotación de identidad.**
4. **No hay grupos ni multidispositivo**: son huecos del modelo, no solo funciones que faltan.
5. **En transparencia no había nada**: código cerrado (abierto el 12 sep 2026, ver §4.8), sin builds
   reproducibles, sin página de operador.
   Es el eje que convierte «el diseño es bueno» en «puedes comprobarlo».
6. **El relleno tiene límites**: los archivos troceados y las fotos siguen siendo reconocibles.
7. **La época 0 sigue siendo clásica** incluso con el post-cuántico implementado.

---

## 6. Resumen propio

Dónde Krypta es mejor **hoy**, no en potencia:

- No hay número de teléfono ni ningún identificador del mundo real ligado a una cuenta, porque no hay
  cuenta.
- No depende de Google ni de Apple para nada (con el coste del §3.5).
- Un desconocido no puede escribirte.

Dónde lo sería **si se hace lo que ya está diseñado**: con el depósito ciego encendido, el disco del
nodo deja de guardar el grafo; con el descubrimiento ciego y un segundo operador, desaparece el punto
central de metadatos. Hasta entonces, la frase honesta es esta:

> **Krypta cambia *quién* tiene tus metadatos, no *si* los tiene alguien.** Hoy los tiene una persona
> con dos VPS, con menos protección que el servidor de Signal (no hay equivalente a sealed sender) y
> sin nada publicado sobre cómo opera. La ventaja estructural es que ese punto central no es necesario
> y se puede quitar sin rehacer la app. Signal no puede quitar el suyo.

Dónde Signal es claramente mejor: protocolo revisado, post-cuántico en el inicio **y en el ratchet**,
metadatos, recuperación del historial, grupos, multidispositivo, transparencia y madurez operativa.
