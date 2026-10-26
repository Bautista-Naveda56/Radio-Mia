# Radio Mía — Documento de continuidad para Google AI Studio

## Propósito de este documento

Este archivo contiene todo el contexto necesario para continuar el desarrollo de **Radio Mía** en un nuevo chat de Google AI Studio. El chat anterior se bloqueó, pero ya existe una aplicación y el usuario también compartió el código del proyecto.

El nuevo chat debe leer este documento antes de modificar el proyecto. No debe comenzar una aplicación distinta ni borrar lo que ya funciona. Debe continuar sobre el código existente, revisar su estado real y corregir o completar la implementación.

---

# 1. Idea general del producto

**Radio Mía** es una aplicación Android que transforma la música local del teléfono en una experiencia parecida a una radio FM argentina personalizada.

La aplicación no es una radio online tradicional y no transmite canciones desde Internet. Su función principal es:

1. Leer las canciones que el usuario ya tiene descargadas en el celular.
2. Reconocer sus metadatos: título, artista, álbum, duración y portada cuando estén disponibles.
3. Reproducirlas como una programación de radio personal.
4. Insertar intervenciones breves de un locutor virtual.
5. Bajar suavemente el volumen de la canción mientras habla el locutor.
6. Volver a subir la música cuando termina la intervención.
7. Generar el texto y la voz del locutor usando Groq.
8. Continuar funcionando con un audio de respaldo si no hay conexión o si Groq falla.

La experiencia debe tener una onda de radio pop argentina moderna, cálida, ágil y entretenida. La inspiración general es la dinámica de emisoras musicales como Radio Disney, pero la aplicación debe tener una identidad propia y no copiar nombres, logos, frases, voces, cortinas ni diseños de radios existentes.

La identidad visual elegida es la de un **estudio FM nocturno**, con fondo oscuro, acentos ámbar y coral, indicador luminoso “RADIO MÍA — AL AIRE” y elementos visuales inspirados en una cabina de radio.

No debe aparecer una frecuencia como “99.5 MHz”. La aplicación debe mostrar solamente:

> RADIO MÍA — AL AIRE

---

# 2. Estado del proyecto anterior

El chat anterior ya generó una primera versión Android de la aplicación.

El proyecto fue creado con:

- Kotlin.
- Jetpack Compose.
- Jetpack Media3 / ExoPlayer.
- MediaStore para leer música local.
- MediaSessionService para reproducción en segundo plano.
- Dos reproductores de audio separados: uno para música y otro para la voz del locutor.
- Ducking manual de volumen mediante fades graduales.
- Pistas de demostración para el emulador.
- Un archivo de respaldo llamado `demo_locutor.wav` o una solución equivalente.
- Panel de depuración plegable.
- Controles deslizantes para volumen atenuado y duración del fade.

El nuevo chat debe revisar el código existente antes de reemplazar cualquier archivo. Debe conservar todo lo que ya funcione.

Si alguna parte todavía está incompleta, debe corregirla de forma incremental. No debe crear una aplicación paralela ni eliminar la aplicación actual para empezar desde cero.

---

# 3. Primera versión que ya se quería validar

La primera versión tenía que demostrar esta secuencia:

1. Se reproduce una canción local o una pista de demostración.
2. La canción continúa reproduciéndose.
3. El usuario toca “Probar locutor”.
4. El volumen de la canción baja gradualmente.
5. Se reproduce `demo_locutor.wav` o un audio de respaldo válido en un segundo reproductor.
6. La canción continúa sonando por debajo de la voz.
7. Cuando termina la voz, la música vuelve gradualmente al volumen anterior.
8. La canción continúa desde el mismo punto.

Esta prueba es fundamental. La integración con Groq no debe romperla.

---

# 4. Objetivo actual: integrar todo con Groq

El objetivo actual es integrar la aplicación Android con un backend seguro que utilice Groq.

Groq debe utilizarse para:

## 4.1 Generación de texto

Usar un modelo de lenguaje disponible en Groq, preferentemente un modelo Llama disponible en la cuenta, para generar el guion breve del locutor.

