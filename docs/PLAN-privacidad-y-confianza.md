# Plan: privacidad y confianza

**Fecha:** 10 de septiembre de 2026.
**Origen:** una comparación de diseño con Signal (documento externo, en la bóveda del autor)
más lo que salió al verificarla contra el código. La comparación no se versiona aquí a
propósito: es circunstancial y vendrán otras. Lo que se guarda es **el plan**.

Este plan no compite con [PLAN-senalizacion-descentralizada.md](PLAN-senalizacion-descentralizada.md),
que es la hoja de ruta del transporte. Aquí se ordena lo que falta para que **la apuesta de
Krypta sea cierta y comprobable**, que es otra cosa.

---

## 0. El principio

Krypta hace una apuesta distinta de Signal: sin cuentas, sin números de teléfono, sin servidor
que enrute, sin FCM. Eso elimina de raíz la base de datos central de identidad, que es la
crítica más repetida a Signal, y no hay nada que pedirle judicialmente al proyecto porque no
existe.

Pero **no hay que perseguir el tamaño de Signal** (grupos, multidispositivo, una fundación con
equipo legal). Krypta no puede ganar ahí y no necesita hacerlo. Lo que sí tiene que hacer es que
**haga falta confiar menos en el operador**, y que lo que quede de confianza **se pueda
comprobar**. Todo lo de abajo sirve a eso.

Dos cosas que conviene tener claras al leer el plan, porque son las que más pesan:

1. **La criptografía propia sin revisar es el mayor riesgo del proyecto.** El ratchet por
   épocas es una desviación justificada ([DISENO-ratchet.md](DISENO-ratchet.md) §2) con
   primitivas estándar, pero es un protocolo propio, con días de vida, en el camino de código
   más sensible, y el envío ya está encendido.
2. **En metadatos, hoy Signal está mejor.** No es un empate. Krypta elimina el directorio
   central, pero el operador del nodo ve el grafo de parejas activas cada día por la DHT
   ([security-model.md](security-model.md) §5) y ve quién habla con quién en vivo cuando el
   tráfico pasa por el relay. Esa es la brecha que cierra la fase 2.

---

## 1. Que no se pierdan mensajes (bloquea publicar)

| Qué | Estado |
|---|---|
| §16 del ratchet con dos móviles: conversación normal | ✅ 10 sep 2026 |
| §16: reentrega del buzón, archivo grande cruzando época, pérdida de estado, llamada | ⬜ **pendiente, y es la puerta de publicación** |
| §13 entrega en 2.º plano | ✅ una muestra el 10 sep; falta la medición de no regresión |
| Failover São Paulo → Dallas | 🟡 media prueba, ocurrió sola; falta que el destinatario retire del segundo nodo |
| Tests de propiedades del ratchet (pérdidas, desorden, duplicados, envíos simultáneos, pérdida de estado) | ✅ 10 sep 2026 (`RatchetPropertyTest`) — y encontró algo en su primera corrida: ver abajo. Ojo: «envíos simultáneos» ahí es **lógico**, no concurrente; la carrera real (H-0) no la podía ver |
| Revisión interna del protocolo | ✅ 14 sep 2026 ([REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md)): **H-0** (dos operaciones simultáneas repetían clave y nonce) y **H-1** (tras borrar y volver a añadir un contacto, o importar un `.krbk`, sus mensajes se perdían para siempre), arreglados con tests que fallaban antes |
| §16.11 y §16.12: volver a añadir un contacto, y ráfagas cruzadas, con dos móviles | ⬜ pendiente (necesita el build del 14 sep en los dos) |
| `check-nodes.sh` programado con aviso | ✅ 12 sep 2026: cargado en launchd en la Mac (cada 15 min, aviso al cambiar el estado, 1,2 s de CPU por pasada en vez de 84). Decidido dejar la Mac sin suspensión en vez de un canal de alerta externo; detectó sola la caída de Dallas durante su redespliegue |

Los tests de propiedades ya están (`RatchetPropertyTest`): sortean secuencias de envíos,
entregas desordenadas, pérdidas, duplicados y pérdidas de estado con semillas fijas, y fijan que
las dos partes siempre converjan, que nada se abra como otro mensaje y que **nunca se repita una
terna `(linaje, época, N)`** en un mismo emisor, que es la forma observable de que ninguna clave
ni nonce se reutiliza.

