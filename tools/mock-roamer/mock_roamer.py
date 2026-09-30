#!/usr/bin/env python3
"""本地 mock 漫游服务器（解锁 U3/U4 开发用，见 docs/UNLOCK_PLAN.md）。

用途：在 PC 上模拟漫游服务器的协议端，让 U4 的真机闭环**不接触任何真实账号凭据**：
  1. 模块配置本机地址为服务器（如 http://192.168.x.x:8787）；
  2. 受限请求到达本服务 → 返回 canned 的经典 playurl JSON（code=0）；
  3. U4 的响应重构把 JSON 转成 VodInfo，播放器起播并向本服务的 media 路径拉流。

用法：
  python mock_roamer.py [端口]            # 默认 8787
  # media 目录下放 sample.mp4 可让播放器真正拉到流（可选）

协议端行为（与真实漫游服务器一致的形状）：
  GET /pgc/player/api/playurl?...        → 200 canned playurl JSON
  GET /intl/gateway/v2/ogv/playurl?...   → 200 canned playurl JSON（th 区）
  其他路径                               → 404 {"code":-404}
"""
import json
import os
import re
import sys

MEDIA_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "media")

# U4.5 诊断：若存在 real_urls.json（{"video": "...", "audio": "..."}），canned 响应的
# base_url 用真实 B 站 CDN 地址——解耦「媒体格式」与「重构正确性」两个变量。
REAL_URLS_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "real_urls.json")
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8787

# 经典 playurl JSON（toVideoInfo 消费的形状：DASH + video/audio 轨）
# base_url 指向本服务的 media 路径；占位 mp4 可后续替换为真实可播样本
CANNED_PLAYURL = {
    "code": 0,
    "quality": 80,
    "format": "dash",
    "type": "DASH",
    "timelength": 30000,
    "video_codecid": 12,
    "accept_quality": [80],
    "support_formats": [
        {"quality": 80, "new_description": "高清 1080P", "display_desc": "1080P", "superscript": "", "codecs": None},
    ],
    "dash": {
        "video": [
            {
                "id": 80,
                "base_url": f"http://0.0.0.0:{PORT}/media/sample.mp4",
                "backup_url": [],
                "bandwidth": 2000000,
                "codecid": 12,
                "md5": "",
                "size": 0,
            },
        ],
        "audio": [
            {
                "id": 30280,
                "base_url": f"http://0.0.0.0:{PORT}/media/sample.m4a",
                "backup_url": [],
                "bandwidth": 320000,
                "codecid": 0,
                "md5": "",
                "size": 0,
            },
        ],
        "dolby": {"audio": [], "type": 0},
        "flac": {"audio": None, "display": 0},
    },
}


def base_url_for(host: str) -> dict:
    """U4.5：有 real_urls.json 用真实 CDN 地址；否则 0.0.0.0 换成本机地址。"""
    body = json.loads(json.dumps(CANNED_PLAYURL))
    real = {}
    if os.path.isfile(REAL_URLS_PATH):
        with open(REAL_URLS_PATH, encoding="utf-8") as f:
            real = json.load(f)
    for track in body["dash"]["video"]:
        track["base_url"] = real.get("video", track["base_url"].replace("0.0.0.0", host))
    for track in body["dash"]["audio"]:
        track["base_url"] = real.get("audio", track["base_url"].replace("0.0.0.0", host))
    return body


class RoamerHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        host = self.headers.get("Host", f"127.0.0.1:{PORT}").split(":")[0]
        print(f"[mock] {self.command} {self.path} (Host={host})", flush=True)

        if parsed.path in (
            "/pgc/player/api/playurl",
            "/intl/gateway/v2/ogv/playurl",
        ):
            body = json.dumps(base_url_for(host)).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if parsed.path.startswith("/media/"):
            # 占位媒体：有真实样本就回文件（支持 Range 206——DASH 播放器按分段拉取）
            try:
                with open(os.path.join(MEDIA_DIR, parsed.path.rsplit('/', 1)[-1]), "rb") as f:
                    body = f.read()
                rng = self.headers.get("Range", "")
                m = re.match(r"bytes=(\d+)-(\d*)", rng)
                ctype = "video/mp4" if parsed.path.endswith(".mp4") else "audio/mp4"
                if m:
                    start = int(m.group(1))
                    end = int(m.group(2)) if m.group(2) else len(body) - 1
                    chunk = body[start:end + 1]
                    self.send_response(206)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Content-Range", f"bytes {start}-{end}/{len(body)}")
                    self.send_header("Content-Length", str(len(chunk)))
                    self.end_headers()
                    self.wfile.write(chunk)
                    print(f"[mock] 206 {parsed.path} {start}-{end}/{len(body)}", flush=True)
                    return
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                print(f"[mock] 200 {parsed.path} {len(body)}B", flush=True)
                return
            except FileNotFoundError:
                pass

        body = b'{"code":-404,"message":"mock: not found"}'
        self.send_response(404)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):  # 关掉默认的双份日志
        pass


if __name__ == "__main__":
    print(f"[mock] roamer listening on 0.0.0.0:{PORT} "
          f"(playurl: /pgc/player/api/playurl, media: /media/*)", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), RoamerHandler).serve_forever()