No asumir a ciegas un modelo o una voz concreta. Revisar la configuración o modelos disponibles en la cuenta y elegir modelos compatibles realmente disponibles.

El texto generado debe ser corto y apto para convertirse en una intervención de radio de aproximadamente 5 a 15 segundos.

## 4.2 Text-to-speech

Enviar el texto generado al endpoint Text-to-Speech de Groq y usar una voz en español disponible en la cuenta del usuario.

El usuario confirmó que en su cuenta de Groq sí aparecen voces en español. Por lo tanto, revisar las voces disponibles y configurar una voz en español adecuada. No sustituirla automáticamente por una voz inglesa.

Si el modelo o la voz exactos no están disponibles en el entorno actual, el backend debe devolver un error claro y Android debe usar `demo_locutor.wav` como respaldo.

## 4.3 Speech-to-text, para una etapa posterior

Groq también puede utilizarse más adelante para transcribir mensajes de voz de oyentes. Esta función no debe bloquear la integración principal de la radio.

No es obligatorio implementar speech-to-text en la primera integración si eso aumenta innecesariamente el riesgo. Se puede dejar una interfaz preparada para agregarlo después.

## 4.4 Visión, para una etapa posterior

Groq dispone de capacidades de visión en modelos multimodales. Esto podría utilizarse más adelante para analizar portadas, imágenes o flyers de recitales, pero no es necesario para que la radio funcione.

No agregar visión al núcleo de esta implementación salvo que ya exista código preparado y no complique la estabilidad.

---

# 5. Regla crítica de seguridad para GROQ_API_KEY

El usuario proporcionará un archivo `.env` que contiene una variable similar a:

```env
GROQ_API_KEY=la_clave_real_del_usuario
```

Ese archivo debe tratarse como información secreta.

## La clave debe:

- Utilizarse únicamente en el backend.
- Leerse mediante `process.env.GROQ_API_KEY`.
- Cargarse con `dotenv` en desarrollo local si corresponde.
- Configurarse como secreto o variable de entorno en producción.
- No copiarse al código Kotlin.
- No copiarse a Jetpack Compose.
- No copiarse a recursos Android.
- No copiarse al frontend.
- No incluirse en el APK.
- No enviarse desde Android directamente a Groq.
- No aparecer en logs.
- No aparecer en respuestas del backend.
- No aparecer en capturas, mensajes, archivos generados ni resúmenes.

## Archivos requeridos

El backend debe tener o crear:

```text
backend/
├── .env
├── .env.example
└── .gitignore
```

El contenido de `.gitignore` debe incluir:

```text
.env
```

El contenido de `.env.example` debe ser solamente un ejemplo sin la clave real:

```env
GROQ_API_KEY=pegá_tu_clave_aquí
```

No imprimir el contenido real del `.env` para verificarlo. No ejecutar comandos que muestren la clave completa. Si se necesita comprobar que existe, informar únicamente si la variable está presente o ausente.

Si el archivo `.env` fue adjuntado al proyecto, mantener su contenido oculto y no incluirlo en el texto de respuesta.

---

# 6. Arquitectura deseada

La arquitectura debe quedar separada de esta manera:

```text
Aplicación Android
    |
    | envía solamente metadatos de la canción
    v
Backend seguro de Radio Mía
    |
    | lee process.env.GROQ_API_KEY
    v
API de Groq
    |
    +--> modelo de texto / Llama
    |
    +--> Text-to-Speech con voz en español
    v
Backend devuelve texto y audio a Android
    |
    v
Android hace ducking y reproduce la voz sobre la música
```

Android no debe llamar directamente a `api.groq.com` utilizando una clave secreta.

El backend puede implementarse con Node.js y TypeScript. Si el proyecto ya tiene una estructura concreta de backend, conservarla. Si no existe, crear una estructura simple, clara y compatible con el entorno disponible.

Opciones aceptables para el backend:

- Servicio Node.js/TypeScript.
- Función segura de Firebase.
- Servicio compatible con Cloud Run.

Elegir la opción que sea realmente compatible con el proyecto existente y documentar cómo se inicia.

---

# 7. Flujo completo de una intervención

## Paso 1: detectar la canción

