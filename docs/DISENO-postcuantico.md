# Diseño: híbrido post-cuántico (ML-KEM-768)

**Fecha:** 11 de septiembre de 2026.
**Estado:** diseño cerrado con medidas reales. **Nada implementado.**
**Origen:** fase 3.2 de [PLAN-privacidad-y-confianza.md](PLAN-privacidad-y-confianza.md).

> **Nota de orden.** El plan pide la **revisión externa del protocolo antes** de tocar la parte
> post-cuántica (§3.1), y esa no depende de nosotros. Este documento está escrito precisamente
> para poder ser esa pieza revisable: congela el diseño **sin escribir código**, que es la forma
> barata de respetar ese orden en vez de saltárselo.

---

## 0. La amenaza, dicha en concreto

*Harvest now, decrypt later*: grabar tráfico cifrado hoy y descifrarlo cuando haya un ordenador
cuántico. En Krypta esto no es abstracto, y encaja peor que en la mayoría de los sistemas:

1. **El PeerID *es* la clave pública** de identidad Ed25519. No hay que robar nada para tenerla:
   se publica, se pega en un chat, se comparte por QR.
2. `S = X25519(identidad_propia, PeerID_del_otro)` es **función pura de dos identidades de larga
   vida**. Quien rompa X25519 sobre una clave publicada obtiene `S` de esa pareja.
3. La **época 0 se deriva de `S`**, y las épocas siguientes encadenan (`salt = RK(e-1)`) con un
   DH por época que es **el mismo X25519**.

Es decir: hoy, una conversación grabada entera es descifrable por un adversario cuántico futuro,
de principio a fin, sin robar un solo dispositivo. **No hay nada post-cuántico en ninguna parte
de Krypta.** El ratchet del 9–10 de septiembre protege contra el robo de la identidad *hoy*; no
contra esto.

La contrapartida, que también hay que decir: esto **no** es una vulnerabilidad presente. Es una
apuesta sobre cuándo existe la máquina, y el coste de equivocarse es asimétrico — si llega, el
tráfico de hoy ya está grabado y no hay arreglo retroactivo.

---

## 1. Lo medido (11 sep 2026)

Medido de verdad, no leído: `crypto/mlkem` de Go 1.26.4 y `SunJCE` del JDK 25.0.1, en el Mac del
autor. **Estos números son los que deciden el diseño**, así que van antes que él.

| | ML-KEM-768 | X25519 (hoy) |
|---|---|---|
| Clave pública / de encapsulado | **1184 B** | 32 B |
| Ciphertext de encapsulado | **1088 B** | — |
| Secreto compartido | 32 B | 32 B |
| Privada en el estado | 64 B (semilla, Go) · 2400 B (expandida, JDK) | 32 B |
| Generar par | 58 µs | 44 µs |
| Encapsular / acordar | 41 µs | 42 µs |
| Desencapsular | 59 µs | — |

Tres consecuencias, y la primera es toda la historia:

- **El coste de CPU es un no-problema.** ML-KEM-768 está en el mismo orden que X25519 (decenas de
  µs). Todo lo que cuesta este cambio es **tamaño**.
- **2272 B por mensaje es intolerable** si el material va en cada uno: un acuse de lectura pasa de
  162 B a 2434 B en el cable, **×15**. Y el acuse de lectura no es un caso raro — es lo que hace
  avanzar las épocas solo con abrir un chat (§1.3 del ratchet).
- **Pero cabe donde importa**: un trozo de archivo de 48 KiB con el material híbrido y el relleno
  ocupa 51 570 B de los 65 536 del blob del buzón (**78,7 %**). No hay que bajar `CHUNK_SIZE`.

### 1.1 Disponibilidad de la primitiva (comprobada)

- **Go 1.26.4**: `crypto/mlkem` en la estándar. Sin dependencias nuevas, igual que X25519.
- **JDK 25**: `SunJCE` ofrece `KeyPairGenerator("ML-KEM-768")` y `KEM("ML-KEM")` **nativos**, así
  que los tests corren en la JVM con el mismo patrón que hoy usa `JdkCurve25519`. Sin
  BouncyCastle.
- **Android: nada, a ninguna API.** Igual que pasó con X25519 (`XDH` desde API 33, `minSdk` 30),
  la implementación de dispositivo tiene que ser la del puente Go.

