# Pruebas locales: autenticación, endpoints y escenarios

Resumen para la presentación. Cada afirmación remite al código o a la decisión que la respalda.

## 1. Autenticación: qué simulamos y por qué

**Qué pide el reto.** La tarea 3 pide identificar al menos tres vulnerabilidades del OWASP Top 10 y proponer cómo mitigarlas. No exige un mecanismo de autenticación concreto.

**Qué decidimos.**

| Decisión | Contenido |
|---|---|
| D22 | Un único mecanismo: JWT con OAuth2 Resource Server y RS256. Los scopes de clientes y productores van separados, y la identidad sale de un campo `client_id` del token. |
| D23 | En local, los tokens los emite una herramienta de desarrollo (`devToken`). En producción el emisor sería Keycloak o Cognito. |

**Los dos papeles de OAuth2.**

| Papel | En local | En producción | Qué hace |
|---|---|---|---|
| Servidor de autorización (emisor) | `devToken` | Keycloak o Cognito | Firma el JWT con su clave privada. Incluye `client_id`, `scope`, `iss`, `aud` y `exp`. |
| Servidor de recursos | Nuestro micro | Nuestro micro | Valida el token con la clave pública: firma, emisor, audiencia y caducidad. Después autoriza por scope. |

Lo que no cambia al pasar a producción es la validación del micro. Solo cambia quién firma el token.

**De dónde salen las claves.**

1. `./gradlew devToken --args="<client_id> <scopes...>"` crea, la primera vez, `.local/dev-jwt/private.pem` y `public.pem`. Son claves RSA de 2048 bits, y `.local/` está ignorado por git (`DevTokenTool.loadOrCreatePrivateKey`).
2. El micro, con el perfil `local`, lee `file:.local/dev-jwt/public.pem` al arrancar (`application-local.yaml`; `SecurityConfig.jwtDecoder`). La aplicación **no** genera claves. Si el fichero no existe, no arranca.
3. Sin perfil `local` no hay clave ni emisor por defecto, así que la aplicación no puede confiar en un emisor de desarrollo por accidente (`application.yaml`).

Consecuencias prácticas:

- Cada checkout tiene sus propias claves. El micro y `devToken` deben ejecutarse desde el mismo directorio.
- Hay que ejecutar `devToken` antes del primer arranque. Si se regeneran las claves, hay que reiniciar el micro.
- `devToken` sin `client_id` solo muestra el mensaje de uso y no genera claves.

**Límites de la simulación.**

- `devToken` no autentica a nadie: firma cualquier cliente y cualquier scope a quien tenga `private.pem`.
- La clave es fija, no rota y no hay revocación. Los tokens duran 12 horas.
- Para usar Keycloak o Cognito habría que validar contra su `jwk-set-uri`. Hoy el validador usa una clave fija (`NimbusJwtDecoder.withPublicKey`). Este cambio no está hecho.

## 2. Scopes: qué son y cómo se asocian a las operaciones

- Los nombres (`notifications:read`, `notifications:replay`, `subscriptions:read`, `subscriptions:write`, `events:write`) los define el proyecto en D22. OAuth2 (RFC 6749, sección 3.3) deja que cada servidor defina sus propios scopes. El formato `recurso:acción` es una convención, no una regla.
- Los scopes viajan en el campo `scope` del JWT, separados por espacios (RFC 9068).
- Spring convierte cada scope en un permiso con el prefijo `SCOPE_`. Después, `SecurityConfig` exige un permiso por método y ruta.
- Nada en el código interpreta el significado de la palabra: solo compara cadenas.
- El diseño separa lectura de escritura, el replay de la lectura (porque reenvía webhooks) y la ingesta (solo para productores). Así se aplica el mínimo privilegio, que es la mitigación de OWASP API5.

## 3. Cómo se casan las peticiones con las reglas

