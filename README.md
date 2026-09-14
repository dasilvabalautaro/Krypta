# Krypta

Mensajería cifrada de extremo a extremo para Android, **sin servidor central, sin cuentas y sin
número de teléfono**. Los móviles se encuentran y hablan entre sí sobre
[libp2p](https://libp2p.io) (DHT, Circuit Relay v2 y hole punching), con un buzón cifrado para
cuando el otro está desconectado.

> **Estado: beta, sin publicar en tiendas.** Lo desarrolla y opera una sola persona. La
> criptografía de sesión (un doble ratchet por épocas) es propia y **no ha tenido revisión
> externa**; la revisión está **en trámite**. Mientras tanto, el protocolo está
> [especificado](docs/ESPECIFICACION-protocolo.md) para poder revisarse, y una
> [revisión interna](docs/REVISION-protocolo-2026-09-14.md) del 14 sep 2026 encontró y arregló
> dos fallos graves. Antes de confiarle nada sensible, lee
> [lo que Krypta no protege](docs/security-model.md#9-resumen-honesto-de-lo-que-krypta-no-protege).

*English summary [below](#english).*

---

## Qué hace

- **Identidad sin cuenta.** Tu identidad es un par de claves Ed25519 generado en el móvil, y tu
  PeerID —la clave pública— es tu dirección. Se comparte como texto o QR, y un número de
  seguridad (o el QR) permite comprobar que nadie ha sustituido el PeerID por el camino.
- **Mensajes, fotos, archivos, notas de voz, GIF, respuestas con cita y llamadas de voz y vídeo.**
- **Cifrado de extremo a extremo**: acuerdo X25519 y AES-256-GCM; doble ratchet por épocas con
  secreto hacia adelante (con cada contacto cuya app también lo anuncia); el tamaño de los
  mensajes va relleno por tramos para que no delate qué son.
- **Sin directorio de usuarios.** Cada pareja se cita en un punto de encuentro de la DHT que
  cambia cada día y solo pueden calcular sus dos miembros.
- **Entrega diferida con depósito ciego**: el buzón del nodo guarda sobres cifrados bajo
  etiquetas opacas, sin remitente ni destinatario en disco. El aviso de «tienes correo» va por
  una conexión propia con el nodo, **sin Google ni FCM**.
- **En el dispositivo**: base de datos cifrada (SQLCipher), adjuntos e identidad cifrados con
  claves del Android Keystore, bloqueo opcional de la app, capturas de pantalla bloqueadas en el
  chat y nada en la copia de Google Drive. La única copia es un `.krbk` cifrado con tu frase-clave.

## Lo que conviene saber

- **Los nodos de infraestructura** (dos VPS, en São Paulo y Dallas) los opera una persona. No ven
  el contenido, pero sí metadatos: quién está conectado, quién habla con quién cuando el tráfico
  pasa por el relay, y el grafo diario de parejas en la DHT.
- **Tus contactos y el operador del nodo pueden ver tu IP.**
- **Quien robe tu identidad y además actúe** (no solo escuche) puede suplantar a tus contactos y
  secuestrar una conversación. El ratchet protege frente a quien solo escucha. Está declarado en
  la [especificación](docs/ESPECIFICACION-protocolo.md#13-debilidades-conocidas).
- **No hay** grupos, multidispositivo, criptografía post-cuántica (diseñada, sin implementar) ni
  rotación de identidad (diseñada, sin implementar).
- **La copia `.krbk` no incluye los mensajes**: perder el móvil es perder el historial.
- En algunos móviles (Transsion/HiOS), la entrega con la app en segundo plano depende de los
  ajustes de batería del fabricante.

El detalle, sin rebajas, está en [docs/security-model.md](docs/security-model.md).

## Estructura

| Módulo | Qué contiene |
|---|---|
| `app/` | Interfaz (Jetpack Compose), servicio en primer plano, notificaciones |
| `core/` | Dominio puro: modelos e interfaces, sin Android |
| `data/` | Persistencia (Room + SQLCipher) |
| `p2p-signaling/` | Lógica de mensajería: `ChatService`, ratchet, buzón, rendezvous, llamadas |
| `native-bridge/` | Envoltorio Kotlin del puente Go (`native-bridge/libp2p/`, compilado con gomobile) |
| `infra/node/` | Nodo de infraestructura: bootstrap, DHT, relay, buzón y aviso |

La arquitectura completa está en [docs/architecture.md](docs/architecture.md).

## Compilar

Requisitos: JDK 25, Android SDK (compileSdk 36.1), NDK `26.1.10909125` y Go 1.26.4. Con
`GOTOOLCHAIN=auto`, Go se descarga solo la versión que pide `go.mod`. `build-aar.sh` comprueba
esas versiones e instala gomobile y gobind a la versión fijada. La app pide Android 11 (API 30) o
superior.

```bash
# 1. El puente Go. El AAR (~75 MB) no se versiona: hay que generarlo en un clon limpio.
native-bridge/libp2p/build-aar.sh

# 2. La app y los tests
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

Usa `:app:assembleDebug`: el `assembleDebug` agregado sin módulo no funciona con AGP 9.

Los binarios exactos de la versión que se somete a revisión externa (el AAR y el APK de
depuración arm64, con su sha256) están en la release
[`revision-externa-1`](https://github.com/dasilvabalautaro/Krypta/releases/tag/revision-externa-1).
Son de depuración, y los de esa etiqueta no son reproducibles; los avisos están en la propia
release.

**El AAR es reproducible desde el commit `8d02875`.** `build-aar.sh` compila el mismo commit a los
mismos bytes desde cualquier carpeta y con la caché vacía, mete el commit dentro de la librería y
comprueba las versiones de Go, gomobile, el NDK y el JDK. Para comprobar un AAR, compila su commit
y compara el sha256 que imprime el script. Solo se ha comprobado en una Mac; entre sistemas
distintos no se ha probado.

Para montar un nodo propio (también sirve para hacer pruebas de carga sin tocar los públicos),
sigue [infra/node/README.md](infra/node/README.md).

## Documentación

Casi toda está en español.

| Documento | De qué trata |
|---|---|
| [security-model.md](docs/security-model.md) | Modelo de seguridad: qué ve cada actor y qué no se protege |
| [ESPECIFICACION-protocolo.md](docs/ESPECIFICACION-protocolo.md) | Especificación normativa del protocolo: bytes, derivaciones, reglas, propiedades y debilidades declaradas |
| [REVISION-protocolo-2026-09-14.md](docs/REVISION-protocolo-2026-09-14.md) | Revisión interna del protocolo, sus hallazgos y el plan de revisión externa y análisis formal |
| [SOLICITUD-revision-externa.md](docs/SOLICITUD-revision-externa.md) | Qué se pide en la revisión externa, y los textos para pedirla |
| [architecture.md](docs/architecture.md) | Arquitectura actual |
| [DISENO-ratchet.md](docs/DISENO-ratchet.md) | Doble ratchet por épocas y relleno por tramos |
| [DISENO-buzon-ciego.md](docs/DISENO-buzon-ciego.md) | Depósito ciego en el buzón |
| [DISENO-postcuantico.md](docs/DISENO-postcuantico.md) | Híbrido post-cuántico (ML-KEM-768), medido y sin implementar |
| [DISENO-rotacion-identidad.md](docs/DISENO-rotacion-identidad.md) | Rotación y revocación de identidad, sin implementar |
| [PLAN-privacidad-y-confianza.md](docs/PLAN-privacidad-y-confianza.md) | Qué falta para que la apuesta de Krypta sea cierta y comprobable |
| [PRUEBAS-PENDIENTES.md](docs/PRUEBAS-PENDIENTES.md) | Lo implementado que aún no se ha probado con dos móviles |
| [politica-privacidad.html](docs/politica-privacidad.html) | Política de privacidad |

## Seguridad

Para avisar de un fallo de seguridad, lee [SECURITY.md](SECURITY.md) y escribe a
**info@4000msnm.com**. Por favor, no hagas pruebas de carga ni de denegación de servicio contra
los nodos públicos: levanta el tuyo.

## Licencia

A tu elección, bajo cualquiera de estas dos licencias:

- MIT ([LICENSE-MIT](LICENSE-MIT))
- Apache 2.0 ([LICENSE-APACHE](LICENSE-APACHE))

Salvo que digas lo contrario, cualquier contribución que envíes para incluirla en Krypta se
entiende licenciada de la misma forma, bajo MIT o Apache-2.0, sin condiciones adicionales.

---

## English

Krypta is an end-to-end encrypted Android messenger with **no central server, no accounts and no
phone number**. Phones find and talk to each other over libp2p (DHT, Circuit Relay v2, hole
punching), with an encrypted store-and-forward mailbox for offline delivery and a
Google-free wake channel.

**Status: beta, not published.** Built and operated by one person. The session protocol (an
epoch-based double ratchet) is custom and **has not been externally reviewed yet**; a review is
being requested. In the meantime, the protocol has a normative specification
([docs/ESPECIFICACION-protocolo.md](docs/ESPECIFICACION-protocolo.md), Spanish), with numbered
properties and declared weaknesses. An internal review on 14 Sep 2026
([docs/REVISION-protocolo-2026-09-14.md](docs/REVISION-protocolo-2026-09-14.md)) found and fixed
a key/nonce reuse race and a permanent message-loss bug. The infrastructure nodes never see content
but do see metadata; see [docs/security-model.md](docs/security-model.md) (Spanish) for the full,
unsoftened threat model.

Build: run `native-bridge/libp2p/build-aar.sh` (Go 1.26 + gomobile + NDK 26.1), then
`./gradlew :app:assembleDebug`. The binaries of the version under external review (AAR and arm64
debug APK, with sha256) are in the release
[`revision-externa-1`](https://github.com/dasilvabalautaro/Krypta/releases/tag/revision-externa-1).
The AAR build is reproducible from commit `8d02875` on: the same commit gives the same bytes, and
the commit is embedded in the library.
Security reports: [SECURITY.md](SECURITY.md),
info@4000msnm.com.

Licensed under either of MIT ([LICENSE-MIT](LICENSE-MIT)) or Apache-2.0
([LICENSE-APACHE](LICENSE-APACHE)) at your option.
