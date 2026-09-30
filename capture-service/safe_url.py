"""URL validation for the capture service. Network-level egress rules are still required in production."""
from __future__ import annotations

import ipaddress
import socket
import time
from urllib.parse import urlsplit

_DNS_CACHE: dict[str, tuple[float, tuple[str, ...]]] = {}
_DNS_TTL_SECONDS = 30


class UnsafeTarget(ValueError):
    pass


def _public_addresses(host: str) -> tuple[str, ...]:
    now = time.monotonic()
    cached = _DNS_CACHE.get(host)
    if cached and cached[0] > now:
        return cached[1]

    try:
        answers = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except (OSError, socket.gaierror) as exc:
        raise UnsafeTarget("The host could not be resolved") from exc

    addresses = tuple(sorted({answer[4][0].split("%", 1)[0] for answer in answers}))
    if not addresses:
        raise UnsafeTarget("The host did not resolve to an address")

    for address in addresses:
        try:
            parsed = ipaddress.ip_address(address)
        except ValueError as exc:
            raise UnsafeTarget("The host resolved to an invalid address") from exc
        if not parsed.is_global:
            raise UnsafeTarget("Private, local, and non-public network addresses are blocked")

    _DNS_CACHE[host] = (now + _DNS_TTL_SECONDS, addresses)
    return addresses


def validate_target_url(value: str) -> str:
    """Normalize and validate an HTTP(S) URL and require every resolved IP to be public."""
    raw = value.strip()
    if not raw:
        raise UnsafeTarget("Enter a URL")
    if "://" not in raw:
        raw = "https://" + raw

    try:
        parsed = urlsplit(raw)
        port = parsed.port
    except ValueError as exc:
        raise UnsafeTarget("The URL is invalid") from exc

    if parsed.scheme.lower() not in {"http", "https"}:
        raise UnsafeTarget("Only HTTP and HTTPS URLs are supported")
    if not parsed.hostname or parsed.username or parsed.password:
        raise UnsafeTarget("The URL must include a public hostname and cannot contain credentials")
    if port not in (None, 80, 443):
        raise UnsafeTarget("Custom ports are not supported")

    host = parsed.hostname.rstrip(".").lower()
    _public_addresses(host)

    netloc = host
    if ":" in host:  # IPv6 literal
        netloc = f"[{host}]"
    if port is not None:
        netloc = f"{netloc}:{port}"
    path = parsed.path or "/"
    return parsed._replace(scheme=parsed.scheme.lower(), netloc=netloc, path=path, fragment="").geturl()


def validate_request_url(value: str) -> None:
    """Apply the same public-network policy to every browser subrequest and redirect."""
    parsed = urlsplit(value)
    if parsed.scheme.lower() not in {"http", "https"}:
        raise UnsafeTarget("Only public HTTP(S) requests are allowed")
    if not parsed.hostname:
        raise UnsafeTarget("The request has no hostname")
    if parsed.port not in (None, 80, 443):
        raise UnsafeTarget("Custom ports are not supported")
    if parsed.username or parsed.password:
        raise UnsafeTarget("Requests containing credentials are blocked")
    _public_addresses(parsed.hostname.rstrip(".").lower())
