import unittest
from unittest.mock import patch

from safe_url import UnsafeTarget, validate_target_url


class SafeUrlTests(unittest.TestCase):
    @patch("safe_url.socket.getaddrinfo", return_value=[(None, None, None, None, ("93.184.216.34", 0))])
    def test_normalizes_public_host(self, _resolver):
        self.assertEqual(validate_target_url("example.test/path?q=1#fragment"), "https://example.test/path?q=1")

    @patch("safe_url.socket.getaddrinfo", return_value=[(None, None, None, None, ("10.0.0.8", 0))])
    def test_rejects_private_address(self, _resolver):
        with self.assertRaises(UnsafeTarget):
            validate_target_url("http://internal.test/")

    def test_rejects_non_http_scheme(self):
        with self.assertRaises(UnsafeTarget):
            validate_target_url("file:///etc/passwd")

    def test_rejects_credentials(self):
        with self.assertRaises(UnsafeTarget):
            validate_target_url("https://user:pass@example.test/")

    def test_rejects_custom_port(self):
        with self.assertRaises(UnsafeTarget):
            validate_target_url("https://example.test:8443/")


if __name__ == "__main__":
    unittest.main()
