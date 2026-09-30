# WebAsset Scraper

Aplicación Android + web para ingresar la URL de una página pública, abrirla en un navegador automatizado, registrar sus respuestas de red y guardar los recursos descargables en un ZIP.

## Estructura

- `index.html`: interfaz web.
- `app/`: aplicación Android Kotlin/Jetpack Compose.
- `capture-service/`: API Python/FastAPI y Chromium/Playwright compartida por web y Android.

## Qué captura

El servicio observa respuestas HTTP(S) que genera la página al cargarla y durante un recorrido corto de desplazamiento vertical para activar contenido lazy-loaded. Clasifica cada respuesta usando el MIME real, guarda el cuerpo binario real y ofrece un ZIP con `capture-manifest.json`. Los fallos y los límites se muestran como tales; no se generan tamaños aleatorios ni archivos sustitutos.

No inicia sesión ni recibe cookies del usuario. No intenta eludir DRM, autenticación ni otras protecciones. El tráfico que requiera credenciales o interacciones que no ocurren al cargar la página puede quedar fuera. Las respuestas HTTP parciales (206/range) se señalan y no se presentan como archivos completos; todavía no se reensamblan. Capturas temporales viven en memoria y vencen a los 15 minutos.

## Ejecutar servicio y web en local

Requiere Docker. Desde la raíz del repositorio:

```sh
docker build -f capture-service/Dockerfile -t webasset-capture .
docker run --rm -p 8000:8000 webasset-capture
```

Abrí `http://localhost:8000`. La API ofrece `GET /api/health`, `POST /api/captures` y `POST /api/exports`.

Para desarrollo sin Docker, usá Python 3.11 o posterior:

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r capture-service/requirements.txt
python -m playwright install chromium
cd capture-service
uvicorn app:app --reload --host 127.0.0.1 --port 8000
```

## Android

Abrí la raíz del repositorio en Android Studio. Para conectar la app a un servicio HTTPS configurado, pasá su URL base al build:

```sh
gradle assembleDebug -PcaptureApiBaseUrl=https://TU-SERVICIO
```

La URL es configuración, no una clave. El valor queda vacío por defecto para no apuntar silenciosamente a un servidor inexistente. La captura Android y la web usan la misma API.

## Pruebas

```sh
PYTHONPATH=capture-service python -m unittest discover -s capture-service/tests -v
```

## Seguridad y despliegue

El servicio bloquea destinos no públicos, puertos personalizados y métodos HTTP con efectos de escritura, y aplica límites de tiempo, cantidad y tamaño. **Esta primera versión está pensada para desarrollo/autohospedaje, no para exponer una API abierta en Internet**: antes de un despliegue público hacen falta autenticación, cuotas/rate limiting y aislamiento de red de salida además de estas validaciones.

## GitHub Actions y Pages

- Cada cambio en  ejecuta el build del APK de depuración; el archivo queda como artefacto de Actions durante 14 días.
- GitHub Pages publica la interfaz web estática. Pages no ejecuta el capturador Playwright.
- Para conectar ambas versiones, configurá la variable de repositorio  con la URL HTTPS del servicio de captura. Si queda vacía, la interfaz lo informa y no intenta fingir una extracción.
- El servicio no usa base de datos; las capturas se mantienen temporalmente en memoria. Aun así, necesita un host de contenedores para procesar páginas.
