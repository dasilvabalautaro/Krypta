# Salida a Google Play — estado y pendientes

Auditoría del 23 jul 2026 sobre el build de release real (`:app:bundleRelease`). Marca las
casillas según se vayan cerrando; lo que está hecho lleva la fecha de verificación.

> ## ⛔ Bloqueante técnico abierto (2 sep 2026)
>
> **La entrega en segundo plano no funciona hoy en el móvil del autor.** Medido con la app
> viva y todas las condiciones a favor (servicio en primer plano, exención de batería, WiFi
> validado, pantalla encendida): **4 minutos con 0 conexiones a los nodos y el buzón sin
> retirar**; al abrir la app se recoge al instante. El "latido" de 2 min no lo rescató. Hay
> además **3 mensajes reales a un contacto sin entregar desde el 26 ago**, a punto de caducar
> por el TTL de 7 días del buzón.
>
> **Estado tras los arreglos del mismo día**: se corrigió una causa real y grave —el bucle WAN
> y el latido podían colgarse indefinidamente, y la cadena de alarmas del latido **moría del
> todo**— y ya se sostiene la conexión con el VPS primario, que antes no aparecía nunca. **Pero
> el fallo persiste en el TECNO**: sigue tirando los sockets en 2.º plano y suprimiendo las
> alarmas pese a la exención de batería. Lo que queda es **de nivel OEM (Transsion/HiOS)**, no
> de código.
>
> Detalle, evidencia, arreglos y lo que falta en **§13 de
> [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md)**. Antes de publicar hay que decidir si esto
> es **específico de Transsion** (la colaboradora, con otro modelo, sí recibía) o afecta a
> todos: lo primero es una nota de compatibilidad; lo segundo bloquea la salida.

## Registro de versiones generadas

**Anota aquí cada AAB que se genere, y marca cuál se subió**: Play rechaza un `versionCode`
repetido, y el historial de git dice qué versión se compiló, pero no cuál llegó a la tienda
— eso solo lo sabe quien la subió. Los huecos en la numeración sí están permitidos (solo
tiene que ser creciente).

| versionCode | versionName | Fecha        | Estado                                        |
|-------------|-------------|--------------|-----------------------------------------------|
| 3           | 1.2         | 23 jul 2026  | generado (¿subido?)                           |
| 4           | 1.3         | 31 jul 2026  | **subido a Play**                             |
| 5           | 1.4         | 7 ago 2026   | generado — añade el nodo primario de São Paulo |
| 6           | 1.5         | 13 ago 2026  | generado — fiabilidad de avisos, GIF animado, FLAG_SECURE |

> La 4 se subió **antes** de que el VPS de São Paulo entrara en `DEFAULT_BOOTSTRAP`, así que
> esa versión solo conoce los dos nodos domésticos. De ahí la 5: es lo que lleva el nodo
> primario a los usuarios nuevos.

> ⚠️ **Resolver antes de generar la siguiente**: la fila 6 dice "generado", pero el punto de
> `USE_FULL_SCREEN_INTENT` (más abajo) dice que **Play lo reclamó *al subir la 1.5***. Ambas
> cosas no pueden ser ciertas. Si la 6 llegó a Play, la próxima subida tiene que ser
> **versionCode 7**. Además, el AAB de la 6 que hay ahora en `app/build/outputs/` se regeneró
> el **2 sep** y **ya no coincide** con el binario del 13 ago (entremedio entró el
> `FLAG_SECURE` solo en el chat, y el arreglo de la vibración): no subas ese archivo creyendo
> que es el de agosto.

## Listo

- [x] **Keystore de producción**: `keystore.properties` (git-ignored) relleno y apuntando a
      `~/keystores/krypta/krypta.jks`; `hasReleaseKeystore` en
      [app/build.gradle.kts](../app/build.gradle.kts) selecciona la firma real y cae al
      keystore de depuración solo si falta. AAB firmado generado (~82 MB).
- [x] **R8 activo** con las reglas que preservan el puente gomobile (`go.**`,
      `chat.neto.krypta.bridge.**`), verificado en dispositivo.
- [x] **Símbolos nativos para Play**: `ndk { debugSymbolLevel = "FULL" }` (libgojni.so no
      pasa por el build nativo de AGP; sin esto Play no recibe símbolos).
