# Copyright (C) 2026 Pilipala
# SPDX-License-Identifier: GPL-3.0-only
"""Build a verified mirror wallpaper overlay ZIP (wallpapers/catalog.json + full + thumbs)."""
import argparse
import datetime
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MIRROR_BASE = 'https://marcotv.wlj9919.top/wallpapers/'
MAX_WALLPAPER_BYTES = 50 * 1024 * 1024
MAX_THUMB_BYTES = 2 * 1024 * 1024

KNOWN_PRESETS = {
    '01-巴哈马浅滩': {
        'id': '01-bahamas-shoals',
        'title': '巴哈马浅滩',
        'subtitle': '巴哈马埃克苏马群岛 · Sentinel-2A 真彩色卫星观测',
        'credit': 'Contains modified Copernicus Sentinel data 2024',
        'license': 'Copernicus Sentinel Data Legal Notice',
    },
    '02-靛夜流光': {
        'id': '02-indigo-ribbon',
        'title': '靛夜流光',
        'subtitle': '原创三维缎面曲面渲染 · 钴蓝与珊瑚反光',
        'credit': 'Pilipala · Marco TV 原创渲染',
        'license': 'GPL-3.0-only',
    },
    '03-富士暮光': {
        'id': '03-fuji-dusk',
        'title': '富士暮光',
        'subtitle': '富士山日落湖景 · 横幅精选裁切调色',
        'credit': 'Romain Guy · Mount Fuji at sunset, March 2025',
        'license': 'CC0 1.0',
    },
    '04-星云之崖': {
        'id': '04-cosmic-cliffs',
        'title': '星云之崖',
        'subtitle': '韦布望远镜船底座星云 NIRCam 观测（裁切压暗）',
        'credit': 'NASA, ESA, CSA, and STScI (weic2205a)',
        'license': 'CC BY 4.0',
    },
    '05-潮汐余晖': {
        'id': '05-tidal-afterglow',
        'title': '潮汐余晖',
        'subtitle': '原创日出前海面波纹与薄云反射渲染',
        'credit': 'Pilipala · Marco TV 原创渲染',
        'license': 'GPL-3.0-only',
    },
}


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_slug(stem: str, index: int) -> str:
    ascii_only = re.sub(r'[^a-z0-9_-]+', '-', stem.lower()).strip('-_')
    if ascii_only and re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,47}', ascii_only):
        return ascii_only
    return f'wallpaper-{index:02d}'


def encode_jpeg(im: Image.Image, target_w: int, target_h: int, quality: int) -> tuple[bytes, int, int]:
    rgb = im.convert('RGB')
    w, h = rgb.size
    if target_w > 0 and (w > target_w or h > target_h):
        scale = min(target_w / w, target_h / h)
        nw = max(1, round(w * scale))
        nh = max(1, round(h * scale))
        rgb = rgb.resize((nw, nh), Image.Resampling.LANCZOS)
        w, h = nw, nh
    buf = io.BytesIO()
    rgb.save(buf, format='JPEG', quality=quality, optimize=True, progressive=True)
    return buf.getvalue(), w, h


