import asyncio
import io
import json
import unittest
import zipfile

from fastapi import HTTPException

from app import CaptureSession, CapturedAsset, ExportRequest, SESSIONS, capture_page, export_assets, CaptureRequest


class CaptureApiTests(unittest.TestCase):
    def tearDown(self):
        SESSIONS.clear()

    def test_blocks_loopback_before_browser_launch(self):
        with self.assertRaises(HTTPException) as caught:
            asyncio.run(capture_page(CaptureRequest(url="http://127.0.0.1/")))
        self.assertEqual(caught.exception.status_code, 400)

    def test_zip_contains_original_binary_bytes_and_manifest(self):
        asset = CapturedAsset(
            id="asset-1",
            url="https://example.test/assets/icon.png",
            file_name="icon.png",
            category="IMAGE",
            mime_type="image/png",
            size_bytes=8,
            status=200,
            content=b"\x89PNG\r\n\x1a\n",
        )
        SESSIONS["capture-1"] = CaptureSession(
            id="capture-1",
            url="https://example.test/",
            title="Fixture",
            created_at=9999999999,
            assets=[asset],
            warnings=[],
        )
        response = asyncio.run(export_assets(ExportRequest(captureId="capture-1", assetIds=["asset-1"])))
        self.assertEqual(response.media_type, "application/zip")
        with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
            self.assertEqual(archive.read("assets/images/icon.png"), b"\x89PNG\r\n\x1a\n")
            manifest = json.loads(archive.read("capture-manifest.json"))
        self.assertEqual(manifest["files"][0]["bytes"], 8)
        self.assertEqual(manifest["sourceUrl"], "https://example.test/")


if __name__ == "__main__":
    unittest.main()