- [x] **targetSdk 36** (por encima del mínimo exigido a apps nuevas).
- [x] **Páginas de 16 KB** (23 jul): `build-aar.sh` enlaza con
      `-extldflags=-Wl,-z,max-page-size=16384`; las 4 ABIs de `libgojni.so` a `0x4000` y
      `zipalign -c -P 16 -v 4` en verde. Requisito de Play para targetSdk ≥ 35 desde el
      1 nov 2025. **Al regenerar el AAR hay que volver a comprobarlo.**
- [x] **Sin copia automática de Google** (23 jul): `allowBackup="false"` + exclusiones
      explícitas en los dos XML. Antes la identidad Ed25519 y la base de mensajes subían al
      Drive del usuario. Verificado: `dumpsys package` ya no lista `ALLOW_BACKUP`.
- [x] **Política de privacidad redactada**: [politica-privacidad.html](politica-privacidad.html).
- [x] **FGS `specialUse`, lado app** (31 jul): el manifiesto declara
      `foregroundServiceType="specialUse|microphone"` con su
      `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` — verificado en
      el manifiesto **fusionado** del release, no solo en el fuente. El texto de la
      justificación nombra la tecnología y, sobre todo, el motivo por el que ningún otro tipo
      encaja (no hay push de terceros), que es el argumento que busca el revisor. La §6 de la
      política lo explica además en lenguaje de usuario. Falta solo el formulario de Console,
      abajo.
- [x] **Permisos explicados al usuario** (31 jul): la §6 de la política tiene una tabla
      permiso → para qué, y su fila "Servicio en primer plano / inicio automático /
      optimización de batería" cubre también `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
- [x] **Revisión previa a producción (2 sep 2026)** — lo que se comprobó de una pasada:
      **92 tests JVM** en verde (`testDebugUnitTest --rerun`), **Go en verde** en el puente y en
      `infra/node`, **AAB de release firmado con el keystore de producción** (verificado:
      `CN=Arturo Silva`, **no** el de depuración), **16 KB de página** en los 8 `.so` del AAB,
      manifiesto fusionado correcto (`allowBackup=false`, sin `debuggable`,
      `specialUse|microphone`, `USE_FULL_SCREEN_INTENT`), y en el móvil
      `USE_FULL_SCREEN_INTENT: granted=true`. **Los tres nodos vivos**: ciclo completo del buzón
      (depósito → retirada → remitente verificado) y `/krypta/wake/1.0.0` en los tres; VPS con
      **25 días de uptime sin reinicios** y latencia **p50 = 111 ms** desde La Paz.
- [x] **`lintRelease` en verde** (2 sep): fallaba con **2 errores `NewApi`** —
      `IncomingNotifier.vibrator()` usaba `VibratorManager` (**API 31**) con `minSdk 30`. Al ir
      dentro de un `runCatching` no rompía, pero en Android 11 **la llamada entrante no vibraba
      nunca**, y en silencio la vibración es el único aviso. Ahora reparte por versión y cae al
      `Vibrator` clásico por debajo de la 31.

## Requisitos nuevos de Play (anuncio del 26 ago 2026) — **verificado el 2 sep 2026**

