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
| Tests de propiedades del ratchet (pérdidas, desorden, duplicados, envíos simultáneos, pérdida de estado) | ✅ 10 sep 2026 (`RatchetPropertyTest`) — y encontró algo en su primera corrida: ver abajo |
| `check-nodes.sh` programado con aviso | ⬜ |

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

1. **Descubrimiento ciego** (diseño primero, como se hizo con el buzón). Hoy los dos miembros
   de una pareja anuncian **la misma** clave de rendezvous y los móviles son clientes de la
   DHT, así que los nodos tienen el grafo diario. Camino a evaluar: anunciar y buscar con una
   identidad libp2p **desechable**, de modo que el registro no lleve el PeerID real y la
   identidad de verdad se autentique después, al conectar. Límite honesto: la IP sigue en el
   registro, así que hay que combinarlo con el punto 4.
2. **Encender el depósito ciego** (`BLIND_DEPOSIT`), que ya está construido y probado contra
   los tres nodos. Condición: que la versión que sabe recibir esté repartida.
3. **Relleno por tramos** dentro del cifrado, para que el tamaño no delate si es texto, foto o
   nota de voz. Signal rellena a múltiplos de 160 bytes; Krypta no oculta el tamaño y así se
   dice en el §4 del modelo de seguridad. Requiere versión de protocolo anunciada, el mismo
   mecanismo que ya usa el ratchet.
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
2. **Post-cuántico híbrido.** Signal tiene el acuerdo inicial post-cuántico desde 2023 (PQXDH)
   y desde octubre de 2025 también el ratchet (SPQR, con ML-KEM-768). Krypta no tiene nada, y
   como `S` es función pura de dos identidades X25519, un adversario cuántico futuro que haya
   **grabado tráfico hoy** podrá descifrar todo lo de clave estática y la época 0: *harvest
   now, decrypt later* aplica de lleno. Plan: mezclar **ML-KEM-768** en la raíz del ratchet,
   con reencapsulado por época. Detalle práctico que lo hace viable: **Go 1.26 trae
   `crypto/mlkem` en la librería estándar**, así que va por el puente igual que X25519 y **sin
   dependencias nuevas** (Android no lo trae, como ya pasó con X25519). La época 0 seguiría
   siendo clásica.
3. **Modelo formal ligero** (ProVerif/Tamarin) del ratchet por épocas, si aparece quien lo haga.

---

## 4. Transparencia

Es el eje que sostiene «puedes confiar en la implementación, no solo en el diseño», y hoy Krypta
no tiene nada de esto.

1. **Decidir si se abre el código.** Es requisito para la auditoría del OTF, para que los builds
   reproducibles signifiquen algo y para que la comparación con Signal deje de ser desigual por
   fuerza. Hay que valorar el efecto sobre Nyx, que comparte transporte y criptografía.
2. **`SECURITY.md` y `security.txt`** con un contacto. Nota: Signal **tampoco** tiene programa
   de recompensas, solo un correo de seguridad; lo que falta aquí es la vía, no el dinero.
3. **Builds reproducibles** de APK, AAR y binario del nodo.
4. **Página de operador** e informe de transparencia, aunque diga «0 peticiones».
5. **Un segundo operador ajeno** en la lista de nodos por defecto.

---

## 5. Identidad

Signal tiene revocación y recuperación; Krypta ninguna de las dos, y el PeerID **es** la
identidad.

1. **Rotación voluntaria** firmada con la identidad anterior: los contactos migran con aviso y
   el número de seguridad cambia. Sirve para una migración planificada, no contra un ladrón
   (que también podría rotar). Hay que decirlo así.
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
