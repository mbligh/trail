#!/usr/bin/env python3
"""Generate XYZ z,y,x tiles intersecting Tewksbury Township, NJ."""
import csv
import math
import sys
import requests
from shapely.geometry import shape, box
from shapely.ops import transform
from pyproj import Transformer

BOUNDARY_URL = 'https://maps.nj.gov/arcgis/rest/services/Framework/Government_Boundaries/MapServer/2/query'
M = 20037508.342789244


def main():
    response = requests.get(BOUNDARY_URL, params={
        'where': "NAME = 'Tewksbury Township'",
        'outFields': 'NAME',
        'outSR': 4326,
        'returnGeometry': 'true',
        'f': 'geojson',
    }, timeout=60)
    response.raise_for_status()
    data = response.json()
    features = data.get('features', [])
    if len(features) != 1:
        sys.exit(f'Expected one Tewksbury feature, found {len(features)}: {str(data)[:500]}')
    polygon = shape(features[0]['geometry'])
    if polygon.is_empty or not polygon.is_valid:
        sys.exit('Invalid township boundary geometry')
    projected = transform(Transformer.from_crs(4326, 3857, always_xy=True).transform, polygon)
    minx, miny, maxx, maxy = projected.bounds
    total = 0
    with open('tewksbury_xyz_15_19.csv', 'w', newline='') as file:
        writer = csv.writer(file)
        writer.writerow(['z', 'y', 'x'])
        for z in range(15, 20):
            width = 2 * M / (1 << z)
            xmin = max(0, math.floor((minx + M) / width))
            xmax = min((1 << z) - 1, math.floor((maxx + M) / width))
            ymin = max(0, math.floor((M - maxy) / width))
            ymax = min((1 << z) - 1, math.floor((M - miny) / width))
            count = 0
            for y in range(ymin, ymax + 1):
                for x in range(xmin, xmax + 1):
                    tile = box(-M + x * width, M - (y + 1) * width,
                               -M + (x + 1) * width, M - y * width)
                    if projected.intersects(tile):
                        writer.writerow([z, y, x])
                        count += 1
            print(f'Zoom {z}: {count:,} tiles')
            total += count
    print(f'Total: {total:,} tiles -> tewksbury_xyz_15_19.csv')


if __name__ == '__main__':
    main()
