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
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8787

# 经典 playurl JSON（toVideoInfo 消费的形状：DASH + video/audio 轨）
# base_url 指向本服务的 media 路径；占位 mp4 可后续替换为真实可播样本
CANNED_PLAYURL = {
    "code": 0,
    "quality": 80,
    "format": "dash",
    "type": "DASH",
    "timelength": 30000000,
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
    """把 base_url 里的 0.0.0.0 替换成请求方实际访问到的主机地址。"""
    body = json.loads(json.dumps(CANNED_PLAYURL))
    for track in body["dash"]["video"] + body["dash"]["audio"]:
        track["base_url"] = track["base_url"].replace("0.0.0.0", host)
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
            # 占位媒体：有真实样本就回文件，否则 404（链路验证不依赖它）
            try:
                with open(f"media/{parsed.path.rsplit('/', 1)[-1]}", "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "video/mp4")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
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
    HTTPServer(("0.0.0.0", PORT), RoamerHandler).serve_forever()
