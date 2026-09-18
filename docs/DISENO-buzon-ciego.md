# Diseño: depósito ciego en el buzón (protocolo v2)

**Estado:** decidido el 9 sep 2026 (rotación **semanal**), **implementado entero y con el envío
encendido desde el 12 sep 2026 — por contacto**. El cliente recibe a ciegas (retira y se
suscribe al wake por etiquetas), el nodo lo sirve, y se deposita bajo etiqueta a quien haya
anunciado protocolo **≥ 2** (`ChatService.BLIND_MIN_PROTOCOL`); al resto, por PeerID. Ver la
nota del §6 sobre por qué eso es seguro sin coordinar publicaciones. `BLIND_DEPOSIT` sigue
existiendo como interruptor global de vuelta atrás. Verificado el 12 sep contra los dos VPS
(`TestBlindMailboxAgainstLiveNode`: ida y vuelta por etiqueta, sin remitente).
**Fecha:** 9 de septiembre de 2026.
**Origen:** [security-model.md](security-model.md) §6 y §10 — «que el nodo deje de ver quién
escribe a quién» es el trabajo con más impacto en privacidad que queda pendiente.

---

## 0. Léase esto antes de decidir

El buzón no es el único sitio donde el nodo ve el grafo social. **El relay ve lo mismo, y
este diseño no lo arregla.**

Cuando DCUtR no perfora el NAT —lo habitual en móvil—, la conversación viaja por Circuit
Relay v2 a través del nodo, que necesariamente conoce los dos PeerID de esa conexión para
reenviar. Es inherente a lo que es un relay: no se puede cegar sin construir encaminamiento por
capas (estilo Tor), que es otro proyecto de otra magnitud.

Así que el alcance real de este cambio es:

| Camino | ¿Ve el nodo quién habla con quién? | ¿Lo arregla este diseño? |
|---|---|---|
| Directo (DCUtR perfora) | No | — (ya estaba bien) |
| **Buzón** (destinatario desconectado) | Sí, y **lo escribe en disco** | **Sí** |
| Relay (NAT sin perforar, en vivo) | Sí, en memoria mientras dura la conexión | **No** |
| Wake (presencia) | Sí, quién está conectado | Parcialmente (§4.3) |

La ganancia grande y sólida es que **el disco deja de contener el grafo social**. Hoy, quien
se lleve el directorio del buzón —un volcado del VPS, una copia de seguridad del proveedor,
un registro que alguien active mañana— obtiene pares emisor/destinatario con hora exacta. Tras
este cambio, obtiene etiquetas opacas que no se pueden atribuir a nadie sin el secreto
compartido de cada pareja.

Frente a un operador **activo y malicioso**, que observa en vivo, la ganancia es mucho menor:
sigue viendo el relay, y en la fase 1 sigue pudiendo correlacionar depósito y retirada (§5).

**Si la decisión es solo por coste/beneficio, esto es lo que hay que sopesar.** Merece la pena
si el modelo de amenaza es «que un compromiso futuro del nodo no revele el pasado». No merece
la pena si lo que se busca es que el operador no pueda saber nada en vivo: eso exige tocar
también el relay, y eso no está sobre la mesa.

---

## 1. El mecanismo

Igual que el rendezvous, pero para el buzón: la dirección deja de ser un PeerID y pasa a ser
una **etiqueta derivada del secreto compartido de la pareja**, distinta por sentido y rotativa
por día.

```
dir     = 0 si peerID_emisor < peerID_destinatario, si no 1     (orden canónico, ambos lo saben)
etiqueta = HKDF(secreto_compartido, info = "krypta-mbx:" ‖ fecha ‖ ":" ‖ dir)   → 32 bytes
```

- **A deposita** bajo `etiqueta(A→B)`.
- **B retira** pidiendo `etiqueta(A→B)` de cada uno de sus contactos.
- Nadie más puede calcularlas: hace falta el secreto compartido, que solo tienen los dos.

Que la etiqueta dependa del sentido es lo que evita que A se retire su propio correo, y tiene
una propiedad agradable: que A conozca `etiqueta(A→B)` no le sirve de nada, porque ahí solo hay
correo que él mismo puso.

---

## 2. Protocolo v2

Se añaden protocolos nuevos; los v1 **siguen existiendo** (ver §6).

### 2.1 `/krypta/mbx/put/2.0.0`

```
cliente → {"v":2,"label":"<hex 32B>","blob":"<b64>"}\n
nodo    → {"ok":true} | {"err":"…"}
```

