# Salida a Google Play — estado y pendientes

Auditoría del 23 jul 2026 sobre el build de release real (`:app:bundleRelease`). Marca las
casillas según se vayan cerrando; lo que está hecho lleva la fecha de verificación.

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
