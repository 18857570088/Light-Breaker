#!/usr/bin/env python3
"""Expand the LightBreaker cloud image library with new horizontal categories."""

from __future__ import annotations

import argparse
from pathlib import Path

import fetch_lightbreaker_commons_images as commons


SEASCAPE_CATEGORY = {
    "id": "nature",
    "nameZh": "自然风光",
    "nameEn": "Nature Landscapes",
    "description": "海景、海浪、沙滩、海岸线、落日与蓝色海面等横屏自然照片。",
    "queries": [
        "seascape ocean waves landscape photo filetype:bitmap",
        "calm sea beach sunset landscape photo filetype:bitmap",
        "tropical beach ocean landscape photo filetype:bitmap",
        "rocky coast seascape landscape photo filetype:bitmap",
        "sea horizon sunset landscape photo filetype:bitmap",
        "ocean shore waves landscape photo filetype:bitmap",
        "coastal cliffs sea landscape photo filetype:bitmap",
        "blue ocean panorama landscape photo filetype:bitmap",
    ],
}


def max_image_index(items: list[dict], category_id: str) -> int:
    return max([commons.parse_image_index(item, category_id) for item in items] + [0])


def append_to_category(
    root: Path,
    base_url: str,
    category: dict,
    target_total: int,
    workers: int,
) -> int:
    manifest = commons.load_manifest(root)
    images = manifest.get("images", [])
    current_items = [
        item
        for item in images
        if item.get("category") == category["id"]
        and commons.is_landscape_item(item)
        and (root / item.get("fileName", "")).exists()
    ]
    needed = max(0, target_total - len(current_items))
    if needed == 0:
        commons.write_manifest(root, base_url, images)
        print(f"{category['id']} already has {len(current_items)} images")
        return len(current_items)

    existing_sources = {item.get("sourceUrl") for item in images}
    candidates = [
        candidate
        for candidate in commons.gather_candidates(category, needed + 160)
        if candidate["info"].get("descriptionurl") not in existing_sources
    ]
    jobs = []
    start_index = max_image_index(current_items, category["id"]) + 1
    for index, candidate in enumerate(candidates[: needed + 30]):
        jobs.append((category, start_index + index, candidate, root, base_url))

    downloaded = []
    with commons.ThreadPoolExecutor(max_workers=workers) as executor:
        futures = [executor.submit(commons.download_one, job) for job in jobs]
        for future in commons.as_completed(futures):
            try:
                item = future.result()
                downloaded.append(item)
                if len(downloaded) % 5 == 0 or len(downloaded) == len(jobs):
                    print(f"downloaded {category['id']}: {len(downloaded)}/{len(jobs)}")
            except Exception as exc:
                print(f"warn download failed: {exc}")

    downloaded.sort(key=lambda item: item["id"])
    for extra in downloaded[needed:]:
        (root / extra["fileName"]).unlink(missing_ok=True)
    images.extend(downloaded[:needed])
    commons.write_manifest(root, base_url, images)
    return len(current_items) + min(len(downloaded), needed)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--workers", type=int, default=2)
    args = parser.parse_args()

    root = Path(args.root)
    root.mkdir(parents=True, exist_ok=True)

    nature_count = append_to_category(root, args.base_url, SEASCAPE_CATEGORY, 120, args.workers)
    print(f"category_done nature: {nature_count}")
    for category_id in ("pets", "scifi"):
        count = commons.seed_category(category_id, root, args.base_url, 50, args.workers)
        print(f"category_done {category_id}: {count}")


if __name__ == "__main__":
    main()
