# Pruebas pendientes de verificación en vivo (2 móviles)

> Registro vivo de lo que está **implementado + testeado (unit/sonda)** pero aún **no
> confirmado con dos móviles reales**. La colaboradora que presta el segundo móvil no
> siempre está disponible; aquí quedan los pasos exactos para ejecutarlos cuando se pueda.
>
> **Rutina de despliegue:** el móvil del autor (TECNO KM5s, USB) se mantiene siempre con el
> último build (`./gradlew :app:installDebug`). El APK para el segundo móvil se deja en
> `~/Desktop/krypta-arm64-debug.apk` (`./gradlew :app:assembleDebug -PslimAbi` + copia) y lo
> comparte el autor. **Ambos móviles deben tener la misma versión** para cada prueba.
>
> Última actualización: **10 sep 2026 (tarde)** — **pruebas con dos móviles hechas**, ambos con
> el build de ese día (nodos São Paulo + Dallas, `wss/443`, ratchet encendido):
>
> - ✅ **§16 ratchet, conversación normal**: intercambio real en los dos sentidos, sin pérdidas
>   (reportado por el autor; el Diagnóstico del TECNO registra `← mensaje` 08:34:24 y 08:35:58 y
>   `→ enviado` 08:36:13 contra el mismo contacto). **Los escenarios concretos de §16 siguen sin
>   hacer**: pérdida de estado, reentrega del buzón, archivo grande cruzando época y llamada.
> - ✅ **§13 entrega en 2.º plano**: llegó **y sonó** con la app en segundo plano, que es lo que
>   estaba roto desde el 2 sep. Es **una muestra** en el TECNO con el build nuevo, no una
>   regresión cerrada: repetirla antes de publicar (§13, prueba de no regresión).
> - ⚠️ Quedan **4 sobres sin recoger** en los nodos (3 en São Paulo, 1 en Dallas) para dos
>   PeerID que **no son** el TECNO ni el contacto de la prueba. Caducan a los 7 días; ver §12.
>
> Antes, el 10 sep por la mañana: §16 añadida al encender el ratchet. Y el 2 sep: auditoría
> previa a preparar la versión de producción.
>
> (La videollamada §11 quedó **VERIFICADA** el 16 jul — funcionó bien.)
>
> **APK del 16 jul** (`~/Desktop/krypta-arm64-debug.apk`): todo lo del 12 jul (rediseño M3,
> 7d parcial, multi-nodo cliente, respaldo de identidad) + **bloqueo de acceso a la app**
> (Ajustes → "Bloqueo de la app": huella/cara/PIN del sistema, período de gracia
> configurable). Sin cambio de protocolo ni de DB. Nota histórica: el 12 jul de madrugada
> pudo quedarle a la colaboradora una llamada perdida accidental (prueba de proximidad,
> ~30 s, colgada).

## Cómo leer los diagnósticos en el TECNO
El limitador OEM de Transsion **silencia el logcat de la app**. Para ver el panel de
Diagnóstico in-app sin logcat:
```
adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"'
```

---

## 1. UX de notificaciones (app cerrada) — **VERIFICADO (4 jul, reporte del autor)**
El autor confirmó (4 jul, tarde) que las pruebas de **notificación funcionaron** entre los
dos móviles junto con las de imagen. Se deja la checklist por si algún matiz (deep-link,
badge, scroll) no se probó explícitamente; si todo estaba bien, mover la sección al histórico.
Cambios del 4 jul (raíz: `KryptaNotifications`, deep-link, cancelación al leer, auto-scroll).
Verificado en un solo móvil: navegación por deep-link (`am start --es krypta.open_contact`)
y config de canales (`messages_v2` importance=4/vibra=true; `service_v2` badge=false). Falta
la prueba real de recepción entre dos móviles:

1. Instalar el **mismo** APK en ambos; añadirse mutuamente como contacto.
2. Cerrar del todo (swipe en recientes) el móvil **receptor**.
3. Enviar un mensaje desde el emisor.
> **Notas (4 jul):**
> - En un móvil conectado por USB el aviso podía no aparecer (app "casi en primer plano");
>   se cambió la detección de visibilidad a un observador en el hilo principal. Probar
>   **DESCONECTADO**, app en 2.º plano, la otra persona enviando.
> - **TECNO/Transsion (HiOS) congela la app a batería** aunque esté en la whitelist de Doze.
>   Mitigación en código: **WifiLock** en el FGS (mantiene el WiFi despierto con pantalla
>   apagada). **Ajustes manuales del móvil (imprescindibles en TECNO)**, vía el botón nuevo
>   "⚙ Ajustes de recepción en 2.º plano": (1) **Inicio automático/Autostart** = ON;
>   (2) **Batería → Sin restricciones**; (3) **Apps protegidas** (Phone Master); (4) bloquear
>   la app en Recientes; (5) **WiFi → mantener activo con pantalla apagada = Siempre**.
> - Confirmado: la colaboradora (otro modelo) **sí recibe**; el fallo es específico del TECNO.
> - **Diagnóstico (4 jul)**: en el TECNO la notificación fija del servicio **sí se ve** (proceso
>   vivo) y el mensaje **aparece al instante al abrir** (se recibió en 2.º plano) → no es freeze,
>   es que el TECNO **suspende la RED** en 2.º plano; el mensaje solo se descarga al abrir y para
>   entonces el aviso se omite (UI visible). Arreglo: **HeartbeatReceiver** (AlarmManager cada
>   ~2 min, `setAndAllowWhileIdle`) que despierta la red y retira el buzón → aviso en ≤~2 min.
>   **Pendiente probar en el TECNO desconectado**: con la app en 2.º plano, que ella envíe y ver
>   si el aviso llega en ≤2 min. Si aún no, el siguiente paso es UnifiedPush (push sin Google).
> - **RESUELTO EL MISTERIO (4 jul)**: una notificación de prueba por el canal real de mensajes
>   (`mImportance=4`) **SÍ aparece en la cortina del TECNO**, pero **sin sonido ni banner** —
>   HiOS silencia las notificaciones de apps de terceros aunque la app pida prioridad alta. El
>   código y el canal funcionan; el "no llega nada" era en realidad "llega en silencio". **Fix =
>   ajuste del móvil**: Ajustes → Notificaciones → Krypta → "Mensajes" → activar Sonido + Banner/
>   Flotante + Pantalla de bloqueo (o en Phone Master → gestión de notificaciones). Se añadió un
>   botón **"🔔 Probar aviso"** en la app para que cada usuario ajuste esto hasta que suene.

4. Verificar en el receptor, uno a uno:
   - [ ] **Suena y vibra** el aviso (heads-up), no solo el conteo silencioso.
   - [ ] Tocar la notificación **abre directo esa conversación** (no la lista).
   - [ ] El chat aparece **con el scroll abajo**, mostrando el mensaje nuevo.
   - [ ] Al abrir/leer, **el conteo del icono se limpia** (probar entrando por el icono de
     la app, no solo por la notificación).
   - [ ] Enviar varios mensajes seguidos: el scroll sigue al último.

## 2. Verificación de identidad anti-MITM (número + QR) — **PENDIENTE**
Verificado en un móvil: el diálogo muestra 60 dígitos, el **QR se genera** (captura ok), el
botón "Escanear" abre la cámara (permiso + `CaptureActivity` de ZXing), "Coinciden,
verificar" marca el contacto y aparece la insignia de escudo (persiste). El parse del payload
QR está cubierto por `QrCodeTest`. Falta confirmar el flujo real entre dos móviles:

1. En ambos móviles, abrir el chat del otro → botón **escudo** → "Verificar identidad".
2. **Número de seguridad**: - [ ] es **idéntico** en los dos móviles.
3. **QR**: - [ ] "Usar QR" en ambos; A escanea el QR de B → toast **"✓ Identidad verificada"**
   y aparece la insignia; B escanea el de A → igual. (Cada uno muestra su QR y escanea el otro.)
4. - [ ] (MITM negativo) Escanear el QR de un tercero / un contacto con PeerID distinto → toast
     **"⚠ El QR NO coincide (posible suplantación)"** y **no** marca verificado.
5. - [ ] Escanear un QR cualquiera (no Krypta) → toast "Ese QR no es de Krypta".

## 3. Endurecimiento de segundo plano — **PARCIAL**
Verificado en un móvil: FGS `specialUse` (`types=0x40000000`), exención de batería en la
whitelist de deviceidle. Falta:

1. - [ ] **Reinicio del móvil**: reiniciar el receptor, **no abrir la app**, enviarle un
     mensaje desde el emisor → debe llegar (lo rearma `BootReceiver`).
2. - [ ] **Cambio de red**: con la app cerrada, pasar el receptor de WiFi a datos (o al revés)
     y enviarle un mensaje → debe llegar rápido (lo dispara `kickWan()` por el NetworkCallback).
3. - [ ] **Persistencia larga**: dejar el receptor con la app cerrada varias horas (>6 h para
     descartar el corte de FGS de Android 15) y comprobar que sigue recibiendo.

## 4. Marca de leído (READ) entre 2 móviles — **VERIFICADO (5 jul)**
Sesión real con la colaboradora: en la Room DB del autor los mensajes salientes figuran
**READ** tras abrirse el chat en el otro móvil (rastro extraído por adb el 5 jul). ✅
El punto 5 (acuse por buzón con B cerrado) no se aisló explícitamente, pero el ciclo
normal funciona. Checklist original:

Verificado en 1 móvil: el sobre (`MessageEnvelope`) viaja bien de punta a punta (texto
correcto tras enviar→buzón→recibir), los mensajes legado siguen legibles, y los estados
salen en español. Falta el ciclo real del acuse entre dos personas:

1. Ambos con el **mismo** APK, contactos añadidos.
2. A envía a B. En A el mensaje sale **"enviado"**.
3. B **abre el chat** con A.
4. - [ ] En A, ese mensaje pasa a **"leído"** (el acuse llegó directo o por buzón).
5. - [ ] Con B cerrado, A envía; luego B abre → el acuse por buzón debe llegar a A al conectar.
6. Nota: el contacto de prueba "SinVerificar" apunta al **propio** PeerID del autor (bucle a
   sí mismo); sirvió para probar el ida-y-vuelta del sobre, pero no es un caso real.

