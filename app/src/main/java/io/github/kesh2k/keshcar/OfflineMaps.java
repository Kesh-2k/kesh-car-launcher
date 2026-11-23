package io.github.kesh2k.keshcar;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Offline map areas: the tiles of a rectangle chosen by the user, saved as an osmdroid SQLite archive
 * (files/osm/area-&lt;id&gt;.sqlite). osmdroid reads every archive in its base path before going to the network,
 * so a downloaded area keeps working without internet and is never trimmed like the normal tile cache.
 */
final class OfflineMaps {
  private OfflineMaps() {}

  /** Lowest zoom downloaded: country/state overview, a handful of tiles. */
  static final int MIN_ZOOM = 5;
  /** Detail levels offered (highest zoom downloaded). */
  static final int[] DETAIL = {14, 15, 16, 17};
  /**
   * tile.openstreetmap.org usage policy: "downloading an area of over 250 tiles at zoom level 13 or higher for
   * offline or later usage is forbidden". Enforced while the default OSM server is in use.
   */
  static final int OSM_MAX_DETAIL_TILES = 250, OSM_DETAIL_ZOOM = 13;
  /** Hard cap for any server (~600 MB). */
  static final long MAX_TILES = 40_000;
  /** Rough average size of a raster tile, for the size estimate. */
  static final long AVG_TILE_BYTES = 15 * 1024;

  static final class Area {
    String id, name, source;
    double north, south, east, west;
    int maxZoom, tiles;

    File file(Context c) { return new File(dir(c), "area-" + id + ".sqlite"); }
  }

  static File dir(Context c) { return new File(c.getFilesDir(), "osm"); }

  static int tileX(double lon, int z) {
    int n = 1 << z;
    return Math.max(0, Math.min(n - 1, (int) Math.floor((lon + 180) / 360 * n)));
  }

  static int tileY(double lat, int z) {
    int n = 1 << z;
    double r = Math.toRadians(Math.max(-85.0511, Math.min(85.0511, lat)));
    return Math.max(0, Math.min(n - 1, (int) Math.floor((1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * n)));
  }

  /** Number of tiles covering the box from zoom `zmin` to `zmax` (inclusive). */
  static long tileCount(double north, double south, double east, double west, int zmin, int zmax) {
    long total = 0;
    for (int z = zmin; z <= zmax; z++)
      total += (long) (tileX(east, z) - tileX(west, z) + 1) * (tileY(south, z) - tileY(north, z) + 1);
    return total;
  }

  static List<Area> load(Context c) {
    List<Area> out = new ArrayList<>();
    try {
      JSONArray a = new JSONArray(c.getSharedPreferences("home", Context.MODE_PRIVATE).getString("offline_maps", "[]"));
      for (int i = 0; i < a.length(); i++) {
        JSONObject o = a.getJSONObject(i);
        Area m = new Area();
        m.id = o.getString("id");
        m.name = o.optString("name");
        m.source = o.optString("source");
        m.north = o.getDouble("n");
        m.south = o.getDouble("s");
        m.east = o.getDouble("e");
        m.west = o.getDouble("w");
        m.maxZoom = o.optInt("z");
        m.tiles = o.optInt("tiles");
        if (m.file(c).isFile()) out.add(m);
      }
    } catch (Exception ignored) {}
    return out;
  }

  static void save(Context c, List<Area> areas) {
    JSONArray a = new JSONArray();
    try {
      for (Area m : areas)
        a.put(new JSONObject().put("id", m.id).put("name", m.name).put("source", m.source).put("n", m.north)
            .put("s", m.south).put("e", m.east).put("w", m.west).put("z", m.maxZoom).put("tiles", m.tiles));
    } catch (Exception ignored) {}
    c.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putString("offline_maps", a.toString()).apply();
  }

  static void add(Context c, Area m) {
    List<Area> l = load(c);
    l.add(m);
    save(c, l);
  }

  static void remove(Context c, Area m) {
    List<Area> l = load(c);
    for (int i = l.size() - 1; i >= 0; i--) if (l.get(i).id.equals(m.id)) l.remove(i);
    save(c, l);
    m.file(c).delete();
    new File(m.file(c).getPath() + "-journal").delete();
  }
}