Diferencias con v1: no hay `to`, y el nodo **no añade `from`**. El sobre guardado es
`{id, ts, blob}` y nada más. En disco: `<mailboxdir>/<label>/<id>.json`.

Que desaparezca `from` tiene un coste que hay que asumir a conciencia: hoy el remitente lo fija
el nodo desde la identidad del stream y por eso **no es suplantable**. En v2 esa garantía se
mueve dentro del cifrado: el receptor sabe de quién es el mensaje porque la etiqueta identifica
la pareja **y** porque solo esa pareja tiene la clave con la que el `blob` descifra. Es al menos
igual de fuerte —AES-GCM autentica— y además ya no depende de que el nodo se porte bien.

### 2.2 `/krypta/mbx/get/2.0.0`

```
cliente → {"v":2,"labels":["<hex>","<hex>",…]}\n
nodo    → {"label":"…","id":"…","ts":…,"blob":"…"}\n … {"done":true}\n
cliente → {"ack":["id",…]}\n
```

La etiqueta es una **credencial al portador**: quien la presenta, retira. Como solo la pareja
puede derivarla, el efecto es el mismo que la autenticación por identidad de stream de v1, pero
sin que el nodo tenga que saber a quién sirve.

Cuántas etiquetas van en cada petición: `contactos × días_de_ventana`. Con TTL de 7 días hay que
pedir 7 por contacto para no dejarse nada, o sea 140 etiquetas (4,5 KB) con 20 contactos. Es
asumible, pero es la parte que más crece y está entre las preguntas abiertas (§7).

### 2.3 `/krypta/wake/2.0.0`

Hoy el nodo avisa al PeerID destinatario, cosa que en v2 no conoce. El cliente pasa a
suscribirse **a sus etiquetas**:

```
cliente → {"v":2,"labels":["<hex>",…]}\n     (al abrir, y al rotar el día)
nodo    → {"ping":true} | {"wake":true}
```

El aviso sigue sin llevar payload ni remitente. El nodo mantiene en memoria un índice
etiqueta → conexión.

Ojo con lo que esto le entrega al nodo mientras mira: la suscripción va por un stream
autenticado, así que le llega el PeerID del suscriptor junto a **todas** sus etiquetas de una
vez. Ver §4.2.

---

## 3. Qué gana

| Dato en disco del nodo | v1 (hoy) | v2 |
|---|---|---|
| Destinatario | PeerID real, **como nombre de directorio** | Etiqueta opaca y rotativa |
| Remitente | PeerID real, dentro del sobre | **No se guarda** |
| Hora | Sí | Sí (necesaria para el TTL) |
| Tamaño | Sí | Sí |
| Contenido | Cifrado | Cifrado |

Un volcado del disco pasa de ser un grafo social con marcas de tiempo a una lista de etiquetas
sin dueño.

**Y hay un efecto secundario que puede ser lo mejor del diseño:** en v2 **un desconocido no
puede depositar en el buzón de otro**, porque no sabe calcular su etiqueta. El ataque que
motivó el reparto justo del 8 sep —llenar la cuota de la víctima para dejarla sin entrega— deja
de ser posible por construcción, no por cuota. Lo único que puede hacer un extraño es inventar
etiquetas al azar y ocupar disco con correo que nadie retirará jamás; eso lo acota el TTL y un
tope global, no hace falta reparto entre remitentes.

---

## 4. Qué NO gana

1. **El relay** sigue viendo ambos extremos (§0).
2. **La correlación en vivo**, en la fase 1: el nodo ve al depositante (identidad del stream,
   que libp2p siempre autentica) y ve quién pide esa misma etiqueta después. Uniendo las dos
   observaciones, reconstruye la pareja **mientras el proceso está corriendo**. Lo que ya no
   puede es dejarlo escrito sin proponérselo: para conservarlo tiene que registrarlo
   activamente, que es un acto deliberado y no el estado por defecto.

   **Y el wake v2 se la da en un paso, no en dos.** Para suscribirse, el cliente manda
   `{"v":2,"labels":[…]}` (§2.3) por un stream que libp2p autentica igual que cualquier otro,
   así que el nodo recibe atados el **PeerID de quien se suscribe** y su **conjunto entero de
   etiquetas**. No hace falta esperar a ningún depósito: ahí ya está cuántos contactos tiene
   ese PeerID, y cada etiqueta que después aparezca en un depósito dice quién es cada uno. Es
   la misma correlación de este punto sin el paso de la retirada, y es lo que hay que tener en
   cuenta al leer el §3: la etiqueta es opaca para quien mire el disco después, no para quien
   mire las conexiones mientras ocurren. Cegarlo de verdad exige que quien se suscribe tampoco
   sea el PeerID real, o sea la fase 2 (§5) aplicada **también** al wake, no solo al depósito.