Android conoce los siguientes datos de la canción actual:

- Título.
- Artista.
- Álbum.
- Género si está disponible.
- Duración.
- Estilo de radio elegido.
- Tipo de intervención.
- Duración máxima deseada.

## Paso 2: solicitar una intervención

Android envía al backend solamente los metadatos necesarios.

Ejemplo de solicitud:

```json
{
  "title": "Persiana Americana",
  "artist": "Soda Stereo",
  "album": "Signos",
  "genre": "Rock argentino",
  "radioStyle": "Radio pop argentina",
  "interventionType": "presentacion",
  "maxDurationSeconds": 12
}
```

No enviar la canción ni el archivo de audio del usuario al backend.

## Paso 3: generar el guion

El backend consulta a Groq y solicita una respuesta estructurada.

La respuesta preferida es:

```json
{
  "type": "presentacion",
  "text": "Seguimos con un clásico que no necesita demasiada presentación: Soda Stereo y Persiana Americana.",
  "tone": "energico",
  "estimatedDurationSeconds": 8
}
```

La respuesta debe validarse antes de continuar.

## Paso 4: generar audio

El backend envía el campo `text` al endpoint Text-to-Speech de Groq y utiliza una voz en español disponible.

El backend devuelve a Android:

- Texto generado.
- Audio generado.
- Tipo de intervención.
- Duración estimada.
- Identificador o clave de caché.

El formato de audio debe ser compatible con Media3/ExoPlayer. Si es necesario, convertirlo o documentar el formato utilizado.

## Paso 5: hacer ducking

Android debe:

1. Guardar el volumen maestro actual.
2. Bajar gradualmente el volumen de la música.
3. Reproducir la voz en el segundo reproductor.
4. Mantener la música reproduciéndose desde el mismo punto.
5. Esperar el final de la voz.
6. Subir gradualmente la música al volumen correcto.
7. Respetar cualquier cambio manual de volumen hecho por el usuario durante la intervención.

## Paso 6: respaldo

Si ocurre cualquiera de estos problemas:

- No hay conexión.
- El backend no responde.
- Groq devuelve un error.
- La voz configurada no existe.
- El audio generado no se puede reproducir.
- Se alcanza un timeout.

Entonces Android debe utilizar `demo_locutor.wav` o el audio de respaldo válido incluido en la app.

La música debe continuar funcionando aunque Groq esté caído.

---

# 8. Estilo del locutor

El prompt interno del modelo debe describir al locutor así:

```text
Sos el locutor de Radio Mía, una radio argentina personal, moderna y entretenida.
Hablás en español rioplatense natural, con calidez y energía.
No exageres el lunfardo ni uses expresiones forzadas.
Sonás como un locutor de radio pop argentina, no como un asistente virtual.
Tus intervenciones son breves, naturales y variadas.
No copies frases, nombres, voces ni identidad de Radio Disney ni de ninguna otra radio.
No digas que sos una inteligencia artificial.
No inventes datos sobre canciones, artistas, álbumes, fechas o hechos históricos.
Si no hay información confiable, hacé una presentación genérica.
Respetá la duración máxima solicitada.
Devolvé únicamente el JSON pedido.
```

Tipos de intervención permitidos:

- `presentacion`: presenta la canción siguiente.
- `continuidad`: mantiene la sensación de radio.
- `cambio_clima`: anuncia un cambio de energía.
- `dato_curioso`: solo si existe información confiable.
- `mensaje_oyente`: para una función futura.

Ejemplos:

```text
Seguimos con un clásico del rock argentino: Soda Stereo y Persiana Americana.
```

```text
Bajamos un cambio y nos metemos en una canción ideal para escuchar de noche.
```

```text
Esto es Radio Mía. La música sigue y nosotros también.
```

No generar intervenciones largas. No hablar antes de cada canción obligatoriamente. La frecuencia de intervención debe poder configurarse.

---

# 9. Funciones Android que deben conservarse

## Biblioteca musical