Fuente: [Elevating app quality: Reducing memory usage and improving device migration](https://android-developers.googleblog.com/2026/08/app-quality-memory-optimization-secure-onboarding.html)
y la tabla oficial de [requisitos de calidad técnica](https://support.google.com/googleplay/android-developer/answer/17492799).
Entran en vigor en **feb 2027** (memoria, bitmaps, DEX) y **abr 2027** (Zero-Tap Sign-In); el
incumplimiento afecta a **visibilidad y capacidad de publicación**, no es un rechazo de revisión.

> ⚠️ **Cómo leer estas medidas**: Android vitals mide el **percentil 90 sobre 28 días de
> usuarios reales**. Lo de abajo son medidas puntuales en **un** móvil (TECNO KM5s), así que
> son indicativas, no equivalentes. Sirven porque el margen es de dos órdenes de magnitud; si
> estuviéramos cerca del umbral no bastarían.

- [x] **Memoria (RSS anónimo + swap)** — **cumple con muchísimo margen**. Umbral en gama de
      4 GB: **2 GB** en primer plano y **1 GB** en "servicios percibidos por el usuario" (que es
      nuestro caso: el FGS permanente). Medido: **48 MB anon + 35 MB swap = ~83 MB** en primer
      plano y **21 MB anon + 62 MB swap = ~83 MB** en segundo plano. Es **~4 %** del umbral que
      nos aplica pese a llevar el runtime de Go y un host libp2p siempre vivo.
- [x] **Memoria de bitmaps** — **cumple**. Umbral: **> 200 MB** en segundo plano / servicios.
      Medido: `Graphics` cae de **27,5 MB a 3,3 MB** al mandar la app a segundo plano (o sea,
      los bitmaps **sí** se liberan al dejar de ser visibles, que es justo lo que Google mira) y
      el *native heap* —donde viven los píxeles desde Android 8— queda en **7 MB**.
- [x] **Optimización de DEX** — **no nos aplica siquiera**. El requisito solo entra para apps
      con **> 10 MB de DEX**; el AAB de release tiene **3,03 MB** (`base/dex/classes.dex`), casi
      todo el peso de Krypta es `libgojni.so`, que **no es DEX**. Y aunque aplicara, la
      cobertura de ofuscación es del **87,2 %** (3813 de 4372 clases renombradas) frente al
      25 % exigido.
- [x] **Zero-Tap Sign-In / API Restore Credentials (abr 2027)** — **no aplica**: Krypta **no
      tiene inicio de sesión** de ningún tipo. No hay cuentas, ni servidor de autenticación, ni
      `CredentialManager`, ni `AccountManager`, ni dependencias de auth (comprobado por
      búsqueda en todo el código y en `libs.versions.toml`). La identidad es una clave Ed25519
      local. El bloqueo de la app usa `BiometricPrompt`, que es un desbloqueo local, **no** un
      sign-in. *Nota para la ficha*: nuestra historia de migración entre dispositivos es la
      exportación manual `.krbk` — deliberada (§7 de la política: la única copia la controla el
      usuario), y consecuencia directa de `allowBackup="false"`.

### Ajuste aplicado: el AAR de gomobile desactivaba R8 para todo el código propio

- [x] **Corregido el 2 sep 2026.** Al revisar el `mapping.txt` para la métrica de DEX salió que
      las clases de Krypta **no se ofuscaban, ni se optimizaban, ni se podaban** (ni los nombres
      de miembros: `_callSignals -> _callSignals`), mientras las librerías sí. La causa estaba
      en el `configuration.txt` de R8: el AAR `krypta-p2p` trae su propio **proguard de
      consumidor**, que gomobile genera del prefijo `-javapkg`, con

      ```
      -keep class go.** { *; }
      -keep class chat.neto.krypta.** { *; }
      ```

      El paquete bindeado es `chat.neto.krypta.bridge`, pero el prefijo acababa cubriendo **la
      app entera**. Y como las reglas de consumidor viven dentro del AAR, **no se ven** en
      [app/proguard-rules.pro](../app/proguard-rules.pro): solo aparecen auditando la config
      final de R8.

      **Arreglo**: [build-aar.sh](../native-bridge/libp2p/build-aar.sh) reescribe ahora ese
      `proguard.txt` dentro del AAR acotándolo a `chat.neto.krypta.bridge.**` (que es lo que de
      verdad resuelve el JNI, junto a `go.**` y las implementaciones de `go.Seq$Proxy`, ya
      declaradas en `proguard-rules.pro`). Se hace **en el script**, así que sobrevive a cada
      regeneración del AAR.

      **Efecto medido** en el AAB de release:

      | Métrica | Antes | Después |
      |---|---|---|
      | Ofuscación, global | 87,2 % (3813/4372) | **98,2 %** (3945/4017) |
      | Ofuscación, código propio | 12,8 % (75/586) | **92,7 %** (305/329) |
      | Tamaño de DEX | 3,03 MB | **2,73 MB** (−10 %) |

      (Las 355 clases que desaparecen del mapping son código que el shrinker ya puede podar.)

      **Verificado en ejecución, que es donde esto rompe sin dar error de compilación.** No se
      podía instalar el release sobre el móvil del autor —otra firma ⇒ exigiría desinstalar y
      **eso borra la identidad Ed25519**— y las imágenes del emulador están incompletas. Así que
      se compiló el release con `applicationIdSuffix = ".smoke"` (parche temporal, ya revertido)
      y se instaló **junto** al build normal, sin tocarlo. Resultado con el código propio
      ofuscado al 92,7 %: la app arranca sin crash, muestra un **PeerID válido**
      (`12D3KooWAAuWeWae4gVphCp4m7Ho6JgQ1STqcSHGn5juLfd1GhtY` — o sea el puente JNI resuelve),
      abre **conexiones reales al VPS primario `216.128.169.83:4001` y a Cloudflare**, y la
      pantalla principal dice **"conectado"**. Las 16 clases de `chat.neto.krypta.bridge` siguen
      con su nombre original (0 renombradas). Después se desinstaló solo el paquete `.smoke`.

## Bloqueantes de ficha (trámite, no código)

- [ ] **Hospedar la política** en una URL pública (p. ej. `krypta.neto.chat/privacidad`) —
      Play exige URL, no un archivo del repo.
- [ ] **Formulario de Seguridad de los Datos** coherente con la política (sin recogida, sin
      terceros, E2EE en tránsito y en el buzón).
- [ ] **Clasificación de contenido** (cuestionario) y público objetivo.
- [ ] **Cumplimiento de exportación de cifrado** (la pregunta que hace Play por usar E2EE).
- [ ] **Formulario del FGS `specialUse` en Play Console** (*Contenido de la app*). Es un
      trámite **aparte** de lo que ya está en el código: el
      `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` del manifiesto no lo rellena. Hay que escribir ahí
      por qué ningún otro tipo de FGS sirve, y **lo revisa una persona**, que puede denegarlo
      si cree que encaja otro tipo. El texto ya está redactado: se copia del manifiesto y de
      la §6 de la política. Conviene tener plan B por si lo deniegan.
      *(Marcar como hecho cuando se envíe — a fecha de 31 jul no consta si se hizo.)*
- [ ] **Declarar `USE_FULL_SCREEN_INTENT` en Console** (nuevo en la 1.5). **Play lo reclamó al
      subir la 1.5** ("Debes informarnos si tu app usa permisos de intent de pantalla
      completa"), así que es bloqueante, no opcional. Está en *Contenido de la app → Permiso de
      intent de pantalla completa*. Desde Android 14 el permiso solo se concede por defecto a
      apps cuya función principal son **llamadas o alarmas**. Verificado en el TECNO:
      `dumpsys package` lo da como `granted=true` sin intervención del usuario.

      **Respuestas del formulario** — ¿usa el permiso?: **Sí**. Función principal:
      **llamadas** (no alarmas, no "otro"). Descripción, lista para pegar:

      > Krypta es una app de mensajería descentralizada con llamadas de voz y vídeo cifradas
      > de extremo a extremo.
      >
      > El permiso se usa en un único punto: mostrar la pantalla de llamada entrante cuando el
      > teléfono está bloqueado o con la pantalla apagada. La notificación es de categoría
      > CATEGORY_CALL, usa Notification.CallStyle.forIncomingCall e incluye las acciones de
      > contestar y rechazar, igual que la app de teléfono del sistema.
      >
      > Krypta no utiliza servicios de notificaciones push de terceros: mantiene su propia
      > conexión cifrada para recibir las llamadas. Sin el intent de pantalla completa, una
      > llamada entrante con el móvil bloqueado solo dejaría un aviso discreto en la bandeja y
      > el usuario perdería la llamada.
      >
      > No se usa para ningún otro fin: no hay anuncios, ni promociones, ni avisos que no sean
      > una llamada entrante en curso.

      La declaración es **verificable en el código**: hay un solo `setFullScreenIntent` en todo
      el proyecto (`KryptaNotifications.notifyIncomingCall`), y solo se dispara con la llamada
      en `RINGING` (`IncomingNotifier.onCallState`). Si alguna vez se añade un segundo uso, esta
      declaración deja de ser cierta y hay que rehacerla.

      Lo que ayuda a que la aprueben: (a) que la **ficha de la tienda mencione las llamadas**
      de voz y vídeo — el revisor comprueba que la función principal declarada existe; (b)
      tener listo un **vídeo de demostración** por si lo piden (móvil bloqueado → entra la
      llamada → la pantalla se enciende con contestar/rechazar); ojo, ese escenario sigue
      **sin probarse en vivo** (§12.5 de [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md)):
      probarlo antes de grabar. Lo revisa una persona, así que puede tardar.

      **Plan B si lo deniegan**: quitar el permiso y el `setFullScreenIntent`. La llamada
      seguiría llegando con notificación, timbre y vibración; se pierde solo que tome la
      pantalla completa con el móvil bloqueado.
- [ ] **Justificar `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` en Console** (entrega de mensajes en
      2.º plano sin push de terceros). La política ya lo explica al usuario; esto es la
      declaración ante Play.
- [ ] **Prueba cerrada previa**: si la cuenta de desarrollador es personal y posterior a
      nov 2023, Play pide 12 testers durante 14 días antes de habilitar producción. Son dos
      semanas de calendario: conviene arrancarla cuanto antes.
- [ ] **Assets**: icono 512, gráfico destacado 1024×500, capturas, descripción corta y larga
      (reciclables de [MANUAL.md](MANUAL.md)).
      ⚠️ **La captura del chat no se puede hacer con el gesto del móvil**: desde la 1.5 la
      pantalla de chat lleva `FLAG_SECURE` y el sistema devuelve negro. Usa **⋮ → "Capturar
      pantalla"** dentro del chat (guarda en `Galería › Krypta`). El resto de pantallas (lista,
      ajustes, ayuda, llamada) se capturan con el gesto normal.

## Producto / política de contenido

- [ ] **Bloquear contacto**. La política de contenido generado por usuarios pide bloqueo o
      denuncia en apps de comunicación. Hoy solo hay "Eliminar contacto". Juega a favor que
      `ChatService.onReceived` descarta a quien no es contacto (nadie desconocido puede
      escribir), y conviene decirlo en la ficha, pero un "Bloquear" explícito evita la
      discusión con el revisor.
- [ ] **Textos en `strings.xml`**: hoy todo el UI está hardcodeado en Kotlin y solo en
      español. No bloquea publicar; bloquea traducir.

## Infraestructura (el riesgo real, no lo mira Play)

- [x] **VPS como nodo primario** (7 ago): **contratado y desplegado** — Vultr São Paulo,
      `216.128.169.83`, PeerID `12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5`, ya
      como primera línea de `Libp2pNode.DEFAULT_BOOTSTRAP` por TCP directo (sin Cloudflare).
      El Mac y el PC Windows siguen en la lista de **respaldo**: el bridge retira y escucha
      de todos los nodos, así que la caída de cualquiera —incluido el VPS— no corta la
      entrega. Latencia **p50 = 107 ms** desde La Paz (antes 146–163 ms vía Cloudflare).
      Runbook y pendientes de la máquina en la sección "Nodo primario en un VPS Linux" de
      [../infra/node/README.md](../infra/node/README.md).
- [x] **Copia de `node.key` del VPS fuera de la máquina** (8 ago): en
      `~/keystores/krypta/krypta-node-saopaulo.key` (permisos `600`, fuera del repo, junto al
      keystore de Android). **Verificada, no solo copiada**: el SHA-256 coincide con el del
      VPS y, al deserializarla con `crypto.UnmarshalPrivateKey`, deriva el PeerID real
      `12D3KooWBwcbXveKDSf4LrH9DYnwMDAyagkzh2uPYZyWkeoVMuk5` — o sea que sirve para resucitar
      el nodo con la misma identidad. Importaba porque si esa clave se pierde el nodo cambia
      de PeerID y **los móviles ya instalados dejan de encontrarlo**: habría que publicar otra
      versión de la app. Para restaurar: copiarla a `/var/lib/krypta/node.key` (dueño
      `krypta:krypta`, permisos `600`) antes de arrancar el servicio.
- [ ] **Relay abierto sin límites**: [infra/node/main.go](../infra/node/main.go) usa
      `EnableRelayService(relayv2.WithInfiniteLimits())` — necesario para que no se cortaran
      las llamadas (el tope por defecto es 128 KiB / 2 min), pero es ancho de banda gratis
      para **cualquier** nodo libp2p de internet, no solo para Krypta. Poner topes generosos
      pero finitos y/o una ACL.
- [ ] **Depósito en buzón sin restricción de origen**: hay cuota por destinatario (200 msgs /
      5 MiB / TTL 7 días) pero cualquiera puede depositar a cualquiera → vector de spam.
- [ ] **Monitorización/alertas** de los nodos (hoy no hay).

## Pruebas en vivo que no publicaría sin cerrar

Ver [PRUEBAS-PENDIENTES.md](PRUEBAS-PENDIENTES.md):

- [ ] §2 Verificación anti-MITM real entre dos móviles (la del 23 jul no vale: se comparó
      contra el propio PeerID).
- [ ] §3 Reinicio del móvil, cambio de red y persistencia > 6 h.
- [ ] Failover multinodo (apagar el nodo del Mac y comprobar entrega por `krypta2`).
