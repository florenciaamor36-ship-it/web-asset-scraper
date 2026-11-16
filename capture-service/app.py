from __future__ import annotations

import asyncio
import io
import mimetypes
import os
import re
import secrets
import time
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, PlainTextResponse, Response
from pydantic import BaseModel, Field
from playwright.async_api import BrowserContext, Error as PlaywrightError, Response as PlaywrightResponse, async_playwright

from safe_url import UnsafeTarget, validate_request_url, validate_target_url

ROOT = Path(__file__).resolve().parent.parent
WEB_INDEX = ROOT / "index.html"
MAX_ASSETS = int(os.getenv("MAX_ASSETS", "300"))
MAX_ASSET_BYTES = int(os.getenv("MAX_ASSET_BYTES", str(16 * 1024 * 1024)))
MAX_TOTAL_BYTES = int(os.getenv("MAX_TOTAL_BYTES", str(96 * 1024 * 1024)))
CAPTURE_TIMEOUT_SECONDS = int(os.getenv("CAPTURE_TIMEOUT_SECONDS", "45"))
SESSION_TTL_SECONDS = int(os.getenv("SESSION_TTL_SECONDS", "900"))
MAX_CONCURRENT_CAPTURES = int(os.getenv("MAX_CONCURRENT_CAPTURES", "2"))


@dataclass
class CapturedAsset:
    id: str
    url: str
    file_name: str
    category: str
    mime_type: str
    size_bytes: int
    status: int
    content: bytes | None = field(default=None, repr=False)
    note: str | None = None

    def public(self) -> dict:
        return {
            "id": self.id,
            "url": self.url,
            "fileName": self.file_name,
            "category": self.category,
            "mimeType": self.mime_type,
            "sizeBytes": self.size_bytes,
            "status": self.status,
            "available": self.content is not None,
            "note": self.note,
        }


@dataclass
class CaptureSession:
    id: str
    url: str
    title: str
    created_at: float
    assets: list[CapturedAsset]
    warnings: list[str]


SESSIONS: dict[str, CaptureSession] = {}
CAPTURE_SEMAPHORE = asyncio.Semaphore(MAX_CONCURRENT_CAPTURES)


class CaptureRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)


class ExportRequest(BaseModel):
    captureId: str = Field(min_length=1, max_length=100)
    assetIds: list[str] = Field(min_length=1, max_length=MAX_ASSETS)


app = FastAPI(title="WebAsset Scraper Capture Service", version="0.1.0")
origins = [item.strip() for item in os.getenv("ALLOWED_ORIGINS", "").split(",") if item.strip()]
if origins:
    app.add_middleware(
        CORSMiddleware,
        allow_origins=origins,
        allow_credentials=False,
        allow_methods=["GET", "POST"],
        allow_headers=["Content-Type"],
    )


def _clean_old_sessions() -> None:
    cutoff = time.time() - SESSION_TTL_SECONDS
    for session_id in [key for key, item in SESSIONS.items() if item.created_at < cutoff]:
        SESSIONS.pop(session_id, None)


def _safe_filename(url: str, mime_type: str, index: int) -> str:
    path_name = urlsplit(url).path.rsplit("/", 1)[-1]
    path_name = path_name.split("?", 1)[0]
    path_name = re.sub(r"[^\w.() -]", "_", path_name, flags=re.UNICODE).strip(" .")[:100]
    if not path_name or path_name in {".", ".."}:
        extension = mimetypes.guess_extension(mime_type.split(";", 1)[0].strip()) or ".bin"
        path_name = f"resource_{index}{extension}"
    if "." not in path_name.rsplit("/", 1)[-1]:
        extension = mimetypes.guess_extension(mime_type.split(";", 1)[0].strip())
        if extension:
            path_name += extension
    return path_name


def _category(mime_type: str, url: str) -> str:
    mime = mime_type.split(";", 1)[0].strip().lower()
    path = urlsplit(url).path.lower()
    if mime.startswith("image/") or path.endswith((".ico", ".svg")):
        return "IMAGE"
    if mime.startswith("audio/"):
        return "AUDIO"
    if mime.startswith("video/"):
        return "VIDEO"
    if mime in {"font/woff", "font/woff2", "application/font-woff", "application/vnd.ms-fontobject"} or path.endswith((".woff", ".woff2", ".ttf", ".otf")):
        return "FONT"
    if "javascript" in mime or path.endswith((".js", ".mjs")):
        return "SCRIPT"
    if mime.startswith("text/") or "json" in mime or "xml" in mime or path.endswith((".css", ".json", ".xml", ".wasm")):
        return "DATA"
    return "OTHER"


def _session_or_404(capture_id: str) -> CaptureSession:
    _clean_old_sessions()
    session = SESSIONS.get(capture_id)
    if not session:
        raise HTTPException(status_code=404, detail="Capture expired or not found; scan the page again")
    return session