## 5. Reintento de FAILED — **VERIFICADO en 1 móvil (4 jul)**
Con la red cortada, el envío queda **"No enviado · toca para reintentar"**; al restaurar la
red y tocar la burbuja, pasa a **"enviado"**. ✅

## 6. Archivos (v1 troceados) entre 2 móviles — **VERIFICADO (5 jul), fragilidad v1 sigue**
Sesión del 5 jul: dos PDFs multi-trozo llegaron al TECNO y se abren (112 KB ≈ 3 trozos y
72 KB ≈ 2 trozos, en `files/krypta_files/`, mensajes DELIVERED en Room). El autor reporta
"archivos ✅" en ambos sentidos. **La fragilidad v1 quedó ARREGLADA el mismo 5 jul (v2)**: el 5 jul
se perdió 1 de 3 notas de voz (ver §7) por el mismo mecanismo que el .bin del 4 jul. Arreglo
en tres capas: (a) `DiskFileStore` hace **staging de trozos en disco** (sobreviven a la
muerte del proceso, reentregas idempotentes; `DiskFileStoreTest`); (b) el buzón es
**ack-tras-persistir** (el sobre solo se borra del nodo cuando el receptor lo persistió;
Go `TestMailboxRedeliverUnacked` + `ChatServiceTest`); (c) los sobres ya no pasan por un
buffer de 64 que **descartaba trozos en ráfaga** (probable causa real de la nota perdida).
Verificado en vivo en el TECNO (depósito → wake → "buzón: recogido"). **Prueba 2 móviles
pendiente**: reenviar un archivo de varios trozos matando la app receptora a mitad
(desliza en recientes al ver "1 de N") → al reabrir debe completarse solo.

<!-- Historial 4 jul: -->

Verificado en vivo: un **PDF de 84 KB (2 trozos) llegó y fue leído** por la colaboradora ✅.
Pero un **.bin de 150 KB (4 trozos) se envió (SENT en el emisor) y nunca apareció en el
receptor** — algún trozo se perdió en recepción y el reensamblado quedó incompleto. Fragilidad
conocida de v1: el receptor acumula los trozos **en memoria** (`DiskFileStore`) y el buzón
**borra los sobres ya ack'd**; si el proceso receptor muere (o pierde un trozo) a mitad de
transferencia, el archivo es irrecuperable y no hay señal de error. Candidatos de arreglo
(v2): staging de trozos en disco, y/o no ack'ar un trozo hasta persistirlo.

1. - [ ] Repetir el envío de un archivo de varios trozos (>96 KB) con el receptor **abierto**
     y comprobar que reensambla. Si vuelve a perderse, mirar el Diagnóstico del receptor.
2. - [ ] Archivo mediano (1–5 MB): comprueba que reensambla bien. >8 MB se rechaza (límite v1);
     offline (buzón) el límite práctico es ~5 MB por la cuota del buzón. Grandes = v2 (staging).
3. - [ ] Tocar la burbuja en el receptor **abre** el archivo con el visor correspondiente.

## 7. Notas de voz (v1) entre 2 móviles — **RESUELTO EL MISTERIO (6 jul)**
**Por qué el autor "no enviaba": mantenía PULSADO el micro (costumbre WhatsApp) y el
`TooltipBox` del botón se comía la pulsación larga mostrando el tooltip — nunca arrancaba
la grabación** (reproducido en vivo por adb: el toque corto grababa y enviaba perfecto —
258 KB al contacto propio — y la pulsación larga no hacía nada). **Arreglado el 6 jul**:
el micro usa `combinedClickable` (sin tooltip) y **toque corto Y pulsación mantenida
arrancan la grabación** igual (luego se toca "Enviar" en la barra). Instalado en el TECNO
y refrescado `~/Desktop/krypta-arm64-debug.apk`. Nota: sigue sin ser "suelta para enviar"
(estilo WhatsApp completo); si se quiere, es trabajo aparte.
Recepción confirmada por el autor (6 jul): las notas de Lucia llegan todas y **la
notificación suena con la app en 2.º plano y pantalla apagada** ✅. Falta solo confirmar
A→B con el autor enviando (ahora que puede) y una nota larga multi-trozo (punto 5).

<!-- Historial 5 jul: -->
**PARCIAL (5 jul)**
Sesión del 5 jul: la colaboradora envió **3 notas de voz; llegaron 2** (11:22 y 11:25,
`.m4a` presentes en disco y reproducibles); la tercera se perdió por la fragilidad v1 de
trozos (§6) sin ninguna señal de error. El **autor no pudo enviar**: en su TECNO el envío
falló **en silencio** — no quedó archivo en `krypta_files/sent/` ni mensaje propio en Room.
Reproducción por adb el mismo 5 jul: la grabadora **sí funciona** (botón 🎤 → "Grabando…",
archivo crece, Cancelar limpia), así que fue un fallo puntual (micro ocupado justo tras la
llamada cortada, u otro rechazo de MediaRecorder). **Bug UX CORREGIDO (5 jul, mismo día)**:
ahora `recorder.start()` fallido y `recorder.stop()` nulo muestran toast, y el chat también
muestra por toast los errores del ViewModel (imagen/archivo/nota que antes eran invisibles).
Y la **pérdida de trozos quedó arreglada** también el 5 jul (v2 del §6: staging en disco +
ack-tras-persistir): las notas de voz ya no deberían perderse. Instalado en el TECNO y en
`~/Desktop/krypta-arm64-debug.apk` (build de la tarde, con AAR nuevo). Reintentar el envío
A→B (pasos abajo) con el APK nuevo en ambos, incluida una tanda de 3+ notas seguidas.

<!-- Historial 4 jul: -->

Verificado en 1 móvil (TECNO): botón 🎤 (sustituye a "Enviar" cuando no hay texto) → pide el
permiso de micro → barra "Grabando… m:ss" → **Cancelar** borra el archivo y vuelve al chat.
Viaja como archivo troceado (mime `audio/mp4`, AAC mono 48 kbps); el emisor **conserva copia**
(`filesDir/krypta_files/sent/`) así que su burbuja también es reproducible. Tests:
`sendFile with localPath…` en `ChatServiceTest`. Falta el ciclo real:

1. Ambos con el mismo APK (el de hoy o posterior).
2. A mantiene el chat sin texto → toca 🎤 → habla unos segundos → **Enviar**.
3. - [ ] En A la burbuja de audio aparece y **se reproduce** (play/pausa + progreso + duración).
4. - [ ] En B llega la burbuja de audio y **se reproduce**. Notificación **"🎤 Nota de voz"**
     si estaba cerrado.
5. - [ ] Nota larga (1–2 min): llega y reproduce entera (multi-trozo).

## 8. Sonda de latencia de llamadas en datos móviles (Fase 7a) — **VERIFICADO (5 jul): GATE SUPERADO**
El autor corrió "📞 Latencia" con datos móviles (5 jul, 14:37 en el Diagnóstico):
`RTT nodo: n=50/50 min=151ms p50=180ms p95=220ms max=295ms`, 0 pérdidas, Opus enc
disponible. **p95 = 220 ms ≤ 300 ms → gate de celular superado.** Falta solo repetirla en
el móvil de la colaboradora (no bloqueante).

<!-- Historial 4 jul: -->

El gate de llamadas (Opción A) está **superado en WiFi** (TECNO→nodo vía Cloudflare:
p50≈146–163 ms, p95≤173 ms, 0 pérdidas; Opus enc disponible — ver el PLAN, Fase 7a). Falta
la medición en **celular**: el 4 jul la SIM del autor estaba sin plan de datos (Tigo en
CAPTIVE_PORTAL → la operadora resetea toda conexión; no es un fallo de Krypta).

1. Con datos móviles activos, tocar **"📞 Latencia"** (pantalla de contactos).
2. - [ ] El Diagnóstico muestra `📞 RTT nodo: n=50/50 … p95=…` con p95 ≲ 300 ms.
3. - [ ] Repetir en el móvil de la colaboradora (ideal: uno en WiFi y otro en datos).

## 9. Llamada de voz entre 2 móviles (Fase 7b) — **VERIFICADO (6 jul) tras el fix del relay**
Reporte del autor (6 jul), con el nodo ya redesplegado con `WithInfiniteLimits`: **la
llamada funciona bien, sin eco, NO se corta**, y **la notificación de llamada entrante
llega con el móvil "apagado"** (app en 2.º plano / pantalla apagada) ✅. Quedan como
matices no bloqueantes: probar rechazo/perdida/busy (puntos 5-7 de la checklist) y apuntar
si la conexión sube a directa (DCUtR) o va por relay.

<!-- Historial 5 jul: -->
**PARCIAL (5 jul): funcionaba pero SE CORTABA A ~20 s**
Primera llamada real (5 jul): **conecta, sin eco, sonido claro** en ambos sentidos ✅…
pero **se corta a los ~20 segundos**. Causa raíz identificada en los rastros + código: la
llamada va por **Circuit Relay v2** y el nodo usa `libp2p.EnableRelayService()` con los
**límites por defecto de go-libp2p: 128 KiB de datos o 2 min por conexión relayada**. Al
bitrate de la llamada (Opus 24 kbps + AES-GCM por frame + framing + overhead libp2p ≈
5–6 KB/s por sentido), los 128 KiB se agotan en ~20–25 s y el relay **resetea la conexión**
→ `receiveFrame()` devuelve null → "finalizada". (Coincide el appop RECORD_AUDIO del TECNO:
uso de 21 s.) Los mensajes/archivos nunca lo pisan porque son transferencias cortas.
**Fix APLICADO (5 jul)**: `libp2p.EnableRelayService(relayv2.WithInfiniteLimits())` en
`infra/node/main.go` (este nodo ES el relay de Krypta y el tráfico es E2EE); binario nuevo
compilado en `infra/node/dist/krypta-node-catalina` (verificado `minos 10.13`). **Falta:
copiarlo a la Mac Catalina y redesplegar** (`deploy-catalina.sh`, ver README del nodo) y
**repetir la llamada** — debe durar sin corte. Aun con fix conviene vigilar si DCUtR sube la
llamada a directa (menos latencia y sin pasar por Cloudflare). Checklist completa abajo.

