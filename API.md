# API propia de WebAsset Scraper

La app tiene una API HTTPS compartida: `https://web-asset-scraper-capture.onrender.com`. Ya está protegida con una clave privada configurada en el servidor. No necesitás comprar ni pedir una API externa. El servidor está en Render Free: no tiene costo mensual, pero puede dormirse o reiniciarse; las capturas interactivas guardadas en memoria se pierden con reinicios.

## Dar acceso a otra Zapia

Pasale la URL base y la clave privada por el almacén seguro de credenciales de esa Zapia, no en un mensaje público ni en código. La clave se envía como encabezado HTTPS `X-API-Key`. No la incluyas en parámetros de URL, Pages o el APK. Si una Zapia no puede guardar secretos de forma segura, no le compartas la clave.

## Capturar recursos de una página pública

`POST https://web-asset-scraper-capture.onrender.com/api/captures`

Encabezados: `Content-Type: application/json` y `X-API-Key: <clave privada>`. Cuerpo: `{"url":"https://sitio-publico/"}`. La respuesta contiene `captureId`, inventario de recursos descargables y advertencias.

## Descargar ZIP

`POST https://web-asset-scraper-capture.onrender.com/api/exports` con los mismos encabezados. Cuerpo: `{"captureId":"...","assetIds":["..."]}`. Responde un ZIP. Las capturas viven hasta 15 minutos y se borran antes si el servicio se reinicia.

## Sesión interactiva

La API también tiene rutas `/api/sessions` para abrir una demo, tomar capturas de pantalla, tocar controles y guardar respuestas de red. Cada llamada necesita el mismo encabezado `X-API-Key`. En el plan Free estas sesiones pueden perderse si Render reinicia Chromium; para capturas largas, el runner temporal gratuito de GitHub Actions es más confiable.

`GET /api/health` es público y solo confirma que el servicio responde; no requiere ni revela la clave.

El servicio bloquea destinos privados y solo admite tráfico público; no inicia sesión ni evita DRM, autenticación o protecciones del proveedor. Revisá el manifiesto: que una petición termine bien no implica que todos los recursos estén disponibles.