1. Se compara el método HTTP y la ruta, en orden, y gana la primera regla que encaja. La última regla es `anyRequest().denyAll()`.
2. `*` equivale a un solo segmento de la ruta. `**`, a varios. Los parámetros de consulta no cuentan.
3. Un método o una ruta que no existen (`PUT`, barra final, un segmento de más) caen en `denyAll`. Responden `403` con token y `401` sin token, nunca `404` ni `405`.
4. El `client_id` nunca va en la petición: se toma siempre del token (mitigación de OWASP API1, BOLA).

## 4. Tabla de endpoints

| # | Método y ruta | Token | Scope | Parámetros o cuerpo | Éxito | Otras respuestas | Cuidado |
|---|---|---|---|---|---|---|---|
| 1 | `POST /events` | Productor (`PRODUCER001`) | `events:write` | `{"event_id","client_id","event_type","content"}` | `202` con `notification_event_ids` | `409` si `event_id` ya existe; `403` con token de cliente | `event_id` es único entre todos los clientes; `EVT001` a `EVT010` ya existen. Se crea una notificación por cada suscripción activa que incluya ese `event_type`. |
| 2 | `POST /subscriptions` | Cliente | `subscriptions:write` | `{"url","event_types":[...]}` | `201` con `Location` y `secret` | `400` si el destino no está permitido; `409` en la suscripción activa número 51 | El `secret` solo aparece en esta respuesta. La URL debe ser `https`, en el puerto 443, con un host de `WEBHOOK_ALLOWED_HOSTS`. |
| 3 | `GET /subscriptions` | Cliente | `subscriptions:read` | — | `200` con `{"data":[...]}` | `429` | Sin paginación, máximo 100. Nunca incluye `secret`. |
| 4 | `GET /subscriptions/{subscription_id}` | Cliente | `subscriptions:read` | — | `200` | `404` si no existe o es de otro cliente | — |
| 5 | `PATCH /subscriptions/{subscription_id}` | Cliente | `subscriptions:write` | `{"active":false}` o `{"event_types":[...]}` | `200` | `400` si se intenta cambiar `url`; `404` | Es `PATCH`: un `PUT` responde `403`. |
| 6 | `DELETE /subscriptions/{subscription_id}` | Cliente | `subscriptions:write` | — | `204` | `404` | Desactiva, no borra. Las notificaciones que ya existen se siguen entregando (D67). |
| 7 | `GET /notification_events` | Cliente | `notifications:read` | `delivery_status`, `created_from`, `created_to`, `page`, `size` | `200` paginado | `400` si un filtro no es válido; `429` | Las fechas van en ISO-8601 UTC (`2026-10-30T00:00:00Z`). `size` es 20 por defecto y como máximo 100. |
| 8 | `GET /notification_events/{notification_event_id}` | Cliente | `notifications:read` | — | `200` con `attempts` | `404` | Cada intento trae ronda, número, fecha, `http_status`, `error_type` y latencia. |
| 9 | `POST /notification_events/{notification_event_id}/replay` | Cliente | `notifications:replay` | — | `202` con `round` y `delivery_status: pending` | `409` si no está en `failed`; `404` | Usa la URL guardada en la notificación, no la de la suscripción actual. |
| 10 | `GET /actuator/health`, `/actuator/info` | Ninguno | — | — | `200` | — | Público. |
| 11 | `/swagger-ui.html`, `/v3/api-docs` | Ninguno | — | — | `200` | — | Público. |
| — | Cualquier otro método o ruta | — | — | — | — | `403` con token; `401` sin token | Cae en `denyAll`. |

**Códigos de error.**

| Código | Significa |
|---|---|
| `401` | No hay token, está caducado o lo firmó la clave de otro checkout. |
| `403` | Falta el scope, o el método o la ruta no existen. |
| `404` | El recurso no existe o es de otro cliente. La respuesta es la misma a propósito. |
| `409` | `event_id` repetido, replay de una notificación que no está en `failed`, o la suscripción activa número 51. |
| `429` | El cliente hizo más de 100 peticiones en un minuto. |

**Tokens.**