**Y encontraron dos cosas**, ambas en [DISENO-ratchet.md](DISENO-ratchet.md) §1.9:

- El ratchet **no** detecta la reproducción de un mensaje de la época 0, porque esa época se
  re-deriva del secreto compartido. Lo para la deduplicación previa — o sea que esa
  deduplicación es una pieza de seguridad, no una comodidad. **Arreglado**: la poda pasa a ser la
  unión de "últimos 8 días" (margen sobre el TTL del buzón) y "últimas 500", porque solo por
  cantidad dejaba de proteger justo a las parejas más activas. Al probarlo salió además un
  **hallazgo de producción**: podar en cada mensaje recibido tumbaba el proceso con una ráfaga de
  600 (un archivo troceado); ahora se poda una de cada 64.
- La regla del linaje perdía mensajes en silencio tras una reinstalación. **Arreglado el mismo
  día** (`ChatService.rehook`): el receptor que falla al abrir reengancha al otro sin esperar a
  que nadie escriba, con tope por contacto. La ventana pasa de "hasta que la otra persona
  escriba" a un solo mensaje.

---

## 2. Que el grafo no exista en ningún sitio

Es la brecha real frente a Signal, y la parte donde Krypta puede acabar **mejor**, porque no
tiene un directorio central que proteger.

### 2.0 Por qué el operador ve los pares, y cuál de estas acciones rinde más

Las cuatro acciones de abajo atacan canales distintos del mismo problema. Conviene saber qué las
une, para no pagar caro lo que se puede conseguir barato.

**La raíz es que el PeerID es a la vez la dirección y la identidad.** libp2p autentica la
identidad de largo plazo antes del primer byte, y en Krypta esa identidad *es* la clave pública
de la que sale el secreto compartido — que es justamente lo que permite añadir a alguien pegando
su PeerID, sin intercambio de claves. El precio es que no existe separación entre «quién se
conecta» y «quién soy»: cualquier nodo con el que hables sabe quién eres, siempre, por
construcción.

Una sola palanca mueve tres de los cuatro canales: **que la identidad que se conecta sea
desechable y la de verdad se autentique una capa más arriba**. Y cuesta menos de lo que parece,
porque en el buzón ciego **la identidad autenticada del transporte ya no aporta ninguna
propiedad de seguridad**: en v1 el nodo fijaba el `from`, y eso hacía al remitente no
suplantable; en v2 esa garantía la da el cifrado extremo a extremo, no el nodo. Lo único que
sostiene hoy el PeerID real en el depósito y en el wake es el **anti-abuso**. Ni más ni menos.

| Canal | ¿Inherente? | Qué lo quitaría | Precio |
|---|---|---|---|
| **Buzón** | No | Identidad efímera al depositar | El ancla del anti-abuso ([DISENO-buzon-ciego.md](DISENO-buzon-ciego.md) §5) |
| **Wake** | No | Lo mismo al suscribirse: el nodo necesita saber **a qué conexión** escribir, no **de quién** es | El mismo — y el §5 hoy solo contempla el depósito, no el wake |
| **Rendezvous** | No | El punto 1 de abajo | La IP sigue en el registro, así que hay que combinarlo con el punto 4 |
| **Relay** | **Sí**, a un salto | Encaminamiento por capas estilo Tor | Otro proyecto: latencia, batería, complejidad |

Del relay solo es inherente esto: para reenviar, alguien tiene que saber **a quién**. Que ese
alguien sepa además **de quién** viene solo se rompe repartiendo el conocimiento entre varios
saltos.

**Lo que hace que hoy sea total no es el protocolo, es el despliegue.** En una DHT grande, el
registro de cada pareja aterriza en el nodo más cercano a esa clave, y la clave rota cada día:
ningún operador ve el grafo entero, ve fragmentos rotatorios que no puede ensamblar. En Krypta
hay dos nodos y los dos son del mismo operador, así que caen siempre en los mismos. La propiedad que
protegería el diseño distribuido existe; lo que falta es que la red lo esté.