- Leer canciones locales mediante `MediaStore`.
- Usar permisos modernos según la versión de Android.
- `READ_MEDIA_AUDIO` en Android 13 o superior.
- `READ_EXTERNAL_STORAGE` cuando corresponda en versiones anteriores.
- Mostrar título, artista, álbum, duración y portada.
- Permitir búsqueda.
- Permitir actualizar la biblioteca.
- Incluir pistas demo instrumentales libres de derechos para el emulador.

## Reproducción

- Usar Media3/ExoPlayer.
- Usar `MediaSessionService`.
- Usar servicio de primer plano cuando corresponda.
- Mostrar controles multimedia del sistema.
- Continuar al bloquear la pantalla.
- Continuar al cambiar de aplicación.
- Mantener música y voz en reproductores separados.
- No pausar la canción durante una intervención.

## Interfaz

Debe mantenerse una interfaz original con:

- Nombre `Radio Mía`.
- Texto `RADIO MÍA — AL AIRE`.
- Fondo oscuro.
- Acentos ámbar y coral.
- Indicador luminoso de locutor.
- Portada de la canción.
- Título y artista.
- Barra de progreso.
- Botones anterior, reproducir/pausar y siguiente.
- Botón `Probar locutor`.
- Botón o control `Intervención del DJ`.
- Interruptor para activar o desactivar el DJ.
- Selector de estilo de radio.
- Sliders de volumen y duración del fade.
- Panel de depuración plegable.

Estilos disponibles:

- Radio pop argentina.
- Radio nocturna.
- Rock argentino.
- Radio energética.
- Radio tranquila.

---

# 10. Estados visibles para el usuario

La aplicación debe mostrar estados claros sin exponer secretos:

- `Listo`.
- `Reproduciendo`.
- `Generando intervención`.
- `Generando voz`.
- `Locutor al aire`.
- `Recuperando volumen`.
- `Sin conexión`.
- `Usando audio de respaldo`.
- `Error temporal de Groq`.

No mostrar mensajes técnicos confusos como respuesta principal. Los detalles técnicos pueden quedar dentro del panel de depuración.

---

# 11. Caché y rendimiento

Implementar una caché básica para no generar repetidamente exactamente la misma intervención.

La clave de caché puede construirse con:

- Título.
- Artista.
- Álbum.
- Estilo.
- Tipo de intervención.
- Versión del prompt.

El caché no debe guardar la clave de Groq.

No generar muchas intervenciones por adelantado sin necesidad. Generar una intervención cuando corresponda y mantener el audio de respaldo listo.

Configurar timeouts razonables. No bloquear la reproducción musical esperando indefinidamente una respuesta de Internet.

---

# 12. Manejo de errores

El backend debe manejar:

- Falta de `GROQ_API_KEY`.
- Modelo inexistente.
- Voz inexistente.
- Respuesta inválida.
- JSON mal formado.
- Error HTTP.
- Timeout.
- Límite de uso.
- Audio vacío o corrupto.
- Entrada demasiado larga.

Android debe manejar:

- Backend inaccesible.
- Sin Internet.
- Audio imposible de reproducir.
- Cambio de canción durante la intervención.
- Pausa durante la intervención.
- Segunda pulsación del botón.
- Rotación de pantalla.
- Pantalla bloqueada.
- Servicio detenido por el sistema.

Nunca dejar la música permanentemente atenuada por una excepción.

---

# 13. Cancelación de intervenciones

Si el usuario toca nuevamente `Probar locutor` mientras el locutor está hablando:

1. Cancelar la intervención actual.
2. Detener el reproductor de voz.
3. Cancelar las corrutinas de fade.
4. Restaurar el volumen de música gradualmente.
5. Evitar que el audio anterior vuelva a ejecutarse al terminar una operación pendiente.
6. Liberar recursos correctamente.
7. Volver al estado `Listo` o `Reproduciendo`.

No permitir dos voces simultáneas.

---

# 14. Audio de demostración

Usar únicamente pistas instrumentales o audios libres de derechos.

No incluir canciones comerciales del usuario dentro del APK.

Si `demo_locutor.wav` ya existe, conservarlo y comprobar que se pueda reproducir. Si no existe, no dejar referencias rotas. Crear una solución válida de demostración o indicar claramente que debe incorporarse un archivo de audio válido.