3. **La presencia**: el wake sigue delatando qué PeerID está conectado y cuándo — y, en v2,
   con cuántas etiquetas, o sea con cuántos contactos activos.
4. **Los tamaños y los tiempos**: sin relleno ni batching, un archivo troceado se sigue viendo
   como una ráfaga de depósitos de 48 KiB.

---

## 5. Fase 2: romper también la correlación en vivo

La correlación del punto 4.2 existe porque **las dos puntas van autenticadas**: libp2p siempre
sabe quién abre el stream. Para romperla, el depósito tendría que hacerse desde una **identidad
efímera** (un host libp2p de usar y tirar, distinto del PeerID real). Entonces el nodo ve «un
peer cualquiera depositó bajo la etiqueta L» y no puede atarlo a nadie.

El problema es que eso **desarma el anti-abuso**: el límite de ritmo y el reparto justo se
anclan en el PeerID del remitente, y con identidades desechables ese ancla desaparece. Las
salidas posibles, ninguna gratis:

- **Cuota por etiqueta** en vez de por remitente: natural en v2, porque una etiqueta *es* una
  relación. No frena a quien inventa etiquetas al azar, pero eso ya lo acotan el TTL y el tope
  global de disco.
- **Prueba de trabajo** por depósito: encarece la avalancha sin identificar a nadie. Gasta
  batería del móvil y es una carrera armamentística perdida frente a un atacante con CPU.
- **Credenciales ciegas** (firma ciega del destinatario a sus contactos): la respuesta
  criptográficamente correcta, y con diferencia la más cara de implementar y de revisar.

Recomendación: **la fase 1 no depende de esto**. Conviene entregar la fase 1, medir, y dejar la
fase 2 como decisión aparte — probablemente junto con el ratchet, que es el otro trabajo
criptográfico grande pendiente.

---

## 6. Compatibilidad y migración

> **Corrección (9 sep 2026).** Este apartado estaba mal planteado: daba por hecho que la
> compatibilidad dependía de la versión del **nodo**. No es así. Lo determinante es la versión
> del **destinatario**: si A deposita bajo una etiqueta y el cliente de B todavía retira solo
> por su PeerID, B **nunca mirará ese buzón** y el mensaje caducará ahí a los 7 días. Que el
> nodo hable v2 no arregla eso.
>
> De ahí el orden real de despliegue, que es el clásico de cualquier cambio de protocolo:
> **primero todos saben recibir, después se enciende el envío.** Por eso el depósito ciego
> quedó tras un interruptor (`ChatService.BLIND_DEPOSIT`) a la espera de que la versión que
> sabe recibir estuviera repartida.
>
> **Corrección (12 sep 2026): no hacía falta esperar a una publicación, y ya está encendido.**
> La condición «el destinatario sabe retirar por etiquetas» se puede saber **por contacto**: la
> retirada por etiquetas entró en el cliente el 9 sep (`63222d1`), **antes** que el anuncio de
> capacidad `V` del 10 sep (`afb576a`), así que no existe ningún cliente que anuncie protocolo
> 2 y no sepa recibir a ciegas. `outboxLabel` devuelve la etiqueta si
> `contact.peerProtocol >= BLIND_MIN_PROTOCOL` (= 2) y cadena vacía si no — el mismo patrón que
> el ratchet y el relleno, y la misma regla: cada capacidad tiene su propio mínimo. La
> exposición que queda es la ya aceptada con el ratchet: un contacto que anunció 2 y después
> vuelve a un build anterior perdería lo que se le deposite a ciegas (caduca a los 7 días).
>
> Un efecto secundario que casi se cuela: al suscribirse el cliente al wake **solo** por
> etiquetas, los depósitos v1 —que hoy son todos— dejaban de despertarlo, degradando la
> entrega instantánea a sondeo de minutos. El nodo suscribe ahora también al PeerID del propio
> stream, que conoce igualmente por ser quien abre la conexión.

Es lo más delicado, porque hay móviles instalados y tres nodos que no se actualizan a la vez.

1. **El nodo habla los dos protocolos.** v1 y v2 conviven en el mismo binario; el
   almacenamiento también (directorios de PeerID para v1, de etiqueta para v2). Sin fecha de
   corte: v1 se retira cuando se retire, y mientras tanto no estorba.
