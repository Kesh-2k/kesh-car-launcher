#!/usr/bin/env python3
"""Build streets.bin (offline street and city names for Kesh Car) from OpenStreetMap data.

Input: two GeoJSON files exported with osmium (see "Offline street names" in the README):
  roads.geojson   named highways of your region (LineStrings)
  cities.geojson  administrative boundaries (Polygons/MultiPolygons)

Usage:
  python3 build-streets.py roads.geojson cities.geojson --bbox W,S,E,N [-o streets.bin] [--admin-level 8]

--bbox limits which city boundaries are kept (cities fully outside it are skipped); roads are taken as they come,
so cut the extract to your region first (osmium extract -b ...).

File format (big-endian):
  "KCR1"
  names:  u16 count, (u16 len, utf-8 bytes)...
  roads:  i32 count, (u16 name, u16 n, n x (i32 lat*1e6, i32 lon*1e6))...
  cities: u16 count, (u16 name, u16 rings, ring x (u16 n, n x (i32, i32)))...
"""
import argparse
import json
import struct

SKIP_HIGHWAYS = ('footway', 'path', 'steps', 'cycleway', 'bridleway', 'corridor', 'proposed', 'construction')


def main():
    ap = argparse.ArgumentParser(description='Build streets.bin for Kesh Car from OSM GeoJSON exports.')
    ap.add_argument('roads', help='GeoJSON with named highways (LineStrings)')
    ap.add_argument('cities', help='GeoJSON with administrative boundaries')
    ap.add_argument('--bbox', required=True, help='W,S,E,N in degrees, e.g. -9.25,38.68,-9.08,38.80')
    ap.add_argument('--admin-level', default='8', help='OSM admin_level used as "city" (default 8 = municipality)')
    ap.add_argument('-o', '--output', default='streets.bin', help='output file (default streets.bin)')
    args = ap.parse_args()
    W, S, E, N = (float(v) for v in args.bbox.split(','))

    names, idx = [], {}

    def name_id(n):
        if n not in idx:
            idx[n] = len(names)
            names.append(n)
        return idx[n]

    def enc(lat, lon):
        return struct.pack('>ii', round(lat * 1e6), round(lon * 1e6))

    roads = []
    for f in json.load(open(args.roads, encoding='utf-8'))['features']:
        p, g = f['properties'], f['geometry']
        if not p.get('highway') or not p.get('name') or g['type'] != 'LineString':
            continue
        if p['highway'] in SKIP_HIGHWAYS:
            continue
        pts = g['coordinates']
        if len(pts) < 2:
            continue
        roads.append((name_id(p['name']), pts[:65000]))

    def thin(ring, maxn=600):
        """Keeps at most ~maxn points per boundary ring (plenty for a point-in-polygon test)."""
        step = max(1, len(ring) // maxn)
        r = ring[::step]
        if r[-1] != ring[-1]:
            r.append(ring[-1])
        return r

    cities = []
    for f in json.load(open(args.cities, encoding='utf-8'))['features']:
        p, g = f['properties'], f['geometry']
        if p.get('boundary') != 'administrative' or p.get('admin_level') != args.admin_level or not p.get('name'):
            continue
        if g['type'] not in ('Polygon', 'MultiPolygon'):
            continue
        polys = g['coordinates'] if g['type'] == 'MultiPolygon' else [g['coordinates']]
        xs = [q[0] for poly in polys for q in poly[0]]
        ys = [q[1] for poly in polys for q in poly[0]]
        if max(xs) < W or min(xs) > E or max(ys) < S or min(ys) > N:
            continue
        cities.append((name_id(p['name']), [thin(poly[0]) for poly in polys]))

    if len(names) > 65535:
        raise SystemExit(f'too many distinct names ({len(names)}): use a smaller region')

    b = bytearray(b'KCR1')
    b += struct.pack('>H', len(names))
    for n in names:
        u = n.encode('utf-8')[:300]
        b += struct.pack('>H', len(u)) + u
    b += struct.pack('>i', len(roads))
    for ni, pts in roads:
        b += struct.pack('>HH', ni, len(pts))
        for lon, lat in pts:
            b += enc(lat, lon)
    b += struct.pack('>H', len(cities))
    for ni, rings in cities:
        b += struct.pack('>HH', ni, len(rings))
        for r in rings:
            b += struct.pack('>H', len(r))
            for lon, lat in r:
                b += enc(lat, lon)
    with open(args.output, 'wb') as out:
        out.write(b)
    print(f'{len(roads)} roads, {len(names)} names, {len(cities)} cities, {len(b) // 1024} KB -> {args.output}')


if __name__ == '__main__':
    main()
