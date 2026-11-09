# Propuesta de correcciones para la guía del ponente

Documento de referencia. No modifica `guia-presentacion-v2.html`: cada punto indica qué buscar en la guía y el texto propuesto para reemplazarlo. Revisa cada cambio antes de aplicarlo.

## 1. Antes de compartir el repositorio (acción manual, prioridad alta)

Estos puntos están en la sección "Antes de la reunión" de la guía y siguen abiertos:

- [ ] Quitar el host de túnel de `allowed-hosts` en `application-local.yaml`. El repositorio es público.
- [ ] Corregir el comentario del puerto local: dice 8081 y el valor es 8082.
- [ ] Corregir la frase de tolerancia de 5 minutos en `security-analysis.md`: la verificación la hace el receptor, no nuestro servicio.
- [ ] Revisar las capturas de IA antes de publicarlas.

## 2. Nombre del archivo del panel

Buscar: `presentacion-panel.html`

Reemplazar por: `presentacion-panel-v2.html`

Motivo: la v2 es la versión vigente del panel.

## 3. Dato de 23 minutos sin respaldo

Buscar en la sección 2.3, la pregunta "¿por qué la 'creación a resultado final' p95 llega a 23 minutos?"

Texto propuesto para la respuesta:

> El panel solo reporta la corrida de 5 minutos: p95 de 2 s para `completed` y de 40 s para `failed`. Antes de citar el valor de 23 minutos, confírmalo en la captura de la corrida larga. Si no aparece en la captura, no lo menciones.

Motivo: el panel no muestra ese valor y no hay evidencia en el repositorio.

## 4. Texto exacto del 409 de replay

Buscar en la sección 2.4 la respuesta que cita "solo se puede repetir un evento fallido".

Texto propuesto:

> El texto exacto del 409 lo define el dominio y no lo transcribimos aquí, igual que en el panel.

Alternativa: si quieres citarlo, primero confirma el texto en el código del controlador de replay.

## 5. Replay de notificaciones completed en la verificación en vivo

Buscar en la sección 2.4 la frase "Las pruebas de la verificación en vivo también lo registraron así para replays de notificaciones `completed`".

Texto propuesto (si no encuentras el dato en `docs/process/live-verification-2026-10-30.md`):

> Para notificaciones `completed` el replay responde 409; la prueba unitaria lo cubre. Confirmar en la verificación en vivo antes de decir que se observó en vivo.

## 6. Encabezado de la sección 3.1

Buscar: `Documentados (no implementados como control principal)`

Reemplazar por: `Documentados y mitigados (API3, API4, API8)`

Motivo: API4 incluye el límite de 100 peticiones por minuto, que está implementado y probado (E.4). El encabezado anterior lo contradice.

## Ya corregido en los decks

- Diapositiva de modos de falla (`failure-modes`), ES y EN: "alerta por antigüedad del outbox" pasó a "métrica de antigüedad del outbox (alerta pendiente de verificar)". Motivo: la guía marca la alerta como no verificada.