| Uso | Comando |
|---|---|
| Cliente completo | `./gradlew devToken --args="CLIENT001 subscriptions:read subscriptions:write notifications:read notifications:replay"` |
| Productor | `./gradlew devToken --args="PRODUCER001 events:write"` |

## 5. Escenarios de prueba desde cero

Ejecución del 2026-11-02. El micro corre desde `develop` (`767236e`) con el perfil `local` en `:8080`. Base, Kafka y Grafana se levantaron desde cero (`docker compose down -v` y `up`).

Resultados observados:

- **Paso 0:**
  - El micro arrancó en 36,6 s y registró `Sample data loaded: 10 notification events, 3 seed subscriptions`.
  - Los datos de ejemplo quedaron con 7 notificaciones `completed` y 3 `failed`.
  - Con el token de cliente, `GET /subscriptions` respondió `200`. Con el de productor respondió `403`: la firma se acepta, pero falta el scope.
- **Paso 1:** las cuatro alertas quedaron `inactive` (Normal) según la API de Grafana.

Ejecución de los escenarios: 2026-11-03, con el mismo micro. Las horas están en UTC; Grafana las muestra en hora local (UTC−5). Las capturas se tomaron con `agent-browser` en modo headless, a 1600×900. Muestran solo el contenido de Grafana: sin barra de direcciones, perfil, tokens ni secretos. Están en `docs/process/evidence/grafana/2026-11-03/`.

- **Antes de empezar:** los tokens del día anterior respondían `401` porque duran 12 horas. Se volvieron a emitir con `devToken` desde el mismo checkout.
- **Paso 1, línea base:** las cuatro alertas en Normal (`o6-alert-rules.png`).
- **Paso 2, escenario A:**
  - A las 09:14:46 se envió `POST /events` para `CLIENT001`, con `credit_deposit`, y respondió `202` con una notificación.
  - El destino `webhook.example.com` no resuelve, así que los cinco intentos dieron `dns_error`: 09:14:46, 09:14:56, 09:15:27, 09:16:27 y 09:18:28. Las esperas fueron de 10 s, 30 s, 60 s y 120 s.
  - La alerta de intentos fallidos (con reintentos) se disparó antes del final, en cuanto hubo intentos con error.
  - Tras el quinto intento, la notificación quedó `failed` y saltó la alerta de notificaciones fallidas (`o6-failed-rate-firing.png`).
- **Paso 3, dead-letter:**
  - El quinto intento marcó `failed` en la misma escritura. El log muestra que el mensaje de `notification.dispatch-retry-3` *won't be retried* y pasa al dead-letter (D70).
  - La alerta del dead-letter se disparó a las 09:18:40 y se resolvió sola unos minutos después (`c-dead-letter-alert.png`).
- **Paso 4, escenario B:**
  - Se probaron seis destinos: `http`, `127.0.0.1`, `169.254.169.254` (metadatos de la nube), el puerto 8443, un host fuera de la lista permitida y una URL con credenciales.
  - Todos respondieron `400` con el mismo mensaje genérico.
  - La tabla `subscriptions` siguió con 3 filas.
- **Paso 5, Kafka parado:**
  - Kafka se paró a las 09:21:05.
  - `POST /events` respondió `202` igualmente: el evento queda en el outbox, en la misma transacción.
  - A las 09:22:28 la fila más antigua tenía 84 s y la alerta del outbox estaba disparada (`o6-outbox-age-firing.png`).
- **Paso 6, Kafka de vuelta:**
  - Kafka arrancó a las 09:23:01.
  - El outbox se vació a las 09:23:06 y la alerta volvió a Normal a las 09:23:27. El historial muestra Alerting con 66 s y después Normal (`o6-outbox-recovered.png`).
  - El evento no se perdió: su notificación siguió el ciclo normal de entrega (`retrying` por `dns_error`).

### Escenario C: éxitos y reintentos con túnel (2026-11-04, horas en UTC del 2026-11-05)

Preparación:

- El túnel rápido de cloudflared lo abrió el usuario; el clasificador de permisos no deja que el agente abra túneles de entrada. El túnel apunta al receptor de `:9200`.
- El micro se reinició en `:8080` con `WEBHOOK_ALLOWED_HOSTS=<host del túnel>`, pasado como variable de entorno. El host nunca se escribe en un fichero versionado.
- Durante la prueba había otras dos instancias del micro en `:8081` y `:8082`, del checkout principal, con la misma Kafka y la misma base. Las entregas se repartieron entre las tres por partición.

Resultados:

| Comprobación | Resultado |
|---|---|
| Suscripciones `/ok` y `/fail` de `CLIENT001`, con `payment_created` | `201` en las dos; el `secret` solo aparece en la respuesta de creación |
| `POST /events` de `CLIENT001`, a las 00:03:02 | `202` con tres notificaciones. La tercera es de otra suscripción a `/fail` que ya existía |
| Aislamiento: el mismo tipo para `CLIENT002` | `202` con `notification_event_ids: []`; ninguna notificación |
| `/ok` | `completed` en 1 intento, con `200` y 74 ms |
| `/fail`, dos notificaciones | 5 intentos con `500` (`http_500`) a +0 s, +13 s, +44 s, +105 s y +225 s; terminan en `failed` |
| Esperas entre reintentos | 10 s, 30 s, 60 s y 120 s más la latencia, como está configurado. Ya no se repite el desfase del 2026-10-29 |
| Dead-letter | Log `round 1 failed: the last attempt of the round was retryable` y luego `won't be retried. Sending to DLT` (D70) |
| Alertas | A las 00:08:07 estaban disparadas la de notificaciones fallidas, la de intentos fallidos y la del dead-letter |

**Hallazgo: los contadores chocan cuando hay más de una instancia.**

- Las métricas OTLP no llevan un identificador de instancia (`service.instance.id`). Las tres instancias escriben en la misma serie de Prometheus.
- Las muestras de `notification_delivery_attempts_total{outcome="retryable"}` alternan `5, 2, 3, 5, 2, 3…`, que son los totales de cada instancia.
- Prometheus toma cada bajada como un reinicio del contador y calcula una tasa falsa: 0,5 intentos por segundo y «360 mensajes en el dead-letter en la última hora». En esos mismos 20 minutos, la base de datos no registró ningún intento.
- Captura: `docs/process/evidence/grafana/2026-11-04/bug-counter-collision-dashboard.png`.
- Consecuencia: con más de una instancia, el panel y las alertas de tasa no son fiables. En producción habrá varias réplicas, así que hay que corregirlo: enviar `service.instance.id` en los atributos del recurso OTLP y agregar con `sum by` en los paneles y las alertas.
- Por el mismo motivo, falta una captura limpia de Grafana del escenario C. Para repetirla hay que dejar una sola instancia en marcha.

Plan:

| Paso | Qué se hace | Qué se espera | Captura |
|---|---|---|---|
| 0 | Base, Kafka y Grafana desde cero; micro desde `develop` con el perfil `local` | Se cargan los datos de ejemplo: 10 notificaciones y 3 suscripciones | — |
| 1 | Línea base | Las cuatro alertas en «Normal» | `o6-alert-rules.png` |
| 2 | Escenario A: evento para `CLIENT001`, con la suscripción de ejemplo apuntando a `webhook.example.com` | `failed` con `dns_error` (5 intentos) o con `invalid_destination` (1 intento) | `o6-failed-rate-firing.png` |
| 3 | Dead-letter, solo si el paso 2 dio `dns_error` | El quinto intento marca `failed` y el mensaje va directo al dead-letter (D70); la alerta dura 2 minutos | `c-dead-letter-alert.png` |
| 4 | Escenario B: destinos rechazados | `400` con un mensaje genérico, sin fila nueva | — |
| 5 | Kafka parado unos 90 s mientras se ingiere un evento | `POST /events` responde `202`; el outbox supera los 60 s y salta la alerta | `o6-outbox-age-firing.png` |
| 6 | Kafka levantado de nuevo | El outbox vuelve a 0 sin perder eventos | `o6-outbox-recovered.png` |