<!-- Historial 4 jul: -->

El MVP de llamadas está implementado y probado en JVM (`CallServiceTest`) + smoke en el
TECNO (llamar → "Llamando…" → colgar → "cancelada"). **Imprescindible: ambos móviles con el
APK de hoy o posterior** — un invite a un APK viejo se muestra allí como texto crudo.

1. A abre el chat de B → botón **📞** (junto al escudo) → concede micro si lo pide.
2. - [ ] En B **suena el timbre** y aparece la pantalla "Te está llamando" (o la notificación
     "📞 Llamada entrante" si la app estaba cerrada; tocarla abre la pantalla).
3. - [ ] B toca **Aceptar** → ambos pasan a "Conectando…" y luego al cronómetro. **Se oye la
     voz en ambos sentidos** (probar auricular y 🔊 altavoz; el mute silencia).
4. - [ ] Colgar en cualquiera termina en ambos ("finalizada") y se vuelve al chat solo.
5. - [ ] B **rechaza** una llamada → A ve "rechazada".
6. - [ ] A llama y **cuelga antes de que B conteste** → en B para el timbre y queda la fila
     "📞 Llamada perdida" (con notificación si estaba cerrado).
7. - [ ] A llama con **B sin abrir la app** ≥1 min (invite rancio vía buzón) → B no timbra
     tarde: fila de llamada perdida al conectar.
8. - [ ] Calidad: latencia percibida, eco (el AEC es del chip), cortes. Apuntar si la conexión
     fue directa (DCUtR) o relayed (Diagnóstico). Si el audio va a tirones vía relay, subir el
     colchón (~120 ms) o bajar bitrate — apuntarlo aquí.
9. - [ ] **(7d, APK 12 jul) Proximidad**: en llamada al oído (sin altavoz) la pantalla se
     apaga al acercarla y se reenciende al alejarla; la mejilla no cuelga ni silencia. Con
     🔊 altavoz o vídeo encendido NO debe apagarse.
10. - [ ] **(7d, APK 12 jul) Micro en 2.º plano**: con la llamada ACTIVA, apagar pantalla
     (proximidad) o pasar la app a 2.º plano un momento → la voz sigue fluyendo (el FGS
     declara tipo `microphone`; comprobable con
     `adb shell dumpsys activity services chat.neto.krypta | grep -i foreground`).

## 11. Videollamada (Fase 7c) entre 2 móviles — **VERIFICADO (16 jul)**
Reporte del autor (16 jul): la videollamada real entre los dos móviles **funcionó bien**
con los ajustes del 6 jul (320×240 / 12 fps / 250 kbps, descarte por grupos GOP,
`keepScreenOn`), en red **mixta** (un móvil en WiFi y el otro en datos móviles) ✅. El vídeo es un **toggle dentro de la llamada de voz**: la llamada
arranca como audio y cualquiera enciende su cámara con **🎥 Vídeo** (canales
independientes: si el vídeo falla, la voz sigue). Cubierto por `TestVideoStreamEcho` (Go)
y `CallServiceTest` (vídeo E2EE bidireccional + descarte por congestión). Si en llamadas
futuras aparecieran entrecortes: anotar si era WiFi o datos y bajar a 8 fps / 180 kbps
(la adaptación automática de bitrate es 7d).

<!-- Historial 6 jul: -->
**1.ª prueba en vivo (6 jul): entrecortes** — imagen pixelada/congelada y la VOZ también se
congelaba (el vídeo a 640×480/500kbps saturaba el túnel wss relayed que comparte con el
audio, y el descarte de frames sueltos corrompía el H.264); y **la pantalla se apagaba y la
llamada se cerraba** (el OEM suspende la red con pantalla apagada). **Ajustes aplicados el
mismo día**: vídeo a **320×240 / 12 fps / 250 kbps, keyframe cada 1 s**; descarte **por
grupos** bajo congestión (si la red no da abasto se tira todo hasta el próximo keyframe →
congela limpio y se recompone, en vez de pixelar; y la cola corta deja respirar al audio);
y **la pantalla se mantiene encendida durante la llamada** (`keepScreenOn`).

Checklist original (por si algún matiz — cambio de cámara §11.7, controles/PiP §11.8,
orientación — no se probó explícitamente en la sesión del 16 jul):

1. A llama a B (voz normal); B acepta → cronómetro.
2. A toca **🎥 Vídeo** → concede el permiso de cámara si lo pide.
   - [ ] En A aparece su PiP ("Enviando tu cámara…" hasta que B encienda la suya).
   - [ ] En B aparece el vídeo de A a pantalla completa (fondo negro) con el nombre arriba.
3. B toca **🎥 Vídeo** también.
   - [ ] Ambos se ven (remoto grande + propio en PiP). Apuntar latencia percibida y fluidez.
   - [ ] La voz sigue clara mientras hay vídeo (el audio va por su propio canal).
4. A toca **🎥 Apagar**.
   - [ ] En B desaparece el vídeo de A (vuelve a "pantalla de voz" si B no envía); la
     llamada sigue.
5. Colgar en cualquiera termina limpio (sin vídeo colgado ni cámara encendida).
6. Anotar si la imagen sale girada (la rotación anunciada es la del sensor; el ajuste
   fino de orientación/espejo queda pendiente de 7d) y si hay tirones vía relay (bajar
   bitrate si hace falta).
7. - [ ] **(7d, APK 12 jul) Cambio de cámara**: con tu vídeo encendido, tocar **Cámara**
     (botón redondo) → pasa a la trasera (y de vuelta). En el receptor la imagen se
     recompone en ~1 s (nuevo SPS/PPS en banda re-crea su decoder) y la voz no se corta.
8. - [ ] **(UI-4, APK 12 jul) Controles en vídeo**: durante el vídeo, un toque en la
     pantalla oculta/muestra los controles (se auto-ocultan a los 4 s); el **PiP propio se
     arrastra** con el dedo y queda dentro de la pantalla.

## 12. Fiabilidad de avisos tras la auditoría del 13 ago — **PENDIENTE**
Los cinco fallos y sus arreglos están en CLAUDE.md (bloque "Notification reliability audit").
Lo instrumentado y la limpieza de bandeja ya se verificaron en 1 móvil (ver más abajo); esto
es lo que **solo se puede comprobar con el segundo**, y es justo el escenario del que se
quejaba el autor ("hay mensajes recibidos pero la alarma no suena").

1. - [ ] **Mensaje con el proceso muerto** (el caso que se perdía en silencio). En el móvil
     receptor: `adb shell am force-stop chat.neto.krypta` (o matarlo desde recientes con el
     limpiador del OEM). **No abrir la app.** Enviar un mensaje desde el otro móvil. Esperar
     hasta ~2 min (el latido). **Esperado**: suena y aparece la notificación **sin haber
     abierto la app**. Antes: el mensaje aparecía al abrir, sin haber sonado nunca.
2. - [ ] **Ráfaga**: enviar 4–5 mensajes seguidos con la app cerrada. **Esperado**: **un solo
     aviso** por contacto que los acumula (se despliega y se leen todos), suena en cada uno, y
     el contador del icono no se dispara de más.
3. - [ ] **Dos contactos a la vez**: recibir de A y de B con la app cerrada → dos avisos, uno
     por contacto. Abrir la app por el **icono** (no por una notificación) y quedarse en la
     lista. **Esperado**: la bandeja queda **vacía** de avisos de Krypta (el permanente
     "Conectado —" sigue) y la lista muestra el **badge de no leídos de A y de B**. Entrar en
     el chat de A → solo se limpia el badge de A.
4. - [ ] **Mensaje estando en otro chat**: con la app abierta en el chat de A, recibir de B.
     **Esperado**: suena y sale el aviso de B (antes: silencio total por tener la app abierta).
     Recibir de A estando en el chat de A → sin aviso (correcto).
5. - [ ] **Llamada con la pantalla bloqueada**: bloquear el móvil receptor y llamar.
     **Esperado**: la llamada **toma la pantalla completa** (full-screen intent) con botones
     contestar/rechazar, timbre y vibración. Probar **contestar desde la notificación** sin
     desbloquear → el audio arranca.
6. - [ ] **Llamada en modo silencio**: con el móvil en silencio, llamar. **Esperado**:
     **vibra en bucle** aunque no suene (antes solo daba un pulso al postearse el aviso).
7. - [ ] **Llamada con el proceso muerto**: `force-stop` en el receptor y llamar. **Esperado**:
     el `invite` llega por buzón en el siguiente latido y timbra. (Ojo: si tarda más que el
     `INVITE_FRESH_MS` de `CallService`, lo correcto es una fila de "📞 Llamada perdida" en vez
     de timbrar tarde — anotar cuál de los dos pasa.)

---

## 13. Entrega en 2.º plano detenida — **FALLO REPRODUCIDO EN 1 MÓVIL (2 sep 2026)**

> No es una prueba "pendiente del segundo móvil": es un **fallo ya reproducido** con uno solo.
> Bloquea la salida a producción, porque rompe la promesa central de la app (los mensajes
> llegan sin abrirla).

### Qué se observó (móvil del autor, TECNO KM5s, Android 15)
Condiciones de partida, todas favorables: proceso vivo (pid estable, 67 hilos, `State: S`, no
congelado), `KryptaForegroundService` en primer plano (`isForeground=true types=0x40000000`),
`chat.neto.krypta` en la whitelist de `deviceidle`, bucket de standby **EXEMPTED (5)**,
`netpolicy` con `effective=NONE` (nada bloqueado), **WiFi validado**, pantalla **encendida** y
dispositivo `ACTIVE` (sin doze).