No crear archivos falsos con nombres que luego no puedan abrirse.

---

# 15. Qué NO debe hacer el nuevo chat

No debe:

- Crear una app nueva desde cero si ya existe una app.
- Borrar la funcionalidad actual sin revisar el código.
- Meter `GROQ_API_KEY` en el APK.
- Copiar el `.env` dentro de Android.
- Mostrar la clave en el chat.
- Escribir la clave en logs.
- Enviar música personal al backend.
- Integrar Spotify o YouTube Music en esta etapa.
- Usar canciones comerciales de demostración.
- Usar una frecuencia de radio real o inventada.
- Copiar Radio Disney.
- Pausar la canción durante la voz.
- Bloquear la reproducción si falla Groq.
- Dejar la música atenuada después de un error.
- Crear dos intervenciones simultáneas.
- Asumir modelos o voces que no estén disponibles.
- Implementar funciones de visión o mensajes sociales si complican la estabilidad principal.

---

# 16. Pruebas obligatorias

Antes de informar que terminó, ejecutar o verificar lo siguiente:

## Compilación

- Compilar Android.
- Compilar backend.
- Corregir errores de Kotlin.
- Corregir errores de Compose.
- Corregir errores de Media3.
- Corregir errores de TypeScript o Node.js.

## Audio

- Reproducir una pista demo.
- Reproducir una canción local.
- Probar la voz de respaldo.
- Probar música y voz simultáneas.
- Verificar fade-down.
- Verificar fade-up.
- Verificar volumen maestro.
- Verificar cambio de volumen durante la intervención.
- Verificar cancelación.
- Verificar que no haya voces superpuestas.

## Segundo plano

- Bloquear la pantalla.
- Cambiar a otra aplicación.
- Usar controles multimedia del sistema.
- Pausar y reanudar.
- Cambiar de canción.

## Red y Groq

- Probar con Internet.
- Probar sin Internet.
- Probar backend caído.
- Probar clave ausente sin mostrarla.
- Probar respuesta inválida.
- Probar voz no disponible.
- Confirmar uso del audio de respaldo.
- Confirmar que la música no se detenga mientras se genera la voz.

## Seguridad

- Confirmar que `GROQ_API_KEY` no aparezca en archivos Android.
- Confirmar que no aparezca en el APK.
- Confirmar que no aparezca en logs.
- Confirmar que `.env` esté ignorado por Git.
- Confirmar que `.env.example` no contenga la clave real.

---

# 17. Entregables esperados

Al finalizar, dejar:

1. Aplicación Android actualizada.
2. Backend actualizado.
3. `.env.example` sin secretos.
4. `.gitignore` con `.env`.
5. README con instrucciones de instalación.
6. Instrucciones para iniciar el backend.
7. Instrucciones para configurar `GROQ_API_KEY` sin exponerla.
8. Instrucciones para configurar la URL del backend en Android.
9. Instrucciones para probar en emulador.
10. Instrucciones para instalar en teléfono físico.
11. Lista de modelos y voz efectivamente utilizados.
12. Descripción de fallback sin Internet.
13. Resumen de pruebas realizadas.
14. Lista de problemas pendientes, si queda alguno.

---

# 18. Prompt listo para pegar en el nuevo chat de Google AI Studio

Copiar y pegar el siguiente mensaje después de adjuntar este documento y el código existente:

