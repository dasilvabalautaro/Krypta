package chat.neto.krypta.ui

/**
 * Ayuda in-app: un resumen corto y orientado a tareas de lo que el manual explica en largo.
 * Es **texto plano, sin Compose**, para poder cubrirlo con tests JVM (ver `HelpContentTest`)
 * y para que la lista sea la única fuente de verdad del FAQ que pinta [HelpScreen].
 *
 * Criterio de contenido: pocas preguntas, las que de verdad generan dudas o soporte (recepción
 * en segundo plano, PeerID, verificación, privacidad); cada respuesta de 2–4 frases. El manual
 * completo ([docs/MANUAL.md]) queda como referencia extensa.
 */
data class HelpItem(
    val category: String,
    val question: String,
    val answer: String,
)

object HelpContent {

    const val INTRO: String =
        "Un resumen de las dudas más frecuentes. Toca una pregunta para ver la respuesta."

    val items: List<HelpItem> = listOf(
        HelpItem(
            category = "Primeros pasos",
            question = "¿Necesito un número de teléfono o registrarme?",
            answer = "No. Krypta no pide teléfono, correo ni ninguna cuenta. Tu identidad se " +
                "crea sola en este móvil la primera vez que abres la app y vive únicamente en " +
                "tu dispositivo.",
        ),
        HelpItem(
            category = "Primeros pasos",
            question = "¿Qué es un PeerID y cómo lo comparto?",
            answer = "Tu PeerID es tu única seña de contacto en Krypta: es tu clave pública, no " +
                "un teléfono ni un correo. Compártelo desde Ajustes → Copiar o Compartir con " +
                "quien quiera escribirte. Quien lo tenga puede añadirte, pero no revela ningún " +
                "otro dato personal tuyo.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Quién puede leer mis mensajes y llamadas?",
            answer = "Solo tú y tu contacto. Todo va cifrado de extremo a extremo con una clave " +
                "que solo tenéis vosotros dos. Los nodos de Krypta y cualquier intermediario " +
                "ven únicamente datos cifrados: nunca el texto, las fotos ni el audio.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Cómo sé que hablo con la persona correcta y no con un impostor?",
            answer = "Abre el chat y entra en “Verificar identidad”. Verás un número de " +
                "seguridad de 60 dígitos: compáralo con el de la otra persona (en persona o por " +
                "llamada) o escanea su código QR. Si coincide en ambos móviles, nadie está en " +
                "medio; el contacto verificado muestra un escudo.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Por qué cada contacto tiene una forma y un color distintos?",
            answer = "El color y la forma del avatar (círculo, hexágono, pentágono…) se generan " +
                "a partir del PeerID del contacto, no del nombre que le pusiste tú. Sirven para " +
                "reconocerlo de un vistazo y como pista de seguridad: si un contacto conocido " +
                "cambiara de forma o color, podría ser señal de que su identidad cambió. La " +
                "letra es la inicial del nombre que tú elegiste.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Se pueden hacer capturas de pantalla de mis chats?",
            answer = "Mientras tienes un chat abierto, no: Krypta bloquea la captura y la " +
                "grabación de pantalla, sale en negro si alguien graba, y el chat tampoco " +
                "aparece en la vista de apps recientes. El bloqueo es solo de la pantalla de " +
                "chat; en el resto de la app (lista, ajustes, ayuda) puedes capturar como " +
                "siempre. Si quieres guardar una conversación, usa ⋮ → Capturar pantalla " +
                "dentro del chat; se guarda en Galería › Krypta, ya fuera del cifrado, así " +
                "que trátala como cualquier foto de tu móvil.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Puedo recuperar mi cuenta si pierdo o cambio de teléfono?",
            answer = "Solo si hiciste una copia de seguridad (Ajustes → Copia de seguridad). Sin " +
                "ella, perder el móvil significa perder tu PeerID, y tus contactos tendrían que " +
                "volver a añadirte y verificarte. Guarda la copia y su frase-clave en un lugar " +
                "seguro.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Qué pasa si le escribo a alguien que está desconectado?",
            answer = "El mensaje se guarda cifrado en el buzón del nodo y se entrega en cuanto " +
                "esa persona vuelve a conectarse, a menudo en segundos. El nodo solo guarda " +
                "datos cifrados: no puede leer el contenido.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Cómo hago una llamada de voz o de vídeo?",
            answer = "Abre el chat y pulsa el icono de teléfono; durante la llamada puedes " +
                "activar la cámara con el icono de vídeo. Las llamadas también van cifradas de " +
                "extremo a extremo y necesitan que ambos estéis conectados a la vez.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Cómo respondo a un mensaje concreto y no al último?",
            answer = "Desliza el mensaje hacia la derecha, o mantenlo pulsado y toca la flecha " +
                "de responder. Encima del cuadro de escribir verás a quién estás respondiendo, " +
                "con una ✕ para descartarlo. Vale para cualquier mensaje —texto, foto, nota de " +
                "voz o archivo— y puedes responder con lo que quieras, no solo con texto. En el " +
                "chat, tocar la cita te lleva al mensaje original. Por la red solo viaja una " +
                "referencia interna al mensaje citado, nunca una copia de su contenido: por eso, " +
                "si esa persona ya había vaciado el chat, verá “Mensaje no disponible” en la cita.",
        ),
        HelpItem(
            category = "Mensajes y llamadas",
            question = "¿Puedo enviar GIF, stickers o emoji grandes desde el teclado?",
            answer = "Sí. Abre la pestaña de GIF o de stickers de tu teclado y toca el que " +
                "quieras: los GIF llegan animados y los stickers conservan su fondo " +
                "transparente. Un GIF puede pesar bastante, así que se envía por partes y " +
                "puede tardar unos segundos más que un mensaje de texto; el límite es de 4 MB.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "¿Puedo quitar el aviso fijo de “Conectado — recibiendo mensajes”?",
            answer = "Ese aviso es lo que mantiene a Krypta conectada con la app cerrada: sin él, " +
                "Android detendría el servicio y dejarías de recibir mensajes y llamadas al " +
                "instante (Krypta no usa los servidores de notificaciones de Google). El sistema " +
                "obliga a mostrarlo mientras el servicio funciona. Sí puedes ocultarlo tú: " +
                "deslízalo para descartarlo, o mantenlo pulsado y desactiva el canal “Servicio en " +
                "segundo plano”. Krypta seguirá funcionando igual; solo dejarás de ver el aviso.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "No me llegan los mensajes con la app cerrada, ¿qué hago?",
            answer = "Muchos móviles “congelan” las apps para ahorrar batería y eso corta la " +
                "recepción. Ve a Ajustes → Recepción en segundo plano, pulsa “Ajustes del " +
                "sistema” y permite a Krypta: batería sin restricciones, inicio automático y " +
                "notificaciones. Con eso los avisos llegan aunque no tengas la app abierta.",
        ),
        HelpItem(
            category = "Privacidad y seguridad",
            question = "¿Puedo copiar un mensaje? ¿Es seguro?",
            answer = "Sí: mantén pulsado el mensaje y elige “Copiar”. Ten en cuenta que al " +
                "copiarlo el texto sale del cifrado de extremo a extremo y pasa a manos del " +
                "portapapeles del móvil, igual que ocurre al guardar una captura en la galería: " +
                "otras apps podrían leerlo al pegarlo. Krypta lo marca como contenido sensible " +
                "para que Android no lo muestre en la vista previa del portapapeles, pero si el " +
                "mensaje es delicado, cópialo solo cuando de verdad lo necesites.",
        ),
        HelpItem(
            category = "Problemas frecuentes",
            question = "Uso “Cerrar todo” en aplicaciones recientes, ¿afecta a Krypta?",
            answer = "Sí, y es distinto de ocultar el aviso: “Cerrar todo” cierra Krypta de " +
                "verdad y deja de recibir hasta que vuelve a levantarse sola unos segundos " +
                "después. Para evitarlo, abre recientes, mantén pulsada la tarjeta de Krypta y " +
                "usa el candado: así queda fuera de “Cerrar todo”. Otras apps de mensajería no " +
                "lo necesitan porque usan los servidores de Google; Krypta no los usa, y por " +
                "eso depende de seguir viva en tu móvil.",
        ),
    )

    /** Categorías en el orden en que deben mostrarse, preservando el de [items]. */
    val categoriesInOrder: List<String> = items.map { it.category }.distinct()
}