**Consecuencia para las prioridades:** la medida con más retorno por lo que cuesta no es
criptográfica — es **§4.5, un segundo operador ajeno**. No toca el formato de red (congelado
hasta la auditoría), no necesita revisión externa, y convierte «un solo operador ve el grafo
de todos» en «nadie lo ve entero». Hoy figura como transparencia; en metadatos es la primera.

**El suelo que ningún diseño quita**, y que hay que decir antes de prometer nada: el operador
seguirá viendo una IP que habla Krypta, a qué horas y con qué tamaños. Contra eso solo hay
tráfico de relleno y batching, que cuestan batería y no existen. Y el anonimato necesita
compañía: con pocos usuarios y dos nodos, el conjunto en el que uno se esconde es diminuto.
Signal tampoco tiene esta propiedad — su *sealed sender* oculta el remitente a un servidor que
sabe perfectamente a qué cuenta entrega; lo compensan con escala y política, no haciéndolo
imposible. La meta realista no es que el operador no pueda ver nada, sino **que no exista un
operador en posición de verlo todo**.

Las acciones concretas, en ese marco:

1. **Descubrimiento ciego** (diseño primero, como se hizo con el buzón). Hoy los dos miembros
   de una pareja anuncian **la misma** clave de rendezvous y los móviles son clientes de la
   DHT, así que los nodos tienen el grafo diario. Camino a evaluar: anunciar y buscar con una
   identidad libp2p **desechable**, de modo que el registro no lleve el PeerID real y la
   identidad de verdad se autentique después, al conectar. Límite honesto: la IP sigue en el
   registro, así que hay que combinarlo con el punto 4.
2. ~~**Encender el depósito ciego** (`BLIND_DEPOSIT`), que ya está construido y probado contra
   los tres nodos. Condición: que la versión que sabe recibir esté repartida.~~ **Hecho el 12
   sep 2026, por contacto**: la condición se cumple contacto a contacto (quien anuncia
   protocolo ≥ 2 ya retira por etiquetas, porque esa capacidad entró en el cliente antes que
   el anuncio), así que `outboxLabel` decide con `peerProtocol >= BLIND_MIN_PROTOCOL` y no hacía
   falta otra publicación. Verificado contra los dos VPS; pendiente con dos móviles
   (PRUEBAS-PENDIENTES §16.10).
3. ~~**Relleno por tramos** dentro del cifrado, para que el tamaño no delate si es texto, foto o
   nota de voz.~~ **Hecho el 11 sep 2026** (`Padding`, §1.10 de
   [DISENO-ratchet.md](DISENO-ratchet.md)): 160 B hasta 4 KiB —el grano de Signal, y por lo
   mismo: ahí vive el tráfico de control— y 1 KiB hasta 64 KiB. Va **dentro del ratchet**, no
   dentro del sobre, para cubrir de una sola decisión todos los tipos (acuse de lectura, hello,
   señal de llamada, trozo de archivo), y lo marca un bit de la cabecera que **ya iba
   autenticado como AAD**, así que nadie puede tocarlo por el camino. Lo que se gana es que el
   tráfico de control deje de distinguirse por el tamaño; lo que **no** se gana, y hay que
   decirlo, es esconder un archivo troceado: 48 KiB rellenados a un tramo de 1 KiB siguen
   siendo 48 KiB. Eso pide tráfico de relleno y batching, que siguen sin hacerse.