### 1.2 Formatos: qué es portable y qué no

Comprobado en las dos direcciones:

- **La pública sí.** El JDK la da envuelta en X.509 (1206 B) y Go cruda (1184 B): la diferencia
  es un **prefijo SPKI fijo de 22 bytes**:
  `308204b2300b0609608648016503040402038204a100`. Reconstruir la clave del JDK
  pegando ese prefijo al crudo da una clave **igual a la original**, y encapsular con ella
  desencapsula bien con la privada original. Es la misma clase de verruga que `JdkCurve25519` ya
  tiene con la coordenada `u`, y se resuelve igual: en la implementación de test, no en la
  interfaz.
- **La privada no, y no hace falta.** Go entrega una semilla de 64 B; el PKCS#8 del JDK son
  2428 B con la clave **expandida** (`NamedPKCS8Key.getRawBytes()` devuelve la expandida, no una
  semilla). Intenté construir un PKCS#8 en formato semilla y el JDK lo rechazó, pero **ese
  resultado no es concluyente**: la longitud de mi DER iba en forma no canónica y bien puede ser
  que el error fuera mío.

  Da igual, porque **el estado del ratchet nunca viaja**: lo escribe y lo lee la misma
  implementación en el mismo dispositivo (el `.krbk` lleva identidad y contactos, no sesiones).
  Así que la privada queda **opaca** en el estado, exactamente como hoy con
  `Curve25519.KeyPair.privateKey`, y la interfaz solo promete que **la pública y el ciphertext**
  tienen el formato del cable.

---

## 2. La decisión: un ratchet post-cuántico **lento**, desacoplado del de épocas

La idea clave sale de una propiedad que el ratchet **ya tiene** y que está escrita en su §1.2: la
raíz **encadena**, `RK(e) = HKDF(…, salt = RK(e-1))`. Eso significa que

> **una sola inyección post-cuántica con éxito protege todo lo que venga después, para siempre.**

No hay que re-encapsular por época. Hay que (a) entrar pronto en una sesión y (b) refrescar de
vez en cuando, para que un compromiso cuántico puntual no valga para siempre hacia adelante.

**Esto corrige el plan**, que decía «mezclar ML-KEM-768 en la raíz del ratchet, con reencapsulado
por época». Con los números del §1, el reencapsulado por época es exactamente lo que no se puede
pagar.

### 2.1 Lo que se descarta, y por qué

| Alternativa | Por qué no |
|---|---|
| **Re-encapsular en cada época** (lo que decía el plan) | ×15 en el tráfico de control; y las épocas avanzan **por mensaje recibido** (§1.8), no por turno |
| **Fragmentar la clave entre varios mensajes** (lo que hace el SPQR de Signal) | Solo hace falta si quieres PQ *por época*. Mucha maquinaria —reensamblado, pérdidas, desorden— para comprar algo que el encadenado de la raíz ya da |
| **PQ solo al abrir la sesión** | Una sesión larga no refresca nunca; y cualquier pérdida de estado vuelve a una época 0 clásica (§1.6), o sea que el PQ se perdería justo cuando se reengancha |

### 2.2 El mecanismo

Misma forma que las épocas —**sin iniciador ni respondedor**— porque es lo que ha hecho que el
resto del diseño no tenga carreras:

1. Cada lado mantiene un par ML-KEM. La cabecera puede llevar, **opcionalmente**, `pq_ek`
   (1184 B) y/o `pq_ct` (1088 B), marcados en bits del byte de flags.
2. **Quién ofrece la clave lo fija el orden canónico de los dos PeerID** (el mismo criterio que
   `MailboxLabel` y que el sentido de las cadenas): el menor manda `pq_ek`, el mayor encapsula
   contra ella y devuelve `pq_ct`. Dos tramos, **2272 B por ronda**, no cuatro.
3. Al llegar `pq_ct`, el que ofreció desencapsula. Los dos tienen ya el mismo `pq` de 32 B.
4. **Inyección**: en el siguiente avance de época,
   `RK(e) = HKDF(ikm = X25519(priv, pub) ‖ pq, salt = RK(e-1))`.

El punto 4 es el que no admite variantes: **híbrido es concatenar, nunca sustituir**. Si ML-KEM
resultara roto, la seguridad queda exactamente en la de hoy. Es la definición de híbrido y es
también la razón del aviso del §5.