2. **El cliente intenta v2 y cae a v1.** `MailboxPut` prueba `/krypta/mbx/put/2.0.0`; si el nodo
   no lo soporta (`protocols not supported`), repite en v1 contra ese mismo nodo. Igual en
   fetch y wake. Esto ya encaja con el multi-nodo actual: hoy el put hace failover entre nodos,
   ahora además hace failover de versión.
3. **Periodo de transición**: el cliente **retira por las dos vías** —v1 por su PeerID y v2 por
   sus etiquetas— hasta que el correo v1 deje de aparecer. Sin esto, lo depositado por un
   contacto que aún no ha actualizado se quedaría sin recoger.
4. **Nada que migrar en disco**: los sobres v1 existentes se entregan por v1 y expiran por TTL.
   A los 7 días de que el último cliente actualice, el almacenamiento v1 se vacía solo.
5. **Orden de despliegue**: primero los tres nodos (que ya saben v2 pero nadie lo usa), después
   la app. Al revés, la app hablaría v2 contra nodos que no lo entienden y caería a v1 — que
   funciona, pero sin ganancia.

---

## 7. Preguntas abiertas (hay que decidirlas antes de escribir código)

1. ~~**Rotación diaria vs. etiqueta estable.**~~ **Decidido: semanal** (9 sep 2026). Con el TTL
   de 7 días bastan **dos etiquetas por contacto** (la semana en curso y la anterior), o sea 40
   con 20 contactos. El corte no cae en lunes sino en una rejilla fija de 7 días desde la
   época: da igual dónde caiga mientras los dos extremos hagan la misma cuenta.
2. ~~**TTL.**~~ **Decidido: sigue en 7 días.** La rotación semanal encaja con él sin forzar nada.
3. ~~**Tope global de disco por nodo.**~~ **Decidido: 2 GiB**, desalojando lo más antiguo desde
   el barrido horario. El VPS tiene 15 GB libres y el buzón ocupaba 5 MiB, así que sobra
   holgura; y en v2 hace falta un techo agregado porque la cuota por destinatario desaparece.
4. ~~**¿Se cifra la etiqueta en reposo?**~~ **Decidido: no.** Quien se lleve el disco puede
   contar relaciones y ver su ritmo, pero no atribuirlas; cifrarlas con una clave que vive en
   la misma máquina no cambia nada frente a quien se lleva las dos.
5. **Qué hacer con `from` en la interfaz interna.** `MailboxHandler.OnMailboxMessage(id, from,
   ts, data)` recibe el remitente del nodo, y `ChatService.onReceived` resuelve el contacto por
   ese PeerID. En v2 habría que resolverlo **por la etiqueta** (el cliente sabe qué contacto le
   corresponde). Es un cambio pequeño pero toca el camino de recepción, que es el más delicado
   del cliente.

---

## 8. Plan por fases

| Fase | Qué | Dónde | Esfuerzo |
|---|---|---|---|
| 0 | Decidir §7 (rotación, TTL, topes) | — | conversación |
| 1 | ✅ Derivación de etiquetas + tests (`MailboxLabel`, 6 tests) | `:p2p-signaling` | hecho |
| 2 | ✅ v2 en el nodo (put/get/wake + almacenamiento + topes) conviviendo con v1 (5 tests) | `infra/node` | hecho |
| 3 | ✅ v2 en el puente Go, con caída a v1 por nodo (4 tests) | `native-bridge/libp2p` | hecho |
| 4 | ✅ Recepción por etiqueta en el cliente y retirada doble durante la transición (4 tests) | `:p2p-signaling` | hecho |
| 5 | ✅ Nodos desplegados y verificados; **envío encendido por contacto el 12 sep 2026** (`BLIND_MIN_PROTOCOL`, ver §6). Pendiente la prueba con dos móviles (PRUEBAS-PENDIENTES §16.10) | infra + app | hecho |

La fase 2 y la 3 son las que llevan el trabajo. Nada de esto es reversible a medias: una vez
que hay clientes hablando v2, el nodo tiene que seguir soportándolo, así que conviene cerrar §7
antes de empezar.

---

## 9. Recomendación

Hacerlo, con dos condiciones: **solo la fase 1** (dejar la 2 —identidades efímeras— para
cuando se aborde el ratchet), y **decidiendo antes** la rotación semanal y los topes globales
del §7.

Y una nota de honestidad para la documentación de cara al usuario: aunque esto entre, la
política de privacidad y el modelo de seguridad **no podrán decir que el nodo no sabe con quién
hablas**, porque el relay lo sigue sabiendo. Lo que sí podrán decir, y hoy no, es que **no
queda escrito en ningún disco**.