@app.get("/api/health")
async def health() -> dict:
    return {"ok": True, "service": "webasset-capture", "version": "0.1.0"}


@app.get("/api")
async def api_info() -> dict:
    return {"name": "WebAsset Scraper API", "capture": "POST /api/captures", "export": "POST /api/exports"}


@app.post("/api/captures")
async def capture_page(payload: CaptureRequest) -> dict:
    try:
        target_url = await asyncio.to_thread(validate_target_url, payload.url)
    except UnsafeTarget as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc

    assets_by_url: dict[str, CapturedAsset] = {}
    body_tasks: set[asyncio.Task] = set()
    total_bytes = 0
    warnings: list[str] = []
    page_title = urlsplit(target_url).hostname or "Web Page"
    started = time.monotonic()

    async def record_response(response: PlaywrightResponse) -> None:
        nonlocal total_bytes
        request_url = response.url
        if not request_url.startswith(("http://", "https://")) or request_url in assets_by_url:
            return
        if len(assets_by_url) >= MAX_ASSETS:
            return
        try:
            headers = await response.all_headers()
            mime_type = headers.get("content-type", "application/octet-stream")
            status = response.status
            content_length = int(headers.get("content-length", "0") or "0")
            if status == 206:
                asset = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime_type, len(assets_by_url) + 1), _category(mime_type, request_url), mime_type, content_length, status, note="Partial byte-range response; it is not reconstructed into a complete file")
            elif status < 200 or status >= 300:
                asset = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime_type, len(assets_by_url) + 1), _category(mime_type, request_url), mime_type, 0, status, note="HTTP response was not successful")
            elif content_length > MAX_ASSET_BYTES:
                asset = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime_type, len(assets_by_url) + 1), _category(mime_type, request_url), mime_type, content_length, status, note="Skipped: per-file size limit")
            else:
                body = await response.body()
                if len(body) > MAX_ASSET_BYTES:
                    asset = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime_type, len(assets_by_url) + 1), _category(mime_type, request_url), mime_type, len(body), status, note="Skipped: per-file size limit")
                elif total_bytes + len(body) > MAX_TOTAL_BYTES:
                    asset = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime_type, len(assets_by_url) + 1), _category(mime_type, request_url), mime_type, len(body), status, note="Skipped: capture size limit")
                else:
                    total_bytes += len(body)
                    asset = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime_type, len(assets_by_url) + 1), _category(mime_type, request_url), mime_type, len(body), status, content=body)
            assets_by_url[request_url] = asset
        except (PlaywrightError, asyncio.TimeoutError, ValueError) as exc:
            if request_url not in assets_by_url and len(assets_by_url) < MAX_ASSETS:
                mime = "application/octet-stream"
                assets_by_url[request_url] = CapturedAsset(secrets.token_hex(8), request_url, _safe_filename(request_url, mime, len(assets_by_url) + 1), _category(mime, request_url), mime, 0, 0, note=f"Could not read response body: {type(exc).__name__}")

    async def safe_route(route) -> None:
        request = route.request
        if request.url.startswith(("data:", "blob:", "about:")):
            await route.continue_()
            return
        if request.method not in {"GET", "HEAD", "OPTIONS"}:
            await route.abort()
            return
        try:
            await asyncio.to_thread(validate_request_url, request.url)
        except UnsafeTarget:
            await route.abort()
            return
        await route.continue_()

    async with CAPTURE_SEMAPHORE:
        try:
            async with async_playwright() as playwright:
                browser = await playwright.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
                context: BrowserContext = await browser.new_context(ignore_https_errors=False, accept_downloads=False)
                await context.route("**/*", safe_route)
                page = await context.new_page()

                def on_response(response: PlaywrightResponse) -> None:
                    task = asyncio.create_task(record_response(response))
                    body_tasks.add(task)
                    task.add_done_callback(body_tasks.discard)

                page.on("response", on_response)
                try:
                    await page.goto(target_url, wait_until="domcontentloaded", timeout=CAPTURE_TIMEOUT_SECONDS * 1000)
                    try:
                        await page.wait_for_load_state("networkidle", timeout=7000)
                    except PlaywrightError:
                        warnings.append("Some network requests were still active when the initial wait ended")
                    page_title = await page.title() or page_title
                    # Bounded scrolling triggers common lazy-loaded images and media without clicking controls.
                    for _ in range(6):
                        if time.monotonic() - started > CAPTURE_TIMEOUT_SECONDS - 5:
                            break
                        at_bottom = await page.evaluate("() => window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 2")
                        if at_bottom:
                            break
                        await page.evaluate("() => window.scrollBy(0, Math.max(500, window.innerHeight * 0.8))")
                        await page.wait_for_timeout(350)
                    await page.wait_for_timeout(500)
                    page_title = await page.title() or page_title
                finally:
                    if body_tasks:
                        try:
                            await asyncio.wait_for(asyncio.gather(*list(body_tasks), return_exceptions=True), timeout=8)
                        except asyncio.TimeoutError:
                            warnings.append("Some resource bodies did not finish before the capture deadline")
                    await context.close()
                    await browser.close()
        except HTTPException:
            raise
        except Exception as exc:
            if not assets_by_url:
                raise HTTPException(status_code=502, detail=f"Could not load the page: {type(exc).__name__}") from exc
            warnings.append(f"The browser ended early: {type(exc).__name__}")

    ordered_assets = sorted(assets_by_url.values(), key=lambda item: (item.category, item.file_name.lower(), item.url))
    if not ordered_assets:
        raise HTTPException(status_code=422, detail="The page loaded but no HTTP resources were captured")

    # Resolve filename collisions inside each ZIP folder while retaining original URLs in metadata.
    seen_names: set[str] = set()
    for asset in ordered_assets:
        folder = _folder_for(asset.category)
        base, ext = os.path.splitext(asset.file_name)
        candidate = asset.file_name
        suffix = 2
        while f"{folder}/{candidate}" in seen_names:
            candidate = f"{base}_{suffix}{ext}"
            suffix += 1
        asset.file_name = candidate
        seen_names.add(f"{folder}/{candidate}")

    session_id = secrets.token_urlsafe(18)
    session = CaptureSession(session_id, target_url, page_title[:200], time.time(), ordered_assets, warnings)
    _clean_old_sessions()
    SESSIONS[session_id] = session
    return {
        "captureId": session_id,
        "url": target_url,
        "title": session.title,
        "totalAssets": len(ordered_assets),
        "totalSizeBytes": sum(item.size_bytes for item in ordered_assets if item.content is not None),
        "assets": [item.public() for item in ordered_assets],
        "warnings": warnings,
        "expiresInSeconds": SESSION_TTL_SECONDS,
    }