1. Se depositó un sobre de sonda en el buzón del VPS para el PeerID del propio móvil
   (`TestMailboxPutAgainstLiveNode` con `MBX_ADDR`=VPS y `MBX_TO`=PeerID del móvil; un
   remitente desconocido se descarta y **se confirma igual**, así que no deja basura).
2. Durante **4 minutos** (12 muestras cada 20 s) se midió a la vez: sobres en el buzón del nodo
   y conexiones TCP establecidas del uid de Krypta.
   - Resultado: `sobres_en_buzon=1` y `conexiones_establecidas=0` en **las 12 muestras**.
   - El "latido" (`HeartbeatReceiver`, ~2 min) **no lo rescató**: `dumpsys alarm` daba la
     última alarma **18 min antes**.
3. Al **abrir la app**: aparecen 2 conexiones establecidas y el buzón pasa a **0 sobres al
   instante** (diagnóstico: `07:54:03 buzón: 1 mensaje(s) recogido(s)`).

### La prueba de que el bucle estaba parado, no solo dormido
El panel de Diagnóstico guarda las **últimas 30 líneas** y `announceAndFind()` escribe
`rendezvous: anunciando a N contacto(s)` **en cada ciclo**. Las líneas visibles eran contiguas:

```
07:17:09  rendezvous: anunciando a 2 contacto(s)
07:33:45  → enviado a …unNxKaXK
07:54:03  buzón: 1 mensaje(s) recogido(s)
```

Con el intervalo relajado (180 s, "wake activo") deberían verse **~12 ciclos** entre 07:17 y
07:54. No hay ninguno. Y en 07:33 el autor estaba **usando la app** (envió un mensaje), así que
tampoco es solo "el OEM suspende la red con la pantalla apagada": **el bucle WAN estaba parado
incluso con la app en uso**.

### Causa probable (a confirmar)
`wanLoop` es estrictamente secuencial y **ninguna de sus llamadas tiene timeout**:
`connectDht` → `logRelayStatus` (`reserveRelay`) → `fetchMailbox` → `announceAndFind`.
`Node.StartDHT` (Go) hace `n.h.Connect(n.ctx, …)` con el **contexto de vida del nodo**, sin
plazo propio, y recorre los 3 bootstraps **en serie**. Si un dial se queda colgado (típico en
móvil: TCP medio abierto tras un cambio de red, o un nodo que acepta la conexión pero no
completa el handshake), **se congela el bucle entero de entrega**, indefinidamente.

Y el mismo defecto **anula la red de seguridad**: `pollOnce()` hace
`runCatching { signaling.connectDht(bootstrap) }` **antes** de `fetchMailbox()`, también sin
timeout — si el dial cuelga, el latido nunca llega a retirar el buzón. Es decir, el latido
falla justo en el escenario para el que existe.

Pista que encaja: con la app abierta el móvil mantiene conexión con los **dos nodos de
Cloudflare** (104.21.65.68:443 y 172.67.159.8:443) pero **no** con el VPS primario
(216.128.169.83:4001), que es **la primera línea** de `DEFAULT_BOOTSTRAP` y donde
`MailboxPut` deposita primero. El buzón del VPS sí se vació, pero por el `MailboxFetch`
periódico (abre y cierra), no por un stream de wake sostenido.

### Daño real ya causado
En el buzón del VPS había **3 sobres para `12D3KooWHGKAvSx8…` con fecha 26 ago** (el chat de
"Jimena" en la lista sigue en **"enviado"** con esa misma fecha). Con el **TTL de 7 días** del
buzón, esos mensajes **caducan sin haberse entregado nunca** — pérdida silenciosa.

### Arreglado el 2 sep 2026 (mismo día)
1. - [x] **Plazo y paralelismo en el dial (Go, `StartDHT`)**. Los tres bootstraps se dialan
     ahora **en paralelo**, cada uno con su `context.WithTimeout` (`BootstrapDialTimeout`,
     20 s), y la función **vuelve en cuanto UNO conecta** — el paso cuesta lo que el nodo más
     rápido, no la suma de los tres. Los dials restantes no se cancelan: se mueren solos al
     vencer el plazo, así que un nodo algo lento acaba conectando y sirve en el ciclo
     siguiente. Cubierto por `TestStartDHTNotBlockedByStalledBootstrap` (un *black hole* TCP
     —acepta y no habla— el **primero** de la lista: antes bloqueaba, ahora conecta en **2 ms**)
     y `TestStartDHTAllStalledRespectsTimeout` (con todos colgados vuelve con error dentro del
     plazo, en vez de no volver). `StartDHT("")` sigue siendo válido (el nodo de infra arranca
     la DHT en modo servidor sin bootstrap).
2. - [x] **`MailboxFetch` y `ReserveRelay` en paralelo** (Go). Cada uno ya tenía su plazo
     (60 s y 30 s), pero iban **en serie**: con tres nodos, hasta 180 s y 90 s por ciclo.
3. - [x] **Plazos por paso + watchdog del ciclo (Kotlin, `wanCycle`/`step`)**. Cada paso del
     ciclo va acotado (`CONNECT/MAILBOX/RELAY/RENDEZVOUS_BUDGET_MS`) y el ciclo entero bajo
     `CYCLE_BUDGET_MS` (150 s, **menor que el intervalo relajado de 180 s**, así que un ciclo
     malo no puede solaparse con el siguiente ni comerse varios turnos). Un paso que vence se
     **salta**, no para la entrega, y lo dice en el diagnóstico.
4. - [x] **El buzón se retira aunque el DHT falle o cuelgue**. Antes `fetchMailbox()` estaba
     *dentro* del `if` del `connectDht`, así que un fallo de conexión dejaba el correo sin
     recoger; ahora va fuera (el buzón se retira por dial directo a cada nodo, no necesita la
     DHT). Y el **orden cambió**: primero el buzón, después el rendezvous — entregar mensajes
     importa más que descubrir peers, que puede esperar al ciclo siguiente.
5. - [x] **Lo mismo en el latido (`pollOnce`)**, que es donde más dolía: hacía `connectDht` y
     **luego** `fetchMailbox`, ambos sin plazo, así que un dial colgado dejaba la red de
     seguridad sin llegar nunca a retirar el buzón. Regresión fijada por
     `pollOnce still fetches the mailbox when the DHT dial hangs` (**verificado que falla con
     el código anterior**) y su gemelo para el dial que falla rápido.
6. - [x] **Fallo de enganche en `StartWake` (Go)**: guardaba `wakeCancel` **antes** de mirar la
     lista de nodos, así que una llamada sin nodos (host aún sin arrancar, o pref de bootstrap
     todavía vacía) lo dejaba marcado como "arrancado" con **cero** streams, y el guard de
     reentrada impedía reintentarlo **para siempre** — sin push, solo sondeo. Ahora parsea
     primero, y el `wanCycle` **re-arma el wake cada ciclo** (es idempotente).

### Medido tras los arreglos (2 sep, mismo día) — mejora parcial, **el fallo sigue**
- ✅ **El stream con el VPS primario ya se sostiene**: nada más mandar la app a 2.º plano hay
  **tres** conexiones establecidas (`216.128.169.83:4001` + las dos de Cloudflare). Antes el
  VPS no aparecía nunca. Es coherente con los arreglos 1 y 6.
- ❌ **Pero la entrega en 2.º plano sigue sin funcionar en este móvil.** Repetida la medición
  (sonda + muestreo): **5 minutos, buzón a 1, conexiones a 0**. Al ~1–2 min de mandar la app a
  segundo plano, el móvil **tira todas las conexiones** del proceso.
- ❌ Y el latido **tampoco se dispara**: la alarma **sí está programada**
  (`dumpsys alarm` → `Pending alarms per uid: […, u0a311:2, …]`; ojo, esta ROM **no** imprime
  la sección de lotes, así que no verla ahí no significa nada) pero lleva **20 min sin
  ejecutarse** (`appops … WAKE_LOCK` confirma que el receptor no corrió). Y eso con el proceso
  vivo, FGS en marcha, `am get-standby-bucket` = **5 (EXEMPTED)** y el uid en las dos listas de
  exención de `dumpsys alarm`.

**Conclusión honesta:** los arreglos eliminan una causa real y grave (el bucle y el latido se
podían quedar colgados para siempre, y de hecho **la cadena del latido moría del todo**: como
`pollOnce` no volvía, el `finally` que reprograma la siguiente alarma no llegaba a ejecutarse
nunca). Pero **no eran la única causa**. Lo que queda es de **nivel OEM**: Transsion/HiOS le
retira los sockets a la app en segundo plano y le suprime las alarmas
`setAndAllowWhileIdle` pese a la exención de batería. Eso **no se arregla desde el código de
la app**; es lo que ya documenta la §1 (autostart, Phone Master → apps protegidas, batería sin
restricciones, WiFi siempre activo), y hay que **verificar en el móvil que esos ajustes están
puestos** antes de sacar conclusiones sobre el resto.

### CAUSA CONFIRMADA (2 sep 2026, con los ajustes del OEM ya puestos)
El autor aplicó los ajustes manuales (autostart, Phone Master, batería sin restricciones) y se
repitió la medición. **Sigue sin entregar**, y ya se sabe por qué: **HiOS congela el proceso**.

Muestreo de CPU del proceso (`utime+stime` de `/proc/<pid>/stat`) con la app en segundo plano:

```
t=45s   cpu=3378   estado=S
t=90s   cpu=3383   estado=S
t=135s  cpu=3383   estado=S
...
t=360s  cpu=3383   estado=S     ← 4,5 min sin consumir NI UN jiffy
```

Cero CPU a partir del minuto y medio. Un bucle WAN vivo habría gastado algo al vencer su
temporizador de 180 s. **No es que el código falle: es que sus hilos no se planifican.** Lo
confirma el diagnóstico in-app, cuyos ciclos se paran en seco y reanudan al abrir la app:

```
18:01:54  rendezvous: anunciando a 2 contacto(s)
18:05:36  rendezvous: anunciando a 2 contacto(s)
18:15:47  rendezvous: anunciando a 2 contacto(s)   ← 10 min de hueco; reanuda al abrir
18:15:50  buzón: 1 mensaje(s) recogido(s)
```

La sonda se depositó a las **18:09:59**, dentro del hueco. Y la red de seguridad tampoco entra:
la alarma del latido **estaba programada** (`Pending alarms per uid` → `u0a311:1`) pero llevaba
**9 min 42 s sin dispararse**, con el móvil `ACTIVE`, pantalla encendida, bucket **EXEMPTED (5)**
y el uid en las listas de exención de `dumpsys alarm`. O sea: HiOS congela el proceso **y**
suprime la alarma que debería descongelarlo.

**Conclusión: no hay arreglo posible dentro de la app.** Ningún temporizador, corrutina ni
alarma puede ejecutarse en un proceso al que el sistema no da CPU. Los arreglos del bucle WAN y
del latido siguen siendo correctos —quitaron cuelgues reales— pero no pueden resolver esto.

### ✅ FUNCIONÓ EL 10 SEP 2026 (una muestra, build nuevo)

Con el build del 10 sep —que cambia justo lo que más podía influir: los nodos pasan a **TCP
directo** contra dos VPS, sin el reciclado de WebSocket de Cloudflare, y el ciclo WAN se relaja
a 180 s **porque el wake se sostiene**— el autor reporta que el aviso **llegó y sonó con la app
en segundo plano**. Observado desde fuera a la vez: el TECNO mantenía conexión establecida con
**los dos** nodos, y su Diagnóstico anuncia rendezvous cada ~3 min, que es la cadencia de
"wake en pie" (con el wake caído serían 30 s).

Lo honesto: es **una muestra**, en el móvil que fallaba, y no se ha repetido la medición de CPU
de esta sección con la app cerrada. La decisión de abajo **no se revierte todavía**; lo que
cambia es que deja de ser un hallazgo bloqueante y pasa a "prueba de no regresión antes de
publicar" (punto 4 de la lista de abajo).

### DECISIÓN (2 sep 2026): se deja como está, a la espera de más móviles
El autor decide **no adoptar push por ahora** y tratar el caso del TECNO como
**posiblemente particular**, hasta tener más muestras. Razonable con lo que hay: dos móviles,
uno que falla (TECNO/HiOS) y uno que recibía bien (el de la colaboradora, §1).

**Lo que haría cambiar la decisión**: que el fallo aparezca en móviles de **otras marcas**. Si
se reproduce fuera de Transsion, deja de ser una nota de compatibilidad y pasa a bloquear la
publicación, porque rompe la promesa central. Para probarlo basta la medición de esta sección:
depositar la sonda y muestrear el CPU del proceso en segundo plano (`utime+stime` de
`/proc/<pid>/stat`); si se queda clavado, es el mismo congelado.

**Lo que NO conviene olvidar**: mientras esto siga abierto, la app entrega en segundo plano en
móviles normales pero **no** en los que congelan procesos, y el usuario no tiene forma de
saberlo. Eso pesa en la ficha de la tienda y en el aviso al usuario, no solo en el código.

### Opciones descartadas por ahora (para cuando haya más datos)
1. - [ ] **Push sin contenido por FCM** (el modelo de Signal). Google Play Services mantiene una
     conexión privilegiada que el OEM **nunca** congela — por eso WhatsApp funciona sin aviso
     fijo. El push no llevaría contenido: solo "despierta y retira tu buzón", así que el E2EE
     queda intacto. Coste: dependencia de Google y metadatos (sabría qué dispositivo recibe algo
     y cuándo). Obliga además a reescribir la justificación del FGS `specialUse` ante Play, que
     hoy dice literalmente "no hay push de terceros".
2. - [ ] **UnifiedPush** (el plan original). Sin Google, con distribuidor propio. Pero la app
     distribuidora sufre el mismo congelado del OEM salvo que el usuario la proteja: mueve el
     problema, no lo elimina.
3. - [ ] **Asumirlo y documentarlo.** Hay indicio de que es específico de Transsion: la §1
     recoge que la colaboradora, con otro modelo, **sí recibía**. Sería publicar con un aviso
     claro y la guía de ajustes. **Antes de decidir esto hay que confirmarlo en 2–3 móviles de
     marcas distintas**, porque si el problema es general, la app no cumple su promesa.
2. - [ ] Confirmar si el fallo es **específico del TECNO** (la §1 dice que la colaboradora, con
     otro modelo, **sí recibía**) — es lo que decide si esto bloquea la publicación para todos
     o solo es una nota de compatibilidad para móviles Transsion.
3. - [ ] Avisar al usuario cuando un mensaje lleva demasiado en "enviado" sin confirmar (hoy
     caduca en silencio a los 7 días). **No abordado**: es cambio de producto, no de este fallo.
4. - [ ] **Prueba de no regresión, obligatoria antes de publicar**: repetir la medición de
     arriba (depositar la sonda + muestrear 4 min con la app cerrada) y ver el buzón a 0 **sin
     abrir la app**. Con dos móviles, además, §12.1.

---

## 14. "Cerrar todo" y borrar notificaciones — **MEDIDO (2 sep 2026)**

Reporte del autor: al usar "Cerrar todo" en recientes, o al borrar todas las notificaciones
desde la cortina, desaparece el aviso "Conectado — recibiendo mensajes cifrados" y Krypta deja
de recibir. Son **dos casos distintos** y solo uno hace lo que parece.

### Caso A — "Cerrar todo" en recientes (`com.transsion.hilauncher:id/ts_btn_recents_clear`)
**Mata Krypta, pero se recupera sola.** Primera medición: el pid desaparece al instante, el
FGS cae y el aviso se va. La **alarma del latido sobrevive** (`Pending alarms per uid` →
`u0a311:1`) y a los **~160 s** el proceso volvió, con el servicio incluido:

```
ActivityManager: Background started FGS: Allowed [callingPackage: chat.neto.krypta;
  code:SYSTEM_ALLOW_LISTED; ...]
```

(se le permite arrancar un FGS desde segundo plano por la exención de batería). O sea: no queda
muerta, queda **ciega ~2–3 min**.

⚠️ **No se pudo reproducir después.** En 4 intentos posteriores la tarea sí se quitaba de
recientes (`dumpsys activity recents` → 0) pero **el proceso sobrevivía** con el FGS en pie. Se
hizo una prueba A/B desactivando el `onTaskRemoved` nuevo y **también sobrevivía**, así que la
diferencia con la primera medición es **ambiental** (probablemente el limpiador de HiOS perdona
a las apps lanzadas hace poco), no mérito del arreglo. Queda pendiente reproducirlo en
condiciones más parecidas a las del autor: app llevando horas abierta, a batería, sin cable.

### Caso B — "Borrar todo" en la cortina — **NO mata nada**
Pulsando el botón real del sistema (`com.android.systemui:id/btn_clear_all`): mismo pid antes y
después, `isForeground=true`, y el aviso **sigue posteado**
(`flags=ONGOING_EVENT|NO_CLEAR|FOREGROUND_SERVICE`). Lo único que ocurre es que **desaparece de
la vista**: desde Android 14 el usuario puede descartar el aviso de un servicio en primer plano
y el servicio sigue corriendo. Krypta **sigue recibiendo**; lo que se pierde es la señal visual.

### Por qué WhatsApp no necesita esto
Es diferencia de arquitectura, no fallo: WhatsApp recibe por **FCM (el push de Google)** y por
eso no necesita aviso permanente. Krypta renunció a los terceros a propósito, así que depende de
mantener su propia conexión, y en Android eso obliga al FGS con su aviso. No se pueden tener las
tres cosas a la vez: sin push de terceros, sin aviso permanente y con entrega instantánea.

### Arreglado
- [x] **`onDestroy` cancelaba el latido.** `HeartbeatReceiver.cancel(this)` corría al destruirse
      el servicio — y como **nada en la app lo para a propósito** (no hay un solo `stopSelf` ni
      `stopService` en todo el código), ese `cancel` solo podía dispararse cuando el sistema o
      el OEM tumbaba el servicio: justo cuando el latido es lo único que puede resucitarlo. Se
      estaba matando la red de seguridad en el único escenario para el que existe. Ahora
      `onDestroy` **pide un latido inmediato** en vez de cancelarlo.
- [x] **`onTaskRemoved` no existía.** Ahora pide un latido inmediato (`scheduleNow`, +3 s) para
      que, cuando el "Cerrar todo" sí mate la app, vuelva en segundos en vez de en ~160 s. No se
      pudo demostrar en vivo por lo dicho arriba, pero es correcto: `START_STICKY` no rearranca
      un servicio cuya tarea ha quitado el usuario.
- [x] **Ayuda in-app**: entrada nueva en "Problemas frecuentes" explicando que "Cerrar todo" sí
      cierra Krypta, que el candado de recientes la excluye, y por qué otras apps no lo
      necesitan. La entrada que ya existía sobre ocultar el aviso fijo era correcta y se
      confirma con la medición del caso B.

### Pendiente
1. - [ ] Reproducir el caso A a batería y con la app llevando horas, para saber si el latido de
     3 s basta o si HiOS también bloquea el rearranque.
2. - [ ] Valorar un aviso en la UI que distinga "aviso oculto" de "servicio parado", ya que hoy
     el usuario no puede saber cuál de los dos tiene.

---

## 15. Bloquear un contacto entre 2 móviles — **PENDIENTE**

Implementado y cubierto por tests JVM (`ChatServiceTest`: descartado y ack'eado, no sale nada
por ningún camino, sin acuse de lectura, fuera del rendezvous, desbloquear restaura) y
verificado en 1 móvil con un contacto desechable (insignia 🚫, aviso en el chat con
"Desbloquear", botón de llamar deshabilitado, migración v4→v5 con los contactos intactos).
Lo que **solo se puede comprobar con el segundo móvil** es la propiedad que de verdad
importa: que el bloqueado **no se entera**.