**Cadencia**: la primera ronda arranca de inmediato (va pegada a los primeros mensajes de la
sesión) y después se refresca cada `K` épocas o cada `T` horas, lo primero que llegue. Con `K`
del orden de 20, el sobrecoste amortizado son ~114 B por mensaje y el tráfico de control sigue
cayendo en su tramo de 160 B casi siempre. El valor exacto es pregunta abierta (§6).

---

## 3. La cabecera pasa a ser de longitud variable

Hoy son 86 bytes fijos y **la cabecera entera es el AAD**. Los campos opcionales obligan a:

- **bits 1 y 2 de los flags**: «lleva `pq_ek`», «lleva `pq_ct`» (el bit 0 ya es el relleno,
  §1.10 del ratchet);
- los campos van **después del byte 86**, en orden fijo, con longitudes implícitas (1184, 1088):
  no hace falta un campo de longitud, y no tenerlo es un dato menos que un atacante pueda mover;
- `HEADER_BYTES` deja de ser una constante y pasa a ser una función de los flags. El AAD sigue
  siendo **toda** la cabecera, así que el material PQ va autenticado: quitar un `pq_ek` por el
  camino rompe el AEAD, igual que pasa con el bit de relleno.
- `looksLikeRatchet` no cambia (sigue mirando el byte 0).

**Y aquí está el riesgo nuevo del cambio, que conviene señalar antes de escribirlo:** `decode`
pasa a calcular un tamaño **a partir de bytes que vienen de la red**. Un flag forjado que declare
un `pq_ek` en un mensaje de 100 bytes tiene que dar «cabecera ilegible», no una lectura fuera del
buffer. Es la única superficie de ataque que este diseño añade y necesita sus propios tests, de
los dos bordes.

---

## 4. Estado y disco

Campos nuevos en `RatchetState` → **`FORMAT_VERSION = 2`**: la privada PQ propia (opaca), la
`pq_ek` propia y la del otro, el secreto de la ronda en curso y la época de la última inyección.

Un detalle que no se puede pasar por alto: hoy `decode` hace `require(version == FORMAT_VERSION)`,
y un estado ilegible **reengancha la sesión** (linaje nuevo, época 0). Eso *funciona*, pero
significaría que al actualizar, **todas las sesiones vivas vuelven a la época 0** y pierden su
progreso de PFS. Así que `decode` tiene que **leer el v1 y completarlo** con los campos PQ
vacíos, no fallar. Es barato y evita una degradación masiva y silenciosa el día de la
actualización.

**No toca Room**: el estado es un blob opaco para `:data` desde la fase 3 del ratchet. Sin
migración.

---

## 5. Dónde vive

| Pieza | Módulo | Por qué |
|---|---|---|
| `Kem` (generar par, encapsular, desencapsular) | interfaz en `:core` | Android no trae ML-KEM a **ninguna** API |
| — en dispositivo | `:native-bridge` → Go `crypto/mlkem` | mismo camino que `RatchetKeyPair`/`RatchetAgree`; AAR nuevo |
| — en tests | `:p2p-signaling` (test) | `SunJCE` del JDK 25, con la conversión del prefijo SPKI (§1.2) |
| Rondas PQ, inyección en la raíz, cabecera variable | `Ratchet` en `:p2p-signaling` | Kotlin puro y testable en JVM |
| Puerta por contacto | `ChatService` | `PROTOCOL_VERSION = 4`, `PQ_MIN_PROTOCOL = 4` |

Compatibilidad: **mínimo por capacidad**, la regla que se ganó el 11 de septiembre con el relleno
(`RATCHET_MIN_PROTOCOL = 2`, `PADDING_MIN_PROTOCOL = 3`). Una pareja mixta sigue en clásico, sin
que nada se rompa ni se pierda.

---

## 6. Qué **no** arregla (leer antes de prometer nada)

1. **No es retroactivo, y aquí duele más que en otros sitios.** Todo lo grabado antes de la
   primera ronda PQ de cada pareja queda vulnerable para siempre. El valor es estrictamente hacia
   adelante, lo que es un argumento para hacerlo pronto, no para hacerlo perfecto.