def build_bundle(source_dir: Path, output_zip: Path, max_width: int, quality: int,
                 base_url: str, meta_path: Path | None, keep_original: bool) -> dict:
    if not source_dir.is_dir():
        raise FileNotFoundError(f'Source folder not found: {source_dir}')
    if not base_url.endswith('/'):
        base_url += '/'
    overrides = {}
    if meta_path and meta_path.is_file():
        overrides = json.loads(meta_path.read_text(encoding='utf-8'))

    images = sorted(
        p for p in source_dir.iterdir()
        if p.is_file() and p.suffix.lower() in {'.jpg', '.jpeg', '.png', '.webp'}
    )
    if not images:
        raise ValueError(f'No images found in {source_dir}')

    max_height = round(max_width * 9 / 16) if max_width > 0 else 0
    entries: dict[str, bytes] = {}
    items = []
    seen_ids = set()

    Image.MAX_IMAGE_PIXELS = 200_000_000

    for idx, img_path in enumerate(images, start=1):
        stem = img_path.stem
        meta = dict(KNOWN_PRESETS.get(stem, {}))
        meta.update(overrides.get(stem, {}))
        item_id = meta.get('id') or safe_slug(stem, idx)
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,47}', item_id) or item_id in seen_ids:
            raise ValueError(f'Invalid or duplicate wallpaper id: {item_id}')
        seen_ids.add(item_id)

        title = (meta.get('title') or re.sub(r'^\d+[-_\s]*', '', stem) or item_id)[:40]
        subtitle = meta.get('subtitle', '')[:160]
        credit = meta.get('credit', '')[:160]
        license_name = meta.get('license', '')[:120]

        with Image.open(img_path) as im:
            if keep_original and img_path.suffix.lower() in {'.jpg', '.jpeg'}:
                full_bytes = img_path.read_bytes()
                fw, fh = im.size
            else:
                full_bytes, fw, fh = encode_jpeg(im, max_width, max_height, quality)
            thumb_bytes, tw, th = encode_jpeg(im, 480, 270, 84)

        if len(full_bytes) > MAX_WALLPAPER_BYTES:
            raise ValueError(f'{img_path.name} exceeds 50 MB ({len(full_bytes)} bytes)')
        if len(thumb_bytes) > MAX_THUMB_BYTES:
            raise ValueError(f'{img_path.name} thumbnail exceeds 2 MB ({len(thumb_bytes)} bytes)')

        full_rel = f'full/{item_id}.jpg'
        thumb_rel = f'thumbs/{item_id}.jpg'
        entries['wallpapers/' + full_rel] = full_bytes
        entries['wallpapers/' + thumb_rel] = thumb_bytes

        items.append({
            'id': item_id,
            'title': title,
            'subtitle': subtitle,
            'credit': credit,
            'license': license_name,
            'width': fw,
            'height': fh,
            'size': len(full_bytes),
            'sha256': sha256_hex(full_bytes),
            'url': base_url + full_rel,
            'thumbUrl': base_url + thumb_rel,
            'thumbSize': len(thumb_bytes),
            'thumbSha256': sha256_hex(thumb_bytes),
        })

    catalog = {
        'schema': 1,
        'updated': datetime.date.today().isoformat(),
        'items': items,
    }
    entries['wallpapers/catalog.json'] = (
        json.dumps(catalog, ensure_ascii=False, indent=2) + '\n'
    ).encode('utf-8')

    attribution_src = source_dir.parent / '图像来源与署名.md'
    if attribution_src.is_file():
        entries['wallpapers/ATTRIBUTION.md'] = attribution_src.read_bytes()

    deploy_note = (
        '# Marco TV 镜像站在线壁纸包部署说明\n\n'
        '作者：Pilipala\n\n'
        '将压缩包内的 `wallpapers/` 目录合并到镜像站根目录（`https://marcotv.wlj9919.top/`），保留原有 `updates/` 与安装包文件：\n\n'
        '```text\n'
        '网站根目录/\n'
        '  wallpapers/\n'
        '    catalog.json\n'
        '    ATTRIBUTION.md\n'
        '    full/*.jpg\n'
        '    thumbs/*.jpg\n'
        '```\n\n'
        '发布顺序：**先上传 `wallpapers/full/*` 与 `wallpapers/thumbs/*`，确认可访问后，再上传/更新 `wallpapers/catalog.json`。**\n'
        '电视端会在「设置 → 通用与桌面 → 桌面壁纸 → 在线精选壁纸」（以及 Magic Desktop 壁纸页）自动拉取 `catalog.json`，经 SHA-256 校验后缓存到本机并支持断网使用。\n'
    )
    entries['WALLPAPERS-DEPLOY.md'] = deploy_note.encode('utf-8')

    output_zip.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_zip, 'w', zipfile.ZIP_DEFLATED) as zf:
        for name, data in entries.items():
            zf.writestr(name, data)
    with zipfile.ZipFile(output_zip) as zf:
        if zf.testzip():
            raise ValueError('ZIP CRC verification failed')

    total_full = sum(it['size'] for it in items)
    total_thumb = sum(it['thumbSize'] for it in items)
    print(
        f'Created {output_zip.name}: {len(items)} wallpapers '
        f'(full {total_full / 1024 / 1024:.2f} MB, thumbs {total_thumb / 1024:.1f} KB, '
        f'zip {output_zip.stat().st_size / 1024 / 1024:.2f} MB); CRC verified.'
    )
    return catalog


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Folder containing source wallpaper images')
    parser.add_argument('--output', type=Path, default=ROOT / 'dist/MarcoTV-wallpapers-mirror-overlay.zip')
    parser.add_argument('--max-width', type=int, default=3840, help='Max output width (default 3840 for 4K; 1920 for 1080p)')
    parser.add_argument('--quality', type=int, default=90, help='JPEG quality for full wallpapers (default 90)')
    parser.add_argument('--keep-original', action='store_true', help='Keep original JPEG bytes without resizing')
    parser.add_argument('--base-url', default=MIRROR_BASE)
    parser.add_argument('--meta', type=Path, default=None, help='Optional JSON file mapping stem -> metadata')
    args = parser.parse_args()
    build_bundle(args.source, args.output, args.max_width, args.quality, args.base_url, args.meta, args.keep_original)


if __name__ == '__main__':
    main()