4. **La IP** ([security-model.md](security-model.md) §5.1):
   - ✅ **Hecho el 10 sep**: el `ConnectionGater` corta al extraño que marcaba por el relay, que
     era la vía por la que se entregaba la IP pública en 1,7 s.
   - ⚠️ Que el nodo **no reparta direcciones de móviles** por `FindPeer`: **el arreglo obvio no
     vale**. En kad-dht (v0.28.2, la del nodo) `filterAddrs` se aplica en los dos sitios —
     `handlers.go:369` para `FindPeer` **y** `handlers.go:325` para `GetProviders`, que es el
     **rendezvous**—, así que un `dht.AddressFilter` dejaría a los contactos sin las direcciones
     con las que se encuentran. Separarlos exige parchear kad-dht. Alternativa sin parche: que
     el **móvil** no publique direcciones públicas directas y todo entre por relay, con DCUtR
     subiéndolo después a directo — pero eso **depende de que DCUtR funcione**, que es
     justamente el gate de NAT que nunca se ha medido. Queda pendiente de esa medición.
   - ✅ **mDNS desactivado por defecto** (10 sep 2026). Se anunciaba siempre; ahora es opt-in
     desde Ajustes → "Red local", con efecto inmediato en los dos sentidos (`StopMdns` en el
     puente, que además suelta el `MulticastLock` que antes quedaba tomado para siempre).
   - ⬜ No anunciar direcciones de red local (mismo condicionante que el punto del nodo).
   - ⬜ Decidir si se ofrece un **modo "solo relay"**: los contactos ven la IP por diseño en
     cuanto hay conexión directa, y Signal tiene el equivalente ("retransmitir siempre las
     llamadas").

Ninguna de estas oculta la IP **al operador del nodo**. Para eso solo sirve una VPN o Tor, y eso
también le pasa a Signal.

---

## 3. Criptografía comprobable

1. **Revisión externa del protocolo.** El propio diseño dice que es «la parte del proyecto que
   más se beneficiaría» de una, y la decisión de no buscarla quedó anotada como riesgo asumido
   (§8.6 de DISENO-ratchet). **Conviene revertirla**, y hacerlo antes de tocar la parte
   post-cuántica. Opciones de bajo coste: el Security Lab del Open Technology Fund (audita
   gratis proyectos abiertos de libertad en internet — hay que confirmar disponibilidad y
   **exige código abierto**, ver fase 4), o una revisión pagada de pocos días solo del ratchet.

   **Decisión revertida y paquete preparado el 14 sep 2026.** Quedan escritas:
   - la **especificación normativa** ([ESPECIFICACION-protocolo.md](ESPECIFICACION-protocolo.md)),
     con propiedades numeradas, debilidades declaradas y preguntas para quien revise;
   - una **revisión interna previa**
     ([REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md)). Encontró H-0 (clave
     y nonce repetidos con operaciones simultáneas) y H-1 (mensajes perdidos para siempre tras
     volver a añadir un contacto), ya arreglados con tests que fallaban antes.

   Qué pedir, a quién y con qué reglas está en el §5 de esa revisión. **La solicitud la tramita el
   autor** (14 sep 2026), con los textos de
   [SOLICITUD-revision-externa.md](SOLICITUD-revision-externa.md) y sobre el commit etiquetado
   `revision-externa-1`.
2. **Post-cuántico híbrido.** Signal tiene el acuerdo inicial post-cuántico desde 2023 (PQXDH)
   y desde octubre de 2025 también el ratchet (SPQR, con ML-KEM-768). Krypta no tiene nada, y
   como `S` es función pura de dos identidades X25519, un adversario cuántico futuro que haya
   **grabado tráfico hoy** podrá descifrar todo lo de clave estática y la época 0: *harvest
   now, decrypt later* aplica de lleno — y peor que en otros sistemas, porque **el PeerID *es* la
   clave pública**: no hay que robar nada para tenerla.

   **Diseño cerrado y medido el 11 sep 2026**, sin código:
   [DISENO-postcuantico.md](DISENO-postcuantico.md). Lo que cambió al medir de verdad:
   - **El coste de CPU es un no-problema** (ML-KEM-768 va en 40–60 µs, el mismo orden que
     X25519). Todo lo que cuesta este cambio es **tamaño**: 1184 B de clave + 1088 B de
     ciphertext.
   - Por eso **se descarta el «reencapsulado por época»** que decía este plan: pondría un acuse
     de lectura en 2434 B frente a 162, **×15**. En su lugar, un **ratchet PQ lento** — la raíz
     ya encadena (`salt = RK(e-1)`), así que **una sola inyección con éxito protege todo lo que
     venga después**, y basta con entrar pronto y refrescar de vez en cuando.
   - Primitiva disponible en los dos lados sin dependencias nuevas: `crypto/mlkem` en Go 1.26 y
     **ML-KEM-768 nativo en el JDK 25** (`SunJCE`), así que los tests siguen corriendo en JVM.
     Android no lo trae a ninguna API, igual que pasó con X25519.
   - Dos huecos que hay que decir en voz alta: la época 0 **sigue siendo clásica** (Signal sí
     cubre el acuerdo inicial con PQXDH, porque tiene servidor de prekeys y nosotros no), y la
     **autenticación tampoco** pasa a ser post-cuántica.
   - Y una condición que no es burocracia: **ser híbrido esconde los errores**. Si ML-KEM
     estuviera mal implementado, el resultado sería una app que funciona y es igual de segura que
     hoy — nada falla, nada avisa. De ahí que el vector de prueba conocido sea obligatorio.
3. **Modelo formal ligero** (ProVerif/Tamarin) del ratchet por épocas, si aparece quien lo haga.
   **Planificado el 14 sep 2026** ([REVISION-protocolo-2026-09-14.md](REVISION-protocolo-2026-09-14.md) §4):
   - **Tamarin, por fases**: núcleo → linaje → capacidades → llamadas, con seis lemas.
   - **Una comprobación de cordura**: el modelo con linaje tiene que encontrar H-4 solo, y el de
     capacidades con la regla anterior, H-3. Un modelo que no encuentra un ataque conocido no está
     modelando lo que creemos.
   - **Lo que no verá**: la carrera de H-0, porque trata cada paso como atómico.

   Nada modelado todavía, y Tamarin no está instalado.

---

## 4. Transparencia

Es el eje que sostiene «puedes confiar en la implementación, no solo en el diseño», y hoy Krypta
no tiene nada de esto.

1. ~~**Decidir si se abre el código.**~~ **Abierto el 12 sep 2026**: el repositorio de GitHub
   (`dasilvabalautaro/Krypta`) es público. Era requisito para la auditoría del OTF, para que los
   builds reproducibles signifiquen algo y para que la comparación con Signal deje de ser
   desigual por fuerza. La **licencia** quedó puesta el mismo día: doble, **MIT o Apache-2.0** a
   elección (`LICENSE-MIT`, `LICENSE-APACHE`), con un `README.md` en la raíz. Lo que sigue
   pendiente aquí: valorar el efecto sobre Nyx, que comparte transporte y
   criptografía, y que el historial público no filtre nada (revisado el mismo día, ver
   REVISION-comparacion-signal §4.8).
2. ~~**`SECURITY.md` y `security.txt`** con un contacto.~~ **Hecho el 12 sep 2026.**
   [SECURITY.md](../SECURITY.md) (es/en): contacto `info@4000msnm.com`, acuse en 7 días,
   divulgación coordinada a 90 días, sin recompensas, qué entra, qué es límite conocido (§9 del
   modelo de seguridad) y la regla de no hacer pruebas de carga contra los nodos públicos.
   `security.txt` (RFC 9116) servido en `https://krypta-sp.neto.chat/.well-known/security.txt` y
   en el de Dallas, desde `infra/node/security.txt` vía `deploy-caddy.sh`; **caduca el 1 sep
   2027** y hay que renovarlo. Con el repo público desde el mismo día, `SECURITY.md` también se
   ve en GitHub. Nota: Signal **tampoco** tiene programa de
   recompensas, solo un correo de seguridad; lo que faltaba aquí era la vía, no el dinero.
3. **Builds reproducibles** de APK, AAR y binario del nodo.

   **Primer paso, 14 sep 2026.** El AAR y el APK de `revision-externa-1` se compilaron desde un
   clon limpio y se publicaron con su sha256 en la
   [release de esa etiqueta](https://github.com/dasilvabalautaro/Krypta/releases/tag/revision-externa-1). Al hacerlo salió que
   `build-aar.sh` **fallaba en un clon limpio** (el directorio de salida no existe); ya está
   arreglado.

   **AAR reproducible, 14 sep 2026 (commit `8d02875`).** Se cerró lo que faltaba:
   - **Rutas locales.** `-trimpath`, y compilar desde una **ruta fija** (`/tmp/krypta-aar`).
     Después de `-trimpath` quedaban 2 rutas: las de la directiva `replace` que gomobile escribe
     apuntando a la carpeta del módulo, y `-trimpath` no las quita.
   - **Commit dentro.** `Version()` lo devuelve, inyectado con `-ldflags -X`, con `-modificado` si
     hay cambios sin confirmar. El script comprueba que está.
   - **`proguard.txt` con fecha fija.** Antes cambiaba el AAR según la hora.
   - **Herramientas fijadas y comprobadas**: `toolchain go1.26.4`, gomobile y gobind a la versión
     de `go.mod`, NDK 26.1 y JDK 25. `go mod tidy -diff` sustituye a `go mod tidy`.

   **Comprobado**: el commit `8d02875`, compilado desde dos clones en rutas distintas y con cachés
   de Go vacías e independientes, dio el mismo AAR (`d817bae1…f4e0`) y los mismos ficheros dentro.

   De paso salió que **todos los AAR anteriores se compilaron con el NDK 25.2**, no con el 26.1 que
   decían los documentos: `~/.zprofile` exportaba `ANDROID_NDK_HOME`. El script ahora lo ignora.

   Lo que **sigue pendiente** en este punto:
   - **Otras máquinas**: solo se ha comprobado en una Mac. El NDK trae binarios distintos para macOS
     y Linux, y nadie ha comparado entre ellos.
   - **El APK**:
     - Su **librería nativa sí queda ligada a la fuente**: es exactamente la del AAR pasada por
       `llvm-strip --strip-unneeded`, porque AGP le quita los símbolos al empaquetar. Comprobado
       byte a byte.
     - **Lo demás no se ha intentado**: el dex, los recursos y la firma, que con la clave de
       depuración es propia de cada máquina.
   - **El binario del nodo** (`infra/node`).
   - **Los binarios de `revision-externa-1`**: son anteriores a esto y no son reproducibles.
4. **Página de operador** e informe de transparencia, aunque diga «0 peticiones».
5. **Un segundo operador ajeno** en la lista de nodos por defecto.

---

## 5. Identidad

Signal tiene revocación y recuperación; Krypta ninguna de las dos, y el PeerID **es** la
identidad.

1. **Rotación voluntaria** firmada con la identidad anterior: los contactos migran con aviso y
   el número de seguridad cambia. Sirve para una migración planificada, no contra un ladrón
   (que también podría rotar). Hay que decirlo así. **Diseñada el 12 sep 2026, sin código**:
   [DISENO-rotacion-identidad.md](DISENO-rotacion-identidad.md). Aviso `M` dentro del E2EE con
   doble firma (clave vieja y nueva), gracia de 14 días, detección de bifurcación (dos rotaciones
   de la misma clave congelan la conversación) y revocación sin sustituto. Las fases que tocan el
   protocolo esperan a §16 y a la revisión externa.
2. **Recordatorio periódico** de exportar el `.krbk`, que hoy es la única recuperación.

---

## 6. Producto

Con el criterio de siempre: las convenciones de mensajería se adoptan salvo que rompan el
modelo de privacidad.

1. **Mensajes efímeros 1:1.** Esfuerzo medio, sin infraestructura. Signal los tiene.
2. **Grupos pequeños**, repartiendo cada mensaje por pareja sobre el ratchet. Esfuerzo grande;
   diseño antes.
3. **Multidispositivo.** Fuera de alcance por ahora, y conviene **decirlo** en vez de dejarlo
   como un hueco silencioso.

---

## 7. Qué no haría

- **Montar un servidor de prekeys** para poder usar `libsignal-client`. Resolvería el riesgo de
  la fase 3.1, pero rompe la apuesta de Krypta: volvería a haber un servicio central del que
  depende el arranque de cada conversación.
- **Perseguir grupos o multidispositivo antes de las fases 1–3.** Son lo que más se nota al
  usar la app y lo que menos arregla de lo que hoy está mal.

---

## 8. Si solo se hacen cinco cosas

1. Los cuatro escenarios que faltan de §16, y los tests de propiedades del ratchet.
2. El descubrimiento ciego (fase 2.1).
3. Cerrar lo que queda de la fuga de IP (fase 2.4).
4. Abrir el código y conseguir la revisión externa.
5. El post-cuántico híbrido.