Usar un contacto desechable, no uno real.

1. - [ ] **El mensaje no llega ni suena.** Bloquear a B en el móvil A. Enviar desde B un
     texto, una foto y una nota de voz. **Esperado en A**: no aparece nada en el chat, no
     suena ninguna notificación y el badge de no leídos no cambia — ni con la app abierta ni
     con la app cerrada (probar ambas).
2. - [ ] **B no se entera.** En el móvil B, esos envíos quedan con el estado normal de
     "enviado" (✓), **nunca** con error, y B no recibe el ✓✓ aunque A abra el chat. Es la
     diferencia con eliminar el contacto, y es lo que hace que el bloqueo sea silencioso.
3. - [ ] **El sobre no se queda dando vueltas en el buzón.** Con A bloqueando y B enviando
     3–4 mensajes, dejar pasar dos ciclos de WAN y mirar el panel de Diagnóstico de A: debe
     aparecer la retirada del buzón (`buzón: N mensaje(s) recogido(s)`) **una sola vez** por
     ráfaga; si los mismos sobres reaparecen ciclo tras ciclo es que no se están ack'eando y
     acabarían llenando el cupo de 5 MiB del destinatario.
4. - [ ] **Llamada bloqueada.** B llama a A. **Esperado en A**: no timbra, no sale
     notificación de llamada y **no** aparece la fila "📞 Llamada perdida". En B, la llamada
     se queda sonando hasta que expira, igual que si A estuviera sin cobertura.
5. - [ ] **A no puede escribir ni llamar.** En el chat de B (bloqueado), A no tiene barra de
     escribir —sale el aviso con "Desbloquear"— y el botón de llamar está deshabilitado.
6. - [ ] **Desbloquear restaura la entrega.** A desbloquea a B. **Esperado**: los mensajes
     **nuevos** de B llegan con normalidad. Los enviados **durante** el bloqueo no vuelven
     (se descartaron y se borraron del buzón): confirmarlo explícitamente, es el
     comportamiento pretendido, no un fallo.
7. - [ ] **El bloqueo sobrevive al respaldo.** En A: bloquear a B, exportar un `.krbk`,
     importarlo en un móvil de repuesto (o tras reinstalar) y comprobar que B sigue
     **bloqueado**, no desbloqueado.

---

## 16. El ratchet, ya encendido — **PARCIAL: conversación normal ✅ (10 sep 2026), escenarios límite pendientes**

> **Resultado del 10 sep 2026 (tarde).** Los dos móviles con el build de ese día. Conversación
> real en los dos sentidos **sin pérdidas** (punto 2 de la lista), reportada por el autor y
> consistente con lo observado desde fuera: el Diagnóstico del TECNO registra `← mensaje de
> …f47d1JYR` a las 08:34:24 y 08:35:58 y `→ enviado a …f47d1JYR` a las 08:36:13, el ciclo WAN
> anuncia a 2 contactos cada ~3 min (o sea, con el wake en pie) y los dos nodos veían a la vez
> dos móviles de **operadoras distintas de La Paz** (Viva y Tigo). Lo que **no** se pudo
> comprobar desde fuera es la marca `peerProtocol = 2`: la base va cifrada con SQLCipher y el
> panel de Diagnóstico solo guarda las últimas líneas, así que el `↔ protocolo v2 anunciado`
> del punto 1 ya había salido del buffer. Que ambos móviles lleven el build del día es lo que
> hace que la pareja use v2.
>
> **Sigue pendiente lo que de verdad podía romper**: reentrega del buzón (3), archivo grande
> cruzando época (4), pérdida de estado e importación de `.krbk` (5) y llamada (7).

El secreto hacia adelante está implementado entero (`docs/DISENO-ratchet.md`, fases 1–6) y
cubierto por tests JVM: el ratchet en sí (`RatchetTest`, 17), su persistencia y atomicidad
(`RatchetSessionsTest`, 5) y el cableado en `ChatService` —recepción v1+v2, anuncio de
capacidad, ida y vuelta completa entre dos clientes y un archivo troceado por ratchet—. Lo que
falta no es código: es **la prueba con dos móviles antes de encender el envío**, que es la
condición que puso el propio diseño (§10) y que sigue en pie.

**Estado del interruptor:** `ChatService.RATCHET_SEND = **true**` desde el 10 sep 2026. Se
encendió **antes** de esta prueba, por decisión del autor (la colaboradora no responde y eso
tenía el trabajo parado). Lo que hay que entender: a quién se le escribe con ratchet lo decide
cada contacto (`peerProtocol`, anunciado con el sobre `V`), así que **hasta que el segundo móvil
no tenga este build no cambia nada**. En cuanto lo tenga, la pareja pasa a ratchet **sin haber
pasado esta prueba**: por eso conviene hacerla con un contacto desechable **antes** de fiarle una
conversación real, y por eso el interruptor sigue siendo `var` (ponerlo a `false` y republicar
devuelve todo a v1).

**Antes de empezar:** los dos móviles con el **mismo** build (ya trae el envío encendido); y
**exportar un `.krbk` en los dos** (el historial no tiene copia de seguridad de ninguna clase).
Usar contactos desechables si se puede.

1. - [ ] **Se anuncian y se reconocen.** Con los dos actualizados, esperar un ciclo de WAN y
     mirar el Diagnóstico: debe salir `↔ protocolo v2 anunciado a N contacto(s)` en cada uno, y
     **una sola vez** — si reaparece en cada arranque, la marca no está persistiendo. En el otro
     móvil debe aparecer `↔ …<peer> habla protocolo v2`.
2. - [x] **Conversación normal.** ✅ **10 sep 2026**: texto en los dos sentidos, sin pérdidas,
     con los dos móviles en el build del día. Falta repetirlo con **foto, nota de voz y
     respuesta con cita**, que van por caminos distintos (envelope `I`, troceado y `Y`).