def _folder_for(category: str) -> str:
    return {
        "IMAGE": "assets/images",
        "AUDIO": "assets/audio",
        "VIDEO": "assets/video",
        "FONT": "assets/fonts",
        "SCRIPT": "assets/scripts",
        "DATA": "assets/data",
    }.get(category, "assets/other")


@app.post("/api/exports")
async def export_assets(payload: ExportRequest):
    session = _session_or_404(payload.captureId)
    requested = set(payload.assetIds)
    selected = [asset for asset in session.assets if asset.id in requested and asset.content is not None]
    if not selected:
        raise HTTPException(status_code=422, detail="None of the selected resources has a downloadable response body")

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        manifest = []
        for asset in selected:
            archive.writestr(f"{_folder_for(asset.category)}/{asset.file_name}", asset.content or b"")
            manifest.append({
                "file": f"{_folder_for(asset.category)}/{asset.file_name}",
                "url": asset.url,
                "mimeType": asset.mime_type,
                "bytes": asset.size_bytes,
                "httpStatus": asset.status,
            })
        archive.writestr("capture-manifest.json", __import__("json").dumps({"sourceUrl": session.url, "title": session.title, "files": manifest, "warnings": session.warnings}, ensure_ascii=False, indent=2))

    filename = re.sub(r"[^A-Za-z0-9_-]", "_", session.title).strip("_")[:60] or "web-assets"
    headers = {"Content-Disposition": f'attachment; filename="WebAssets_{filename}.zip"'}
    return Response(buffer.getvalue(), media_type="application/zip", headers=headers)


@app.get("/api/captures/{capture_id}/assets/{asset_id}/text")
async def get_text_asset(capture_id: str, asset_id: str):
    session = _session_or_404(capture_id)
    asset = next((item for item in session.assets if item.id == asset_id), None)
    if asset is None:
        raise HTTPException(status_code=404, detail="Resource not found in this capture")
    if asset.content is None:
        raise HTTPException(status_code=422, detail="This resource has no captured response body")
    if asset.category not in {"SCRIPT", "DATA"} and not asset.mime_type.lower().startswith("text/"):
        raise HTTPException(status_code=415, detail="Only text resources can be previewed")
    if len(asset.content) > 2 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Text preview is limited to 2 MiB")
    return PlainTextResponse(asset.content.decode("utf-8", errors="replace"), media_type="text/plain; charset=utf-8")


@app.get("/api/captures/{capture_id}")
async def get_capture(capture_id: str) -> dict:
    session = _session_or_404(capture_id)
    return {
        "captureId": session.id,
        "url": session.url,
        "title": session.title,
        "totalAssets": len(session.assets),
        "totalSizeBytes": sum(item.size_bytes for item in session.assets if item.content is not None),
        "assets": [item.public() for item in session.assets],
        "warnings": session.warnings,
    }


@app.get("/", include_in_schema=False)
async def web_app():
    if not WEB_INDEX.exists():
        raise HTTPException(status_code=404, detail="Web interface is not installed")
    return FileResponse(WEB_INDEX, media_type="text/html")
