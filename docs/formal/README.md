# Modelos formales del protocolo de Krypta

Modelos simbólicos en [Tamarin](https://tamarin-prover.com) del ratchet por épocas, según el plan de
[REVISION-protocolo-2026-09-14.md](../REVISION-protocolo-2026-09-14.md) §4. Los hizo el propio
proyecto, con asistentes de IA, igual que la [auditoría interna](../AUDITORIA-interna-2026-10-11.md):
**no son un análisis independiente**. Su valor está en que cualquiera puede correrlos.

## Qué sale, y por qué fiarse

La prueba de que un modelo modela lo que creemos es que **encuentre por sí solo los fallos que ya
conocemos**. Los dos lo hacen:

| Modelo | Lema | Resultado | Qué significa |
|---|---|---|---|
| M1, derivaciones v3 | `fs_epoca_vigente` | **falsificado** | Encuentra W-15 (A-01 de la auditoría): con el estado de A se reabre lo que A ya envió en la época |
| M1, derivaciones v4 (`-DV4`) | `fs_epoca_vigente` | **verificado** | El protocolo v4 lo cierra |
| M1, las dos | `secreto`, `acuerdo`, `ejecutable` | verificado | Sin comprometer nada no se lee nada, y lo que B acepta lo envió A |
| M2 | `recuperacion_con_S` | **falsificado** | Encuentra H-4 / W-4: con `S`, un linaje forjado deja a B en la sesión del atacante sin vuelta atrás |
| M2 | `recuperacion_honrada` | verificado | Sin nadie con `S`, cuando A pierde el estado, B adopta su linaje nuevo |
| M2b (regla del linaje nueva, §1.13) | `sin_S_solo_linajes_honrados` | verificado **condicionado** | Sin `S`, por muchas cabeceras falsas que fabrique el atacante, nadie adopta un linaje que no haya creado un extremo honrado. Se verifica suponiendo el auxiliar `linaje_escrito_es_honrado`, que Tamarin no cierra en 2 min |
| M2b | `tope`, `ejecutable` | verificado | Un linaje fuera de «ahora + tope» no se adopta (el `Long.MAX_VALUE`) |

`propuesta_autentica`, `secreto_dh` (M1) y `linaje_de_B_adoptado` (M2) son lemas auxiliares que
Tamarin necesita para cerrar las pruebas. También se prueban.

## Qué no modelan

- **M1** cubre la época 0 derivada de `S`, el paso a la época 1 por DH y **dos mensajes** de la
  cadena de envío. Una cadena sin límite hacía que la búsqueda no terminara, y dos mensajes bastan
  para la pregunta. No cubre las épocas siguientes, el desorden, las pérdidas, el relleno ni P5
  (recuperación frente a un atacante pasivo).
- **M2** solo cubre la regla del linaje, sin cadenas.
- **M2b** modela la regla nueva (tope y relevo) con un reloj por extremo. Sus trazas de recuperación
  (`recuperacion_tras_forja`, `w6_se_recupera`) **no terminaron** con ninguna heurística, y el
  auxiliar de su lema principal tampoco. Lo que muestran esas trazas lo cubren los tests de Kotlin
  (`RatchetPropertyTest` con relojes y linajes forjados, `ChatServiceTest`). Su primera versión
  encontró una reflexión: el modelo no llevaba el sentido en la clave de la época 0. Krypta sí lo
  lleva.
- Ninguno ve **condiciones de carrera** (H-0): en un modelo simbólico cada regla es atómica.
- Ninguno ve **la reutilización de nonce** (W-16): AES-GCM es aquí un cifrado ideal. Eso lo fija
  `RatchetCompromisoTest`.
- `S` es un valor fresco compartido, no X25519 de dos identidades. W-12 (la misma semilla para
  firmar y para X25519) queda fuera.

## Cómo correrlos

```sh
export PATH="$HOME/.local/bin:$PATH"
./verificar.sh            # todos los lemas; sale con error si alguno da otro resultado
tamarin-prover --prove krypta_m1.spthy          # M1 con derivaciones v3
tamarin-prover --prove -DV4 krypta_m1.spthy     # M1 con derivaciones v4
tamarin-prover --prove krypta_m2.spthy          # M2
tamarin-prover --prove=tope krypta_m2b.spthy    # M2b: lema a lema. Sin nombre se cuelga en el
                                                # auxiliar, que no cierra; las trazas van con -DTRAZAS
tamarin-prover interactive .                    # interfaz web, para ver las trazas de ataque
```

`verificar.sh` tarda menos de un minuto en la Mac de desarrollo.

## Instalación (macOS en Intel)

El release de Tamarin no trae binario para macOS x86_64, así que se compila. Versiones verificadas
el 11 oct 2026:

- **Tamarin 1.12.0**, commit `82780bbaf3328a45f624ddb41e51bf75425f851c`, compilado con
  **stack 3.11.1**, que baja su propio GHC 9.6.7 (LTS 22.44). La compilación ocupa unos 4 GB en
  `~/.stack` y tarda en torno a una hora.
- **Maude 3.5.1**, binario oficial `Maude-3.5.1-macos-x86_64.zip`, sha256
  `938eb55fbca44f7f8de71fdb8eeb41f8004ed0a440a3caa37702fee3fc3c79bf`.

```sh
# Maude: tiene que encontrar prelude.maude, que va junto al binario. Con un enlace simbólico en
# ~/.local/bin no lo encuentra (lo busca junto al enlace), así que va un script con MAUDE_LIB.
mkdir -p ~/.local/share ~/.local/bin
unzip Maude-3.5.1-macos-x86_64.zip -d ~/.local/share/maude-3.5.1
printf '#!/bin/sh\nMAUDE_LIB="$HOME/.local/share/maude-3.5.1" exec "$HOME/.local/share/maude-3.5.1/maude" "$@"\n' > ~/.local/bin/maude
chmod +x ~/.local/bin/maude ~/.local/share/maude-3.5.1/maude

# Tamarin
git clone --depth 1 --branch 1.12.0 https://github.com/tamarin-prover/tamarin-prover.git
cd tamarin-prover && stack setup && stack install --local-bin-path ~/.local/bin
tamarin-prover test       # 55 casos sin errores; el aviso de GraphViz solo afecta a la interfaz
```

## Lo que costó escribirlos

Dos trampas que conviene no repetir:

- **Una regla de revelación que se vuelve a producir a sí misma** (`[ St ] → [ Out(...), St ]`)
  hace que Tamarin encadene revelaciones sin fin: ni el lema más simple terminaba. El estado
  revelado pasa ahora a una variante que puede seguir enviando pero no revelarse otra vez. Una copia
  persistente tampoco sirve: dejaría revelar un estado ya sobrescrito y daría ataques falsos.
- El preprocesador (`#ifdef`) no funciona dentro de un `let`. La diferencia entre v3 y v4 va en
  `macros:` a nivel de teoría.
- **Un reloj como cadena de reglas** (`[ Reloj(t) ] → [ Reloj(t+1) ]`) hace que Tamarin retroceda por
  ella sin fin. En M2b la hora entra como un valor cualquiera y la monotonía va en una restricción.
- Con naturales, muchas comparaciones `<<` entre sumas desconocidas disparan los casos. Fijar
  valores concretos en los lemas de traza ayudó en `ejecutable`, no en las demás.
