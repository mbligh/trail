#!/usr/bin/env python3
"""Download a small diagnostic sample of NJ 2020 imagery XYZ tiles from CSV.

Usage: python3 download_nj_tiles.py sample.csv
CSV columns: z,y,x (header optional)
"""
import argparse
import csv
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = 'https://maps.nj.gov/arcgis/rest/services/Basemap/Orthos_Natural_2020_NJ_WM/MapServer/tile'

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('csv_file', type=Path)
    p.add_argument('--output', type=Path, default=Path('tiles'))
    p.add_argument('--delay', type=float, default=0.3, help='Seconds between requests (default: 0.3)')
    p.add_argument('--timeout', type=float, default=20)
    args = p.parse_args()
    downloaded = skipped = failed = total_bytes = 0
    with args.csv_file.open(newline='') as f:
        for line_no, row in enumerate(csv.reader(f), 1):
            if not row or row[0].strip().startswith('#'):
                continue
            if [v.strip().lower() for v in row[:3]] == ['z', 'y', 'x']:
                continue
            try:
                z, y, x = (int(v.strip()) for v in row[:3])
            except (ValueError, IndexError):
                print('Invalid CSV row {}: {}'.format(line_no, row))
                failed += 1
                continue
            if not (0 <= z <= 22 and 0 <= x < (1 << z) and 0 <= y < (1 << z)):
                print('Invalid XYZ coordinates at line {}'.format(line_no))
                failed += 1
                continue
            folder = args.output / str(z) / str(x)
            # Service may return JPEG or PNG; inspect content type rather than assume.
            existing = [folder / (str(y) + ext) for ext in ('.jpg', '.png')]
            if any(path.exists() and path.stat().st_size > 0 for path in existing):
                skipped += 1
                continue
            url = '{}/{}/{}/{}'.format(BASE, z, y, x)
            for attempt in range(3):
                try:
                    req = urllib.request.Request(url, headers={'User-Agent': 'NJ-Tewksbury-Tile-Sampler/1.0'})
                    with urllib.request.urlopen(req, timeout=args.timeout) as response:
                        mime = response.headers.get('Content-Type', '').split(';')[0].lower()
                        payload = response.read()
                    if mime in ('image/jpeg', 'image/jpg') or payload.startswith(b'\xff\xd8\xff'):
                        ext = '.jpg'
                    elif mime == 'image/png' or payload.startswith(b'\x89PNG\r\n\x1a\n'):
                        ext = '.png'
                    else:
                        raise ValueError('Unexpected tile response type: {}'.format(mime))
                    folder.mkdir(parents=True, exist_ok=True)
                    destination = folder / (str(y) + ext)
                    tmp = destination.with_suffix(destination.suffix + '.part')
                    tmp.write_bytes(payload)
                    os.replace(str(tmp), str(destination))
                    downloaded += 1
                    total_bytes += len(payload)
                    print('z={} x={} y={}  {:,} bytes'.format(z, x, y, len(payload)))
                    break
                except (urllib.error.URLError, OSError, ValueError) as exc:
                    if attempt == 2:
                        print('FAILED {}: {}'.format(url, exc))
                        failed += 1
                    else:
                        time.sleep(2 ** attempt)
            time.sleep(max(0, args.delay))
    print('\nDownloaded: {} | Skipped: {} | Failed: {}'.format(downloaded, skipped, failed))
    print('New data: {:.2f} MiB'.format(total_bytes / (1024 * 1024)))
    if downloaded:
        print('Average new tile: {:.1f} KiB'.format(total_bytes / downloaded / 1024))

if __name__ == '__main__':
    main()