3. - [ ] **Reentrega del buzón.** Cerrar la app de B, enviarle 3–4 mensajes desde A, abrirla:
     tienen que llegar **todos y una sola vez**. Después, en dos ciclos seguidos de WAN, el
     Diagnóstico de B no debe volver a recoger los mismos sobres (si reaparecen es que no se
     están ack'eando).
4. - [ ] **Archivo grande cruzando un cambio de época.** A envía un archivo de ~2–4 MB (o un
     GIF) y B **responde con un mensaje mientras se está enviando**. El archivo debe llegar
     completo y abrirse. Es el caso que más partes toca a la vez: ráfaga de trozos, época
     girando a mitad y reensamblado en disco.
5. - [ ] **Pérdida de estado en un extremo.** En B: exportar `.krbk`, desinstalar, reinstalar e
     importar. Con el estado del ratchet perdido, B escribe a A: el mensaje **tiene que llegar**
     (arranca un linaje nuevo en la época 0, que A adopta por ser mayor). Y a la inversa: A
     escribe a B y B lo lee. Esto es lo que hace que una sesión rota nunca sea permanente, y
     es la propiedad que más conviene ver con los ojos.
     **Y lo que hay que mirar además** (lo destapó la prueba de propiedades, ver
     `DISENO-ratchet.md` §1.9): justo **antes** de que B escriba, que A le mande un mensaje. Ese
     mensaje **se va a perder** —A escribe en el linaje viejo y B descarta lo menor— y en el
     móvil de A se quedará como enviado sin que B lo vea nunca. Es el comportamiento esperado
     hoy, no un fallo de la prueba; sirve para medir cuánto dura la ventana y decidir si merece
     la pena arreglarla.
6. - [ ] **Los mensajes de antes se siguen leyendo.** Subir de una versión anterior (no
     reinstalar): el historial previo debe seguir legible y el Diagnóstico debe registrar
     `🗄 historial convertido: N mensaje(s)` una sola vez.
7. - [ ] **Llamada.** Una llamada de voz y una de vídeo entre los dos, para confirmar que la
     señalización (que viaja por el mismo camino) no se rompe. La clave por llamada **todavía
     no** va dentro del ratchet: eso es la fase 7.
8. - [ ] **Un contacto sin actualizar sigue funcionando.** Con un tercer móvil (o dejando uno
     sin actualizar), comprobar que la conversación con él sigue en v1 y que no se rompe nada:
     es el caso normal durante semanas.

9. - [ ] **Adjuntos cifrados en reposo** (fase 8, se puede probar ya, con el ratchet apagado):
     enviar una nota de voz y un GIF, y comprobar en el móvil emisor que
     `run-as chat.neto.krypta head -c 4 files/krypta_files/sent/<fichero>` dice `KFV1` y no
     `GIF8`/`ftyp`. Después: que la nota de voz **se reproduce** en su burbuja, que el GIF
     **se anima**, y que un archivo recibido se abre con otra app (deja una copia en claro en
     la caché a propósito). Los adjuntos que ya estaban en el móvil siguen en claro y tienen
     que seguir funcionando: eso ya está verificado en el TECNO con un GIF anterior.

Si algo falla, el interruptor vuelve a `false` y la conversación sigue en v1 sin perder nada
—salvo lo que se hubiera enviado con ratchet y no se hubiera podido abrir—, que es justo por
lo que esta prueba va antes del encendido.

---

## 10. DCUtR directo en celular (gate de NAT) — **BLOQUEADO por hardware**
Requiere **2 SIMs de operadoras distintas** (CGNAT real). Medir si la conexión sube a
directa (DCUtR) o se queda en relay.

1. - [ ] Con ambos móviles en datos móviles (operadoras distintas), enviar mensajes y revisar
     el diagnóstico: ¿aparece conexión directa o todo va por relay/buzón?
2. - [ ] Medir latencia directa vs. relay.

---

## Verificado en 1 móvil (no requiere el segundo)
- **Bloquear contacto (6 sep)**: en el TECNO, con un contacto de usar y tirar (creado con el
  PeerID del nodo de São Paulo, borrado al terminar; los dos contactos reales no se tocaron).
  La **pulsación larga** en la lista ofrece "Vaciar chat / Bloquear / Eliminar contacto" y el
  bloqueo se aplica sin confirmación (es reversible). Tras bloquear: 🚫 en rojo junto al
  nombre de la lista; dentro del chat, la barra de escribir se sustituye por "Has bloqueado a
  X. No recibirás sus mensajes ni sus llamadas." con **Desbloquear**, y el botón de llamar
  pasa a estar deshabilitado con `content-desc` "Contacto bloqueado" (leído con `uiautomator
  dump`, porque `screencap` sale negro en el chat). ⋮ → **Desbloquear** devuelve la barra de
  entrada, el clip, el micro y el botón de llamar. **Migración v4→v5 en el móvil real**:
  `PRAGMA user_version` = 5 y los dos contactos reales siguen ahí con su `verified` intacto y
  `blocked = 0`.
  - [ ] **Pendiente con 2 móviles**: §15 (que el bloqueado no se entere: sus envíos le quedan
    en ✓, sin ✓✓; que a este lado no llegue nada ni suene; que los sobres del buzón se
    ack'een en vez de reentregarse; y que el bloqueo sobreviva a un `.krbk`).
- **Responder citando (3 sep)**: en el TECNO, con un contacto de usar y tirar. **Mantener
  pulsada** una burbuja abre la píldora con "Responder a este mensaje" + "Copiar mensaje";
  **deslizarla a la derecha** abre directamente la barra de cita (autor + resumen + ✕ sobre el
  campo de escribir). La ✕ y el botón **atrás** la descartan sin salir del chat. Enviada la
  respuesta, la burbuja se pinta con la cita dentro ("Tú" / texto citado) encima del texto
  nuevo (comprobado con la captura propia de ⋮, porque `screencap` sale negro en el chat).
  - [ ] **Pendiente con 2 móviles**: (a) que el receptor vea la cita resuelta con **su** copia
    del mensaje citado (aquí ambos extremos eran el mismo móvil); (b) responder **con** una
    foto y **con** una nota de voz y comprobar que la cita sobrevive al troceado/reensamblado
    (la cita viaja en la meta, no en los trozos); (c) responder a un mensaje que el receptor
    ya **vació** de su chat → debe salir "Mensaje no disponible", no un hueco ni un fallo;
    (d) responder con el receptor **desconectado** (entrega por buzón) y ver la cita al abrir;
    (e) tocar la cita en el móvil receptor debe saltar al mensaje original y destellarlo.
- **Avisos, auditoría del 13 ago**: `KryptaNotificationsTest` (instrumentado, 4/4 en el
  TECNO/Android 15) cubre la acumulación por contacto (MessagingStyle), que cancelar un
  contacto no toca al otro, que `cancelAllMessages` barre el canal **sin** tirar el permanente
  del servicio, y que el sistema **acepta** la notificación de llamada con `CallStyle` +
  full-screen intent (es donde el sistema rechaza en caliente, no en el build).
  `USE_FULL_SCREEN_INTENT` sale `granted=true` en `dumpsys package`. En vivo: tres "Probar
  aviso" seguidos dan **una** notificación de conversación acumulada; ir a inicio y volver a
  abrir la app deja la bandeja limpia de mensajes con el "Conectado —" del servicio intacto
  (comprobado con `dumpsys notification` y captura del panel).
- **Bloqueo de capturas (13 ago)**: con `FLAG_SECURE`, `adb shell screencap` de la app sale
  **totalmente en negro** (solo se ven las barras del sistema, que no son de Krypta), y
  ⋮ → "Capturar pantalla" dentro del chat genera un PNG correcto con toda la UI en
  `Pictures/Krypta` (el menú desplegable no aparece: se esperan dos fotogramas). Queda por
  comprobar a mano en el móvil, sin adb: el gesto nativo de captura (debe salir el aviso del
  sistema), un grabador de pantalla (debe grabar negro) y la miniatura de recientes (vacía).
  **Acotado al chat (21 ago)**: el flag ya no se pone en `MainActivity`, sino al entrar en la
  pantalla de chat y se quita al salir. Pendiente de comprobar en el móvil: (a) `adb shell
  screencap` **con un chat abierto** sale negro; (b) el mismo comando en la **lista de chats,
  ajustes y ayuda** sale con la UI normal; (c) entrar y salir del chat varias veces mantiene
  ese comportamiento (el flag se pone/quita, no se queda pegado); (d) al ir a "recientes"
  desde un chat la miniatura sale vacía, y desde la lista sale normal; (e) ⋮ → "Capturar
  pantalla" sigue guardando el PNG correcto.
- **Contenido del teclado (13 ago)**: en el TECNO, con un contacto de usar y tirar, las
  pestañas **GIF y stickers** de Gboard ya abren (antes: "la app no admite insertar aquí"); un
  sticker con fondo transparente se pinta **sobre el teal de la burbuja**, no sobre un cuadro
  negro (arreglo de `WEBP_LOSSY` en `ImageCodec`). Un **GIF de Tenor se envía troceado**
  (diagnóstico: "→ archivo enviado … (2 trozos)") y la burbuja **se anima**: dos capturas con
  un segundo de diferencia muestran fotogramas distintos. La vista previa de la lista dice
  "🎞 GIF". Las dos entradas nuevas de la ayuda (el aviso fijo del servicio y el contenido del
  teclado) se leen en pantalla.
  - [ ] **Pendiente con 2 móviles**: que el GIF llegue **animado al receptor** (aquí solo se
    comprobó la burbuja propia, que usa la copia local; la del receptor la reensambla
    `DiskFileStore`). Probar también con el receptor **desconectado** (entrega por buzón: 4 MB
    es el tope precisamente para caber en su cupo de 5 MiB).
- **Preparación para Play (23 jul)**: AAR regenerado con alineación de **16 KB**
  (`-extldflags=-Wl,-z,max-page-size=16384`; las 4 ABIs a `0x4000`, `zipalign -c -P 16` OK) y
  **copia automática de Google desactivada** (`allowBackup="false"`; `dumpsys package` ya no
  lista `ALLOW_BACKUP`). Con el AAR nuevo instalado en el TECNO: la app arranca, el estado es
  **"conectado"** (DHT), los contactos siguen y las vistas previas descifran. AAB de release
  firmado regenerado (~82 MB) y verificado por dentro. Checklist completo del lanzamiento en
  [PLAY-STORE.md](PLAY-STORE.md).
- **No puedes añadirte a ti mismo (23 jul)**: `ChatService.addContact` rechaza el PeerID
  propio (`require` → `IllegalArgumentException`) y `ChatViewModel` muestra el motivo exacto;
  además, si el PeerID ya estaba guardado con otro nombre, avisa del renombrado en vez de
  heredar el chat anterior en silencio. Verificado en el TECNO por adb: Nuevo contacto →
  nombre "PruebaAutoAlta" + PeerID propio → **"Ese es tu propio PeerID: pide a tu contacto
  el suyo"** en rojo bajo la barra y **no** se crea el contacto. Unit test
  `addContact rejects your own peerId`. Nota: esto retira el truco de auto-envío que se usaba
  para probar el buzón en un solo móvil (ya cubierto por tests de Go y `ChatServiceTest`).
- **Vaciar chat / eliminar contacto (17 jul)**: pulsación larga en la lista → diálogo
  "Vaciar chat / Eliminar contacto", y menú **⋮** en la barra del chat con las mismas
  acciones; ambas piden confirmación destructiva. Verificado por adb en el TECNO con un
  contacto desechable ("BorrarTest", PeerID del nodo Windows): long-press → vaciar →
  confirmación; abrir su chat → ⋮ → eliminar → vuelve a la lista y la fila desaparece;
  Lucia y SinVerificar intactos. Todo local (sin protocolo); unit tests en
  `ChatServiceTest` + `DiskFileStoreTest`. **Falta un matiz en vivo**: vaciar un chat que
  tenga **archivos/notas de voz reales** y comprobar con `run-as` que los ficheros de
  `files/krypta_files/` (ensamblados y copias en `sent/`) desaparecen del disco — la
  verificación en vivo se hizo sobre un chat sin adjuntos (el borrado de ficheros está
  cubierto por `DiskFileStoreTest`, pero no se observó aún en el móvil).
- **Selector de tema (16 jul)**: Ajustes → tarjeta "Apariencia" con 3 botones
  (Sistema/Claro/Oscuro; por defecto Sistema). `ThemePreference` (pref en `krypta_settings`)
  + `resolveDark` (puro, `ThemePreferenceTest`). Verificado en el TECNO: pulsar "Claro" pasa
  toda la app a tema claro **en caliente** (sin reiniciar) y marca la opción; "Sistema"
  vuelve a seguir el modo del móvil.
- **Ayuda in-app (16 jul)**: icono **?** en la barra superior de conversaciones y de Ajustes
  → pantalla "Ayuda" con un FAQ corto en tarjetas desplegables agrupadas por categoría
  (`ui/HelpScreen.kt` + `ui/HelpContent.kt`). Ayuda contextual: icono **ⓘ** en las tarjetas
  "Tu identidad" y "Recepción en segundo plano" de Ajustes → diálogo breve. Verificado en el
  TECNO (tema oscuro): abrir la Ayuda, desplegar una pregunta (chevron rota, respuesta
  aparece), y el diálogo "Tu PeerID" con botón "Entendido". Datos del FAQ cubiertos por
  `HelpContentTest`. Onboarding de primera vez: pendiente (fase posterior, acordado).
- **Formas de avatar por PeerID (16 jul)**: en la lista de conversaciones el avatar de cada
  contacto ya no es solo un círculo — la **forma** (círculo, squircle, hexágono, pentágono,
  octágono) se deriva del PeerID igual que el color (`ui/theme/AvatarShape.kt`). Verificado
  en el TECNO: Lucia → pentágono redondeado, SinVerificar → squircle, ambos con la inicial
  bien centrada. **Falta comprobar con un contacto EN LÍNEA** que el punto verde de "en
  línea" (reubicado a (0.70, 0.84) del recuadro) quede sobre el cuerpo de una forma no
  circular (hexágono/pentágono) y no flotando en la esquina vacía — no había ningún contacto
  conectado durante la verificación.
- **Bloqueo de la app (16 jul)**: Ajustes → "Bloqueo de la app" → interruptor "Pedir
  desbloqueo para entrar" (huella/cara/PIN del sistema, BiometricPrompt; Krypta no guarda
  secretos) + selector "Al instante / 1 min / 5 min". Verificado por adb en el TECNO: el
  interruptor abre el diálogo nativo (huella + "Usar patrón"), cancelar no lo activa; con
  la pref forzada, el arranque en frío cae en "Krypta está bloqueada" (sin filtrar
  contenido), el prompt salta solo, cancelar mantiene el bloqueo y "Desbloquear" lo
  relanza. **Ciclo con dedo real VERIFICADO por el autor (16 jul)**: activar autenticando,
  bloquear/desbloquear con huella — "marchó bien" (probado con gracia de 1 min); después
  lo dejó desactivado por preferencia personal, la función queda opcional y operativa.
  (Matiz no aislado explícitamente: atender una llamada entrante con la app bloqueada —
  el gate va tras la rama de CallScreen, cubierto por diseño.)
- **Rediseño de UI Material 3 (12 jul)**: tema verde-teal claro/oscuro, icono de launcher
  nuevo, lista de conversaciones (avatar, vista previa descifrada, hora, badge de no leídos
  que se limpia al abrir el chat), pantalla de Ajustes con los controles técnicos, chat con
  burbujas asimétricas. Verificado en el TECNO en ambos temas; **sin cambio de protocolo ni
  de esquema de DB** (mezclar versiones no rompe nada, pero conviene actualizar ambos).
  El APK del 12 jul está en `~/Desktop/krypta-arm64-debug.apk`.
- **UI-3 del chat (12 jul)**: separadores por día ("Hoy"/"Ayer"/"lunes 6 de julio"),
  agrupación de burbujas consecutivas, hora + checks dentro de cada burbuja, hoja inferior
  de adjuntos (Foto/Archivo), y nota de voz **mantener-y-soltar** (mantener graba, soltar
  envía, <1 s se descarta; toque corto = grabación fijada con Cancelar/Enviar). Verificado
  en vivo en el TECNO, incluida una nota enviada por mantener-y-soltar al contacto propio.
  De paso se cazó y arregló un bug del gesto: si la fila de entrada se sustituía durante la
  grabación mantenida, el gesto se cancelaba y "soltar" no enviaba.
- **7d proximidad (12 jul)**: en llamada de voz (sin altavoz/vídeo) se adquiere el
  `PROXIMITY_SCREEN_OFF_WAKE_LOCK` y se libera al colgar — verificado por `dumpsys power`
  (ACQ al llamar, REL al colgar). Falta la prueba física (oreja → pantalla se apaga, se
  reenciende al alejar) en la llamada real de dos móviles (§9.9).
- **Multi-nodo, lado cliente (12 jul)**: el campo de bootstrap acepta varios nodos (uno
  por línea); el bridge deposita en el primero vivo y retira/escucha de todos. Verificada
  la **regresión con un solo nodo** (conecta y envía igual que antes); el **failover real**
  se probará con el segundo nodo. **(16 jul) El segundo nodo está DESPLEGADO**: el PC
  Windows del autor, expuesto como `krypta2.neto.chat` (runbook en la sección "Segundo nodo
  en Windows" de [infra/node/README.md](../infra/node/README.md)). PeerID
  `12D3KooWNGNzFsntPcabJ3DxmYKuXzSD6skeTaeepsnbntc6JTEm`; verificado desde la Mac (conexión
  libp2p completa por wss + sondas de buzón y wake OK). `DEFAULT_BOOTSTRAP` ya trae ambos
  nodos; en el TECNO se borró la pref antigua de un solo nodo para que caiga al default
  (conectado ✓). **Queda la prueba de failover con 2 móviles** (Fase C del runbook): ambos
  con el APK del 16 jul (si el 2.º móvil guardó bootstrap manual alguna vez, pegar las dos
  líneas), apagar el nodo de la Mac (`launchctl unload …chat.neto.krypta.node.plist`),
  enviar con el receptor cerrado → debe salir SENT (buzón del nodo Windows) y notificar al
  abrir; recargar el nodo de la Mac al acabar.
  **(10 sep, tarde) Failover del depósito observado en producción, sin provocarlo**: a las
  12:29 UTC un sobre aterrizó en el buzón de **Dallas**. Como `MailboxPut` deposita en el
  **primer nodo vivo** y São Paulo es el primero de la lista, eso significa que en ese momento
  São Paulo no aceptó el depósito y el cliente cayó al segundo nodo solo. Es media prueba: el
  depósito hizo failover de verdad, pero **el sobre sigue sin recogerse** (ver §12), así que la
  otra mitad —que el destinatario lo retire del segundo nodo— no está demostrada.
  **(10 sep) La prueba cambia de nodos**: `DEFAULT_BOOTSTRAP` es ahora São Paulo + Dallas (los
  domésticos salieron). Pasos: ambos móviles con un APK del 10 sep o posterior y **sin lista
  guardada en Ajustes** (o con esas dos líneas); con el receptor cerrado, parar São Paulo
  (`ssh root@216.128.169.83 systemctl stop krypta-node`) y enviar → debe salir SENT por el
  buzón de Dallas y notificar al abrir el receptor; volver a arrancar São Paulo al acabar
  (`systemctl start krypta-node`) y comprobar que los dos siguen en `check-nodes.sh`.
  **(23 jul) El intento de prueba con una segunda persona ("Jimena") NO llegó a ejecutarse**:
  el PeerID dado de alta era el **del propio TECNO**, no el de ella. Como `Contact.id` **es**
  el PeerID, el alta hizo `upsert` sobre el contacto de auto-envío que ya existía de las
  pruebas de buzón — heredó su chat (mensajes del 4–12 jul, incluido "prueba-multinodo") y
  parecía un contacto real, pero los mensajes iban al propio móvil. Diagnosticado sacando
  `databases/krypta.db` por `run-as` y derivando el PeerID propio de `krypta_identity.xml`
  (coincidían). **Arreglado el mismo día** (ver la entrada del guardarraíl más abajo). Para
  rehacer la prueba: eliminar ese contacto (long-press → Eliminar contacto), pedirle a ella
  su PeerID **desde su pantalla de Ajustes** y volver a darla de alta; la verificación de
  identidad hay que repetirla (el escudo actual no vale, se comparó contra uno mismo).
- **Respaldo de identidad (12 jul)**: ciclo completo en el TECNO — Ajustes → "⬆ Exportar
  copia" (frase-clave + SAF a Descargas, archivo `.krbk` de ~289 B) → "⬇ Importar copia"
  (mismo archivo + frase-clave) → diálogo "Identidad restaurada" con el PeerID → "Cerrar
  Krypta" → al reabrir, **mismo PeerID**, contactos intactos y vistas previas descifrando
  (secretos re-derivados OK). La restauración **en un móvil distinto** (el caso real) se
  probará cuando toque migrar/preparar un segundo dispositivo; los mensajes antiguos no
  viajan en la copia (solo identidad + contactos), y una frase-clave errónea se rechaza
  (cubierto por `IdentityBackupTest`).
- **QR de verificación** (4 jul): el QR se genera y renderiza; el botón "Escanear" pide
  permiso de cámara y abre la `CaptureActivity` de ZXing. Falta el escaneo real entre 2
  móviles (§2).
- **Migraciones Room** (4 jul): un contacto (con su flag `verified`) **sobrevive al salto
  v3→v4** instalando encima sin desinstalar — ya no se pierden datos al actualizar. Nota: al
  cambiar el id de un canal de notificación (no la DB) los datos igual se conservan; lo que
  antes borraba era `fallbackToDestructiveMigration`, ya retirado.
- **Banner "identidad sin verificar"** (4 jul): aparece en el chat de un contacto no
  verificado y desaparece al verificar (Camarada verificado no lo muestra). Clicable → abre
  el diálogo de verificación.
- **Intervalo WAN adaptativo (batería)** (4 jul): con la app conectada al nodo real, el
  diagnóstico muestra "wake activo: bucle relajado (180s)"; sin wake vuelve a 30 s. Falta
  medir el ahorro real de batería en una sesión larga (no bloqueante).

## Ya verificado en vivo con 2 móviles (histórico)
- **Videollamada (7c)** (16 jul): llamada de voz + 🎥 vídeo entre los dos móviles, todo
  funcionó bien con los ajustes del 6 jul (320×240/12fps/250kbps, descarte GOP,
  `keepScreenOn`), en red **mixta** (WiFi ↔ datos móviles). ✅ (§11)
- **Sesión completa 5 jul**: mensajes ✅ (con READ, §4), imágenes ✅, archivos multi-trozo ✅
  (§6), notas de voz 2/3 (§7), primera llamada de voz real: clara y sin eco pero corte a
  ~20 s (§9, causa y fix identificados), latencia en datos móviles p95=220 ms (§8). ✅
- **Imágenes (v1 en línea) + notificación** (4 jul): foto elegida → comprimida → enviada →
  **la colaboradora la recibe** y la notificación funciona. ✅ (antiguo §6)
- **Archivo PDF troceado (2 trozos, 84 KB)** (4 jul): llegó y fue **leído**. El caso
  multi-trozo perdido (.bin de 150 KB) sigue abierto en §6.
- **Mensajería WAN por Circuit Relay v2 tras NAT** (26 jun–1 jul): mensaje `SENT` por relay.
- **Buzón store-and-forward (entrega offline)** (3 jul): receptor cerrado → `SENT` vía buzón
  → al abrir, el mensaje llega y descifra.
- **Wake (aviso instantáneo)** (3 jul): depósito → retirada en ~3 s; con la app cerrada, la
  notificación (versión previa) llegó. *La nueva UX de notificación (§1) reemplaza esa parte
  y vuelve a estar pendiente.*
