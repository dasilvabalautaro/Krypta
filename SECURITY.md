# Seguridad de Krypta

Krypta es un mensajero cifrado de extremo a extremo, sin servidor central y sin cuentas. Lo
desarrolla y lo opera **una persona**. Si has encontrado un fallo de seguridad, gracias por
avisar: esta página dice cómo hacerlo y qué puedes esperar.

*(English below.)*

## Cómo avisar

Escribe a **[info@4000msnm.com](mailto:info@4000msnm.com)** con el asunto `[seguridad]`.

- **En el primer correo, describe el área y el impacto, no la explotación completa.** No hay
  todavía una clave PGP publicada; si el detalle pone en riesgo a usuarios, pide en ese primer
  correo un canal cifrado y se acuerda uno.
- Incluye, si puedes: versión de la app (o commit), dispositivo y versión de Android, pasos para
  reproducirlo, qué consigue un atacante y qué necesita para conseguirlo.
- No hace falta que tengas una prueba de concepto terminada: un indicio bien explicado vale.

## Qué puedes esperar

- **Acuse de recibo en un plazo de 7 días.** Es un proyecto de una persona; si no llega
  respuesta en ese plazo, reenvía el correo.
- Una valoración honesta: si es un fallo, si ya estaba documentado como límite conocido, y qué
  se va a hacer.
- **Divulgación coordinada**: se publica cuando haya arreglo desplegado, o a los **90 días**
  del aviso si no lo hay, lo que ocurra antes. Si hay usuarios en riesgo activo, se habla antes.
- **Crédito** en la nota de cambios y en el documento que corresponda, si lo quieres.
- **No hay programa de recompensas.** Tampoco se va a emprender ninguna acción contra quien
  investigue de buena fe dentro de estas reglas.

## Qué entra

| Componente | Dónde |
|---|---|
| App Android (`chat.neto.krypta`) | `app/`, `core/`, `data/`, `p2p-signaling/` |
| Puente Go sobre go-libp2p | `native-bridge/libp2p/` |
| Nodo de infraestructura (bootstrap, DHT, relay, buzón, wake) | `infra/node/` y los nodos públicos `krypta-sp.neto.chat` y `krypta-dal.neto.chat` |
| Especificación normativa del protocolo | `docs/ESPECIFICACION-protocolo.md` |
| Diseños de protocolo | `docs/DISENO-ratchet.md`, `docs/DISENO-buzon-ciego.md`, `docs/DISENO-postcuantico.md`, `docs/DISENO-rotacion-identidad.md` |

Interesan especialmente:

- lo que rompa la confidencialidad o la autenticidad de los mensajes y las llamadas;
- los fallos del ratchet por épocas: reutilización de claves o nonces, degradaciones, abrir un
  mensaje con otra clave;
- **cualquier propiedad de las numeradas (P1–P14) en el §12 de la especificación que no se
  cumpla**;
- las formas de que un desconocido obtenga datos de un usuario a partir de su PeerID;
- los fallos de parseo de datos que llegan de la red;
- cualquier camino por el que un nodo guarde o revele más de lo que dice
  [docs/security-model.md](docs/security-model.md).

## Qué no entra, o ya está documentado

Antes de escribir, mira el §9 de [docs/security-model.md](docs/security-model.md) («Resumen
honesto de lo que Krypta NO protege») y el §13 de
[docs/ESPECIFICACION-protocolo.md](docs/ESPECIFICACION-protocolo.md) («Debilidades conocidas»).
Lo que está ahí es un **límite conocido**, no un hallazgo nuevo, aunque una forma nueva y más
barata de explotarlo sí interesa. Entre otros:

- El operador del nodo ve metadatos: quién está conectado, quién habla con quién por el relay y
  el grafo diario de parejas en la DHT.
- Tus contactos y el operador del nodo pueden ver tu IP.
- No hay criptografía post-cuántica, y la época 0 de cada sesión no tiene secreto hacia adelante.
- Quien obtiene una identidad puede escribir como sus contactos y secuestrar una sesión con un
  linaje forjado (W-3 y W-4 de la especificación).
- Quien tiene el móvil desbloqueado, root o código dentro del proceso lo tiene todo.
- Las compilaciones de depuración permiten `adb run-as`.

Tampoco entran: ataques que requieren que la víctima instale una app maliciosa con permisos
especiales, ingeniería social, informes automáticos sin impacto demostrado, ni ausencia de
cabeceras o configuraciones «recomendadas» sin un ataque concreto.

## Reglas para investigar

- **Prueba solo contra tus propios dispositivos, identidades y contactos.** No intentes leer,
  alterar o bloquear los mensajes de otras personas.
- **No hagas pruebas de carga ni de denegación de servicio contra los nodos públicos**: los
  usan personas reales y los opera una sola persona. Para eso, levanta tu propio nodo con
  `infra/node` (instrucciones en [infra/node/README.md](infra/node/README.md)).
- Si en el camino accedes a datos de otra persona, para, no los guardes y avisa.

---

## English

Krypta is an end-to-end encrypted, server-less messenger built and operated by **one person**.

**Report** to **[info@4000msnm.com](mailto:info@4000msnm.com)** with `[security]` in the
subject. In the first email, describe the area and impact rather than the full exploit; there is
no PGP key published yet, so ask for an encrypted channel if the details put users at risk.
Spanish or English are both fine.

**What to expect:** acknowledgement within **7 days** (resend if you hear nothing), an honest
assessment, **coordinated disclosure** when a fix ships or **90 days** after the report,
whichever comes first, and credit if you want it. **There is no bug bounty.** Good-faith research
within these rules will not be pursued.

**In scope:** the Android app, the Go libp2p bridge, the infrastructure node and the public nodes
`krypta-sp.neto.chat` / `krypta-dal.neto.chat`, the normative protocol specification
(`docs/ESPECIFICACION-protocolo.md`) and the protocol designs under `docs/`. A violation of any
numbered property (P1–P14, spec §12) is especially welcome.

**Known limits** are listed in §9 of `docs/security-model.md` and §13 of the specification:
operator-visible metadata, IP exposure to contacts and the operator, no post-quantum crypto, no
forward secrecy for epoch 0, an identity holder can impersonate contacts and hijack a session
through a forged lineage, and a compromised device. A new or cheaper way to exploit them is
welcome; the limit itself is not a finding.

**Rules:** test only against your own devices and identities; **no load or DoS testing against
the public nodes** — run your own from `infra/node`; if you reach someone else's data, stop,
don't keep it, and tell us.
