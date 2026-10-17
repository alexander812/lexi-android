#!/usr/bin/env python3
"""Create or update a GitHub release and upload the Android APK asset."""

from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com"
USER_AGENT = "lexi-android-release-publisher"


def ssl_context() -> ssl.SSLContext:
    try:
        import certifi

        return ssl.create_default_context(cafile=certifi.where())
    except ImportError:
        return ssl.create_default_context()


def die(message: str) -> None:
    print(message, file=sys.stderr)
    sys.exit(1)


def api_request(method: str, url: str, token: str, payload: dict | None = None):
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": USER_AGENT,
    }
    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(
            request, timeout=120, context=ssl_context()
        ) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        try:
            parsed = json.loads(body)
            message = parsed.get("message", body)
        except json.JSONDecodeError:
            message = body
        return error.code, {"message": message}


def file_digest(path: str) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as file:
        while chunk := file.read(1024 * 1024):
            digest.update(chunk)
    return f"sha256:{digest.hexdigest()}"


def upload_asset(upload_url: str, token: str, apk_path: str, asset_name: str) -> dict:
    base_url = upload_url.split("{", 1)[0]
    url = f"{base_url}?name={urllib.parse.quote(asset_name)}"
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        die(f"Некорректный upload_url: {upload_url}")

    size = os.path.getsize(apk_path)
    connection = http.client.HTTPSConnection(
        parsed.hostname, timeout=600, context=ssl_context()
    )
    path = parsed.path
    if parsed.query:
        path += f"?{parsed.query}"

    connection.putrequest("POST", path)
    connection.putheader("Authorization", f"Bearer {token}")
    connection.putheader("Accept", "application/vnd.github+json")
    connection.putheader("X-GitHub-Api-Version", "2022-11-28")
    connection.putheader("User-Agent", USER_AGENT)
    connection.putheader("Content-Type", "application/vnd.android.package-archive")
    connection.putheader("Content-Length", str(size))
    connection.endheaders()

    with open(apk_path, "rb") as apk:
        while chunk := apk.read(1024 * 1024):
            connection.send(chunk)

    response = connection.getresponse()
    body = response.read().decode("utf-8", errors="replace")
    connection.close()

    if response.status not in (200, 201):
        die(f"GitHub не принял APK: HTTP {response.status}\n{body}")

    try:
        return json.loads(body)
    except json.JSONDecodeError:
        die(f"GitHub вернул неожиданный ответ:\n{body}")
        return {}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True, help="owner/repo")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--asset-name", default="lexi-android.apk")
    parser.add_argument("--body", default="")
    parser.add_argument("--target-commitish", default="")
    args = parser.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        die("Не задан GITHUB_TOKEN.")

    repo_url = f"{API}/repos/{args.repo}"
    status, repo = api_request("GET", repo_url, token)
    if status != 200:
        die(f"Не удалось получить репозиторий {args.repo}: HTTP {status}: {repo.get('message', repo)}")
    if repo.get("private"):
        print(
            "Внимание: репозиторий приватный, ссылка на релиз не будет работать без авторизации.",
            file=sys.stderr,
        )

    tag_path = urllib.parse.quote(args.tag, safe="")
    status, release = api_request(
        "GET", f"{repo_url}/releases/tags/{tag_path}", token
    )

    if status == 404:
        payload = {
            "tag_name": args.tag,
            "name": args.name,
            "body": args.body,
            "draft": False,
            "prerelease": False,
            "make_latest": "true",
        }
        if args.target_commitish:
            payload["target_commitish"] = args.target_commitish
        status, release = api_request(
            "POST", f"{repo_url}/releases", token, payload
        )
        if status not in (200, 201):
            die(f"Не удалось создать релиз {args.tag}: HTTP {status}: {release.get('message', release)}")
        print(f"Создан релиз: {release['html_url']}")
    elif status == 200:
        status, release = api_request(
            "PATCH",
            release["url"],
            token,
            {
                "name": args.name,
                "body": args.body,
                "draft": False,
                "prerelease": False,
                "make_latest": "true",
            },
        )
        if status != 200:
            die(f"Не удалось обновить релиз {args.tag}: HTTP {status}: {release.get('message', release)}")
        print(f"Обновлён релиз: {release['html_url']}")
    else:
        die(f"Не удалось найти релиз {args.tag}: HTTP {status}: {release.get('message', release)}")

    local_digest = file_digest(args.apk)
    matching_assets = [
        asset
        for asset in release.get("assets", [])
        if asset.get("name") == args.asset_name
    ]

    kept_asset = None
    for asset in matching_assets:
        if (
            kept_asset is None
            and asset.get("state") == "uploaded"
            and asset.get("digest") == local_digest
        ):
            kept_asset = asset
            continue
        status, result = api_request("DELETE", asset["url"], token)
        if status not in (204, 200):
            die(
                f"Не удалось удалить старый {args.asset_name}: "
                f"HTTP {status}: {result.get('message', result)}"
            )
        print(f"Удалён старый asset: {args.asset_name}")

    if kept_asset is not None:
        print("APK уже загружен и совпадает по SHA-256 — upload пропущен.")
        print(f"Размер: {kept_asset.get('size')} байт")
        print(
            "Постоянная ссылка: "
            f"https://github.com/{args.repo}/releases/latest/download/{args.asset_name}"
        )
        return

    asset = upload_asset(release["upload_url"], token, args.apk, args.asset_name)
    latest_url = (
        f"https://github.com/{args.repo}/releases/latest/download/{args.asset_name}"
    )
    print(f"Загружен asset: {asset.get('browser_download_url')}")
    print(f"Размер: {asset.get('size')} байт")
    print(f"Постоянная ссылка: {latest_url}")


if __name__ == "__main__":
    main()
