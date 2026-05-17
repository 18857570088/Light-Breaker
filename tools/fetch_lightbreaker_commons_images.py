#!/usr/bin/env python3
import argparse
import csv
import hashlib
import html
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path


BASE_API = "https://commons.wikimedia.org/w/api.php"
USER_AGENT = "LightBreakerImageSeeder/1.0 (https://github.com/18857570088/Light-Breaker)"
THUMB_WIDTH = "1400"

CATEGORIES = [
    {
        "id": "nature",
        "nameZh": "自然风光",
        "nameEn": "Nature Landscapes",
        "description": "极光、瀑布、海浪、雪山、森林、湖泊等舒缓自然景观。",
        "queries": [
            "aurora borealis landscape filetype:bitmap",
            "waterfall landscape long exposure filetype:bitmap",
            "ocean waves seascape filetype:bitmap",
            "snow mountain landscape filetype:bitmap",
            "glacier lake landscape filetype:bitmap",
            "forest river landscape filetype:bitmap",
            "sunrise lake landscape filetype:bitmap",
            "tropical beach sunset filetype:bitmap",
            "misty mountains landscape filetype:bitmap",
            "autumn forest landscape filetype:bitmap",
        ],
    },
    {
        "id": "masterworks",
        "nameZh": "名画再现",
        "nameEn": "Classic Masterworks",
        "description": "星空、睡莲、呐喊等公共领域经典绘画及馆藏高清复刻。",
        "queries": [
            "The Starry Night Van Gogh painting filetype:bitmap",
            "Claude Monet Water Lilies painting filetype:bitmap",
            "Edvard Munch The Scream painting filetype:bitmap",
            "Vincent van Gogh Sunflowers painting filetype:bitmap",
            "Katsushika Hokusai Great Wave painting filetype:bitmap",
            "Johannes Vermeer painting filetype:bitmap",
            "Rembrandt painting filetype:bitmap",
            "Sandro Botticelli painting filetype:bitmap",
            "J. M. W. Turner painting filetype:bitmap",
            "Claude Monet Impression Sunrise painting filetype:bitmap",
            "Renoir painting filetype:bitmap",
            "Cezanne painting filetype:bitmap",
            "Raphael painting filetype:bitmap",
            "Leonardo da Vinci painting filetype:bitmap",
        ],
    },
    {
        "id": "city",
        "nameZh": "城市建筑",
        "nameEn": "City Architecture",
        "description": "夜景、街道、地标、市集、城市天际线与建筑空间。",
        "queries": [
            "city skyline night filetype:bitmap",
            "city street night lights filetype:bitmap",
            "urban architecture landmark filetype:bitmap",
            "old town street architecture filetype:bitmap",
            "street market city filetype:bitmap",
            "Tokyo night city filetype:bitmap",
            "Hong Kong skyline night filetype:bitmap",
            "New York skyline night filetype:bitmap",
            "Paris street architecture filetype:bitmap",
            "Venice canal cityscape filetype:bitmap",
            "Shanghai skyline night filetype:bitmap",
            "city square market lights filetype:bitmap",
        ],
    },
    {
        "id": "abstract",
        "nameZh": "抽象艺术",
        "nameEn": "Abstract Art",
        "description": "几何、水墨、波普、表现主义、色块与开放授权抽象图形。",
        "queries": [
            "abstract geometric art filetype:bitmap",
            "geometric pattern art filetype:bitmap",
            "abstract watercolor art filetype:bitmap",
            "ink wash abstract art filetype:bitmap",
            "abstract expressionist painting filetype:bitmap",
            "color field abstract painting filetype:bitmap",
            "pop art pattern filetype:bitmap",
            "kaleidoscope abstract art filetype:bitmap",
            "generative art abstract filetype:bitmap",
            "modern abstract painting public domain filetype:bitmap",
            "abstract shapes colorful filetype:bitmap",
        ],
    },
]

BAD_TITLE_WORDS = {
    "logo",
    "icon",
    "map",
    "diagram",
    "chart",
    "flag",
    "coat of arms",
    "locator",
    "symbol",
    "signature",
    "seal",
    "blank",
    "qr",
    "poster",
}