```text
Leé completamente el archivo Radio_Mia_continuidad_y_prompt.md antes de modificar el proyecto.

También te comparto el código existente de Radio Mía. El chat anterior se bloqueó, por lo tanto debes continuar sobre este proyecto y no crear una aplicación nueva desde cero.

Voy a proporcionar un archivo .env con una variable GROQ_API_KEY. Tratá ese archivo como información secreta. No muestres su contenido, no lo imprimas, no lo copies a la aplicación Android, no lo incluyas en el APK, no lo pongas en Kotlin, Compose, recursos Android ni frontend. Usá la clave únicamente en el backend leyendo process.env.GROQ_API_KEY.

Si no existe, creá backend/.gitignore con:

.env

Creá también backend/.env.example sin la clave real:

GROQ_API_KEY=pegá_tu_clave_aquí

No muestres el valor de la clave en ningún resultado.

Primero inspeccioná el proyecto existente y determiná qué partes ya están implementadas. Conservá todo lo que funcione. Después implementá la integración completa de Radio Mía con un backend seguro y Groq.

Necesito que:

1. La app Android lea música local con MediaStore.
2. Reproduzca música con Media3/ExoPlayer.
3. Continúe en segundo plano y con la pantalla bloqueada mediante MediaSessionService.
4. Mantenga la canción sonando mientras habla el locutor.
5. Baje el volumen de la canción con fade-down.
6. Reproduzca la voz en un segundo reproductor.
7. Recupere el volumen con fade-up.
8. Respete cambios manuales de volumen.
9. Evite intervenciones superpuestas.
10. Use audio de respaldo si no hay Internet o Groq falla.
11. Genere el texto del locutor con un modelo disponible en Groq.
12. Genere la voz mediante el endpoint Text-to-Speech de Groq usando una voz en español realmente disponible en la cuenta.
13. No asumas nombres de modelos o voces que no existan: revisá la configuración disponible.
14. Mantenga el estilo de radio argentina moderna, cálida y entretenida.
15. No copie Radio Disney ni ninguna identidad existente.
16. Mantenga la interfaz “RADIO MÍA — AL AIRE”, sin frecuencias.
17. Mantenga el panel de depuración plegable.
18. Implemente timeout, errores, caché básica y fallback.
19. No envíe los archivos de música del usuario al backend, solamente metadatos.
20. No exponga GROQ_API_KEY en ningún cliente.

No integres Spotify, YouTube Music, visión ni mensajes sociales si complican la estabilidad principal. Speech-to-text puede quedar preparado para una etapa futura.

No uses canciones comerciales dentro del APK. Verificá que las pistas demo y demo_locutor.wav existan realmente o reemplazá las referencias rotas por una solución válida de demostración.

Antes de terminar:

- Compilá Android.
- Compilá el backend.
- Corregí errores.
- Probá música y voz simultáneas.
- Probá fade-down y fade-up.
- Probá bloqueo de pantalla y segundo plano.
- Probá cancelación de intervención.
- Probá sin Internet.
- Probá fallback cuando Groq falla.
- Verificá que la clave no aparezca en Android, APK, logs ni respuestas.

No informes simplemente que terminaste si no verificaste los puntos anteriores. Si algo no se puede implementar automáticamente en el entorno, explicá exactamente qué quedó pendiente y dejá el código preparado sin referencias rotas.
```

---

# 19. Instrucciones para adjuntar el `.env`

El usuario quiere proporcionar el `.env` con la clave real.

Si Google AI Studio ofrece un mecanismo de secretos o variables de entorno, utilizarlo preferentemente.

Si se adjunta el archivo como archivo privado del proyecto, usarlo únicamente para configurar el backend y no mostrar su contenido.

No copiar el contenido de `.env` en el chat.

No pegar la clave en el prompt.

No colocar el archivo `.env` dentro de la carpeta Android.

La ubicación conceptual correcta es:

```text
RadioMia/
├── android/
└── backend/
    ├── .env
    ├── .env.example
    └── .gitignore
```

---

# 20. Criterio final de éxito

Radio Mía se considera correctamente integrada cuando ocurre esta secuencia:

1. El usuario abre la app.
2. La app muestra canciones demo o canciones locales.
3. El usuario inicia una canción.
4. La canción sigue sonando aunque cambie de aplicación o bloquee la pantalla.
5. La app solicita una intervención.
6. El backend genera un texto mediante Groq.
7. Groq genera una voz en español disponible.
8. Android recibe el audio.
9. La música baja gradualmente.
10. El locutor habla sobre la música.
11. La música no se detiene.
12. La voz termina.
13. La música vuelve gradualmente al volumen correcto.
14. Si la red falla, la app usa `demo_locutor.wav`.
15. La clave de Groq permanece solamente en el backend.

La experiencia final debe sentirse como una radio personal argentina que conoce y presenta la música del usuario, no como una aplicación técnica de prueba.