2. **La época 0 sigue siendo clásica**, y tiene que serlo: es lo que permite escribir sin ronda
   previa. O sea que **el primer mensaje de una sesión es vulnerable a un adversario cuántico**.
   Y aquí hay que ser honestos: **Signal no tiene este hueco**, porque PQXDH pone material
   post-cuántico en el acuerdo inicial gracias a que un servidor publica prekeys PQ. Krypta no
   puede sin una ronda previa, que es justo lo que su diseño evita. Lo acota que la primera ronda
   se completa en una ida y vuelta, y que un acuse de lectura ya es un mensaje.
3. **La autenticación no pasa a ser post-cuántica.** La identidad sigue siendo Ed25519/X25519, así
   que un adversario cuántico podría **suplantar** a alguien, aunque no leer el pasado protegido.
   Arreglarlo pide firmas PQ (ML-DSA), lo que cambia el formato del PeerID y la identidad de todos
   los contactos. Fuera de alcance — y Signal tampoco lo tiene.
4. **No reduce metadatos.** Ni uno.

---

## 7. Preguntas abiertas (hay que decidirlas antes de escribir código)

1. **La cadencia `K`/`T`.** Compromiso entre bytes y recuperación post-compromiso cuántica.
   Propuesta: primera ronda inmediata, refresco a las 20 épocas o 24 h.
2. **¿Encapsulan los dos lados o solo uno?** El §2.2 propone **uno**, elegido por orden canónico,
   porque cuesta la mitad de bytes. La objeción razonable es que así **el secreto lo elige el que
   encapsula**; el argumento de que no importa es que va *mezclado* con el X25519 de la época y
   que tanto `pq_ek` como `pq_ct` van en el AAD, así que un encapsulador malicioso no gana nada
   que no ganara ya clásicamente. **Es la pregunta número uno para una revisión externa.**
3. **768 o 1024.** 768 es lo que usa Signal y lo que NIST recomienda por defecto; 1024 sube a
   1568 + 1568 B.
4. **Vector de prueba conocido (KAT de FIPS 203).** Ver §8: no es opcional.

---

## 8. Plan por fases

| Fase | Qué | Dónde | Riesgo |
|---|---|---|---|
| 0 | Decidir el §7 | — | conversación |
| 1 | `Kem` en `:core` + `JdkKem` + tests (tamaños, ida y vuelta, conversión del prefijo, **KAT**) | `:core`, `:p2p-signaling` | nada observable |
| 2 | `KemKeyPair`/`KemEncapsulate`/`KemDecapsulate` en Go + AAR + `BridgeKem` | `:native-bridge` | regenerar AAR (4 ABIs, 16 KB) |
| 3 | Cabecera variable + validación de longitudes + tests de los dos bordes | `:p2p-signaling` | **parseo de datos de red** |
| 4 | Máquina de la ronda PQ + inyección en `RK` + estado `FORMAT_VERSION = 2` leyendo v1 | `:p2p-signaling` | el núcleo |
| 5 | `RatchetPropertyTest` sorteando rondas PQ; test de presupuesto de tamaño | tests | — |
| 6 | `PROTOCOL_VERSION = 4` + `PQ_MIN_PROTOCOL` + puerta por contacto | `ChatService` | despliegue |
| 7 | Docs y redacción honesta de cara al usuario | `docs/` | — |

Las fases 1 y 2 no cambian nada observable y se pueden hacer sin riesgo, como pasó con el ratchet.

---

## 9. Recomendación

Hacerlo, en el orden del §8, **pero con dos condiciones que no son burocracia**:

1. **La revisión externa primero**, como pide el plan. Este documento existe para poder ser
   revisado sin haber escrito código.
2. **El KAT de la fase 1 no es opcional**, y la razón es incómoda: *ser híbrido esconde los
   errores*. Si la implementación de ML-KEM estuviera mal —o el puente devolviera basura, o una
   ABI del AAR saliera rota— el resultado sería un mensajero que **funciona perfectamente** y es
   exactamente igual de seguro que hoy. Nada falla, nada avisa, y la propiedad que se creía
   comprada no existe. Un vector de prueba conocido es lo único que distingue «híbrido» de
   «clásico con 2 KB de relleno caro».

Y la nota de honestidad de siempre: con esto Krypta podrá decir que el tráfico de una pareja
actualizada **no se puede guardar hoy para descifrarlo mañana**, salvo su primer intercambio. Lo
que seguirá sin poder decir es que la identidad resista a un adversario cuántico, ni que el nodo
no sepa con quién hablas.