def clean_text(value):
    if not value:
        return ""
    value = html.unescape(str(value))
    value = re.sub(r"<[^>]+>", "", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip()


def strip_file_title(title):
    title = title.removeprefix("File:")
    title = re.sub(r"\.(jpg|jpeg|png)$", "", title, flags=re.IGNORECASE)
    title = title.replace("_", " ")
    return clean_text(title)


def request_json(params):
    query = urllib.parse.urlencode(params)
    req = urllib.request.Request(
        BASE_API + "?" + query,
        headers={"User-Agent": USER_AGENT},
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                return json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code != 429 or attempt == 3:
                raise
            retry_after = int(exc.headers.get("Retry-After") or 12)
            time.sleep(retry_after + attempt * 5)
        except TimeoutError:
            if attempt == 3:
                raise
            time.sleep(5 + attempt * 5)


def commons_search(search_query, limit):
    found = []
    offset = 0
    while len(found) < limit:
        params = {
            "action": "query",
            "format": "json",
            "generator": "search",
            "gsrnamespace": "6",
            "gsrlimit": "50",
            "gsroffset": str(offset),
            "gsrsearch": search_query,
            "prop": "imageinfo",
            "iiprop": "url|mime|size|extmetadata",
            "iiurlwidth": THUMB_WIDTH,
        }
        data = request_json(params)
        pages = data.get("query", {}).get("pages", {})
        batch = sorted(pages.values(), key=lambda item: item.get("index", 0))
        if not batch:
            break
        for page in batch:
            info_list = page.get("imageinfo") or []
            if not info_list:
                continue
            info = info_list[0]
            if is_candidate(page, info):
                found.append({"page": page, "info": info, "query": search_query})
            if len(found) >= limit:
                break
        cont = data.get("continue", {})
        if "gsroffset" not in cont:
            break
        offset = int(cont["gsroffset"])
        time.sleep(0.15)
    return found


def is_candidate(page, info):
    mime = info.get("mime", "")
    if mime not in {"image/jpeg", "image/png"}:
        return False
    width = int(info.get("thumbwidth") or info.get("width") or 0)
    height = int(info.get("thumbheight") or info.get("height") or 0)
    if width < 900 or height < 600:
        return False
    ratio = width / max(height, 1)
    if ratio < 0.45 or ratio > 2.8:
        return False
    title = page.get("title", "").lower()
    if any(word in title for word in BAD_TITLE_WORDS):
        return False
    return True


def candidate_score(candidate):
    info = candidate["info"]
    meta = info.get("extmetadata") or {}
    categories = clean_text((meta.get("Categories") or {}).get("value", "")).lower()
    width = int(info.get("width") or 0)
    height = int(info.get("height") or 0)
    score = 0
    if "featured pictures" in categories or "featured picture" in categories:
        score += 80
    if "quality images" in categories or "quality image" in categories:
        score += 45
    if "valued images" in categories or "valued image" in categories:
        score += 30
    score += min(width * height // 1_000_000, 20)
    return score


def gather_candidates(category, target):
    seen_titles = set()
    candidates = []
    per_query = max(25, target // max(len(category["queries"]), 1) + 15)
    with ThreadPoolExecutor(max_workers=min(2, len(category["queries"]))) as executor:
        futures = {}
        for query in category["queries"]:
            print(f"search {category['id']}: {query}", flush=True)
            futures[executor.submit(commons_search, query, per_query)] = query
        for future in as_completed(futures):
            query = futures[future]
            try:
                for candidate in future.result():
                    title = candidate["page"].get("title")
                    if title in seen_titles:
                        continue
                    seen_titles.add(title)
                    candidates.append(candidate)
            except Exception as exc:
                print(f"warn search failed: {query}: {exc}", flush=True)
    candidates.sort(key=candidate_score, reverse=True)
    return candidates


def extension_for(info):
    return ".png" if info.get("mime") == "image/png" else ".jpg"


def download_one(args):
    category, index, candidate, root, base_url = args
    info = candidate["info"]
    page = candidate["page"]
    ext = extension_for(info)
    image_id = f"{category['id']}_{index:03d}"
    rel_path = f"{category['id']}/{image_id}{ext}"
    file_path = root / rel_path
    file_path.parent.mkdir(parents=True, exist_ok=True)
    download_url = info.get("thumburl") or info.get("url")
    req = urllib.request.Request(download_url, headers={"User-Agent": USER_AGENT})
    content = None
    for attempt in range(5):
        try:
            time.sleep(0.45 + (index % 3) * 0.15)
            with urllib.request.urlopen(req, timeout=75) as response:
                content = response.read()
            break
        except urllib.error.HTTPError as exc:
            if exc.code != 429 or attempt == 4:
                raise
            retry_after = int(exc.headers.get("Retry-After") or 20)
            time.sleep(retry_after + attempt * 10)
        except TimeoutError:
            if attempt == 4:
                raise
            time.sleep(8 + attempt * 8)
    if content is None:
        raise ValueError("empty download")
    if ext == ".jpg" and not content.startswith(b"\xff\xd8"):
        raise ValueError("download is not a JPEG")
    if ext == ".png" and not content.startswith(b"\x89PNG"):
        raise ValueError("download is not a PNG")
    file_path.write_bytes(content)
    sha256 = hashlib.sha256(content).hexdigest()
    meta = info.get("extmetadata") or {}
    return {
        "id": image_id,
        "category": category["id"],
        "categoryNameZh": category["nameZh"],
        "categoryNameEn": category["nameEn"],
        "title": strip_file_title(page.get("title", image_id)),
        "fileName": rel_path.replace(os.sep, "/"),
        "url": urllib.parse.urljoin(base_url.rstrip("/") + "/", rel_path.replace(os.sep, "/")),
        "sourceUrl": info.get("descriptionurl", ""),
        "originalUrl": info.get("url", ""),
        "author": clean_text((meta.get("Artist") or meta.get("Credit") or {}).get("value", "")),
        "license": clean_text((meta.get("LicenseShortName") or meta.get("License") or {}).get("value", "")),
        "licenseUrl": clean_text((meta.get("LicenseUrl") or {}).get("value", "")),
        "width": int(info.get("thumbwidth") or info.get("width") or 0),
        "height": int(info.get("thumbheight") or info.get("height") or 0),
        "bytes": len(content),
        "sha256": sha256,
        "searchQuery": candidate["query"],
    }


def load_manifest(root):
    path = root / "manifest.json"
    if not path.exists():
        return {"version": 1, "categories": [], "images": []}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {"version": 1, "categories": [], "images": []}


def write_manifest(root, base_url, image_items):
    categories = [
        {
            "id": cat["id"],
            "nameZh": cat["nameZh"],
            "nameEn": cat["nameEn"],
            "description": cat["description"],
            "count": sum(1 for image in image_items if image.get("category") == cat["id"]),
            "path": urllib.parse.urljoin(base_url.rstrip("/") + "/", cat["id"] + "/"),
        }
        for cat in CATEGORIES
    ]
    data = {
        "version": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": "Wikimedia Commons API",
        "baseUrl": base_url.rstrip("/") + "/",
        "categories": categories,
        "images": sorted(image_items, key=lambda item: (item.get("category", ""), item.get("id", ""))),
    }
    (root / "manifest.json").write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    write_sources_csv(root, data["images"])
    write_review_html(root, data)


def write_sources_csv(root, images):
    fields = ["id", "category", "title", "url", "sourceUrl", "author", "license", "licenseUrl", "searchQuery"]
    with (root / "sources.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        for image in images:
            writer.writerow({field: image.get(field, "") for field in fields})


def write_review_html(root, data):
    cards = []
    for image in data["images"]:
        cards.append(
            f"""<article class="card" data-category="{html.escape(image['category'])}">
  <img src="{html.escape(image['url'])}" loading="lazy" alt="{html.escape(image['title'])}">
  <div class="meta">
    <strong>{html.escape(image['id'])}</strong>
    <span>{html.escape(image['categoryNameZh'])}</span>
    <p>{html.escape(image['title'])}</p>
    <small>{html.escape(image.get('license') or 'Unknown license')}</small>
    <a href="{html.escape(image.get('sourceUrl') or '#')}" target="_blank" rel="noreferrer">source</a>
  </div>
</article>"""
        )
    filters = " ".join(
        f"""<button onclick="filterCards('{cat['id']}')">{html.escape(cat['nameZh'])} ({cat['count']})</button>"""
        for cat in data["categories"]
    )
    html_text = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>LightBreaker 图片审核</title>
<style>
body{{margin:0;background:#0a0f1f;color:#e6edf7;font-family:Arial,'Microsoft YaHei',sans-serif}}
header{{position:sticky;top:0;background:#0f172a;padding:16px;z-index:5;border-bottom:1px solid #24324c}}
h1{{font-size:22px;margin:0 0 10px}}
button{{margin:4px 6px 4px 0;padding:8px 12px;border:1px solid #395075;background:#17233a;color:#fff;border-radius:6px;cursor:pointer}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px;padding:16px}}
.card{{background:#111b2f;border:1px solid #263852;border-radius:8px;overflow:hidden}}
img{{width:100%;aspect-ratio:16/10;object-fit:cover;display:block;background:#060914}}
.meta{{padding:10px;font-size:13px;line-height:1.4}}
.meta strong,.meta span,.meta small,.meta a{{display:block;margin-bottom:4px}}
.meta p{{margin:6px 0;color:#cbd5e1}}
.meta a{{color:#93c5fd}}
</style>
<script>
function filterCards(category){{
  document.querySelectorAll('.card').forEach(card=>{{
    card.style.display = category === 'all' || card.dataset.category === category ? '' : 'none';
  }});
}}
</script>
</head>
<body>
<header>
<h1>LightBreaker 图片审核</h1>
<button onclick="filterCards('all')">全部 ({len(data['images'])})</button>
{filters}
</header>
<main class="grid">
{''.join(cards)}
</main>
</body>
</html>"""
    (root / "review.html").write_text(html_text, encoding="utf-8")


def seed_category(category_id, root, base_url, target, workers):
    category = next(cat for cat in CATEGORIES if cat["id"] == category_id)
    manifest = load_manifest(root)
    images = [item for item in manifest.get("images", []) if item.get("category") != category_id]
    existing_urls = {item.get("sourceUrl") for item in manifest.get("images", []) if item.get("category") == category_id}
    candidates = [item for item in gather_candidates(category, target + 80) if item["info"].get("descriptionurl") not in existing_urls]
    selected = candidates[: target + 25]
    if len(selected) < target:
        print(f"warn only {len(selected)} candidates selected for {category_id}", flush=True)
    downloaded = []
    jobs = [(category, index + 1, candidate, root, base_url) for index, candidate in enumerate(selected)]
    with ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(download_one, job) for job in jobs]
        for future in as_completed(futures):
            try:
                item = future.result()
                downloaded.append(item)
                if len(downloaded) % 10 == 0 or len(downloaded) == len(jobs):
                    print(f"downloaded {category_id}: {len(downloaded)}/{len(jobs)}", flush=True)
            except Exception as exc:
                print(f"warn download failed: {exc}", flush=True)
    downloaded.sort(key=lambda item: item["id"])
    if len(downloaded) > target:
        for item in downloaded[target:]:
            extra_path = root / item["fileName"]
            try:
                extra_path.unlink()
            except FileNotFoundError:
                pass
        downloaded = downloaded[:target]
    images.extend(downloaded)
    write_manifest(root, base_url, images)
    return len(downloaded)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--category", choices=[cat["id"] for cat in CATEGORIES] + ["all"], default="all")
    parser.add_argument("--target", type=int, default=100)
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()
    root = Path(args.root)
    root.mkdir(parents=True, exist_ok=True)
    category_ids = [cat["id"] for cat in CATEGORIES] if args.category == "all" else [args.category]
    for category_id in category_ids:
        count = seed_category(category_id, root, args.base_url, args.target, args.workers)
        print(f"category_done {category_id}: {count}", flush=True)


if __name__ == "__main__":
    main()
