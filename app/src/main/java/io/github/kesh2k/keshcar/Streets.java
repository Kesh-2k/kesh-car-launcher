package io.github.kesh2k.keshcar;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline street and city names: reads a streets.bin file (built by tools/build-streets.py from OpenStreetMap
 * data) and looks up the nearest street segment on a ~550 m grid.
 *
 * The file is optional. It is looked up, in order, in the app's private files (imported by long-pressing the
 * street bar), in the app's external files folder (Android/data/io.github.kesh2k.keshcar/files/streets.bin) and in
 * the APK assets. Without it {@link #ready} stays false and the launcher falls back to online reverse geocoding.
 */
final class Streets {
  static final String FILE = "streets.bin";
  private static final double CELL = 0.005;
  private String[] names;
  private int[] segName;          // per segment: name index
  private float[] sLat1, sLon1, sLat2, sLon2;
  private final Map<Long, int[]> grid = new HashMap<>();
  private int[] cityName;
  private float[][][] cityRings;  // city → rings → [lat0, lon0, lat1, lon1, ...]
  private float[][] cityBox;      // minLat, minLon, maxLat, maxLon
  volatile boolean ready;

  /** Opens the imported file, else the external one, else the asset; throws if none exists. */
  private static InputStream open(Context c) throws java.io.IOException {
    File own = new File(c.getFilesDir(), FILE);
    if (own.isFile()) return new FileInputStream(own);
    File ext = c.getExternalFilesDir(null);
    if (ext != null) {
      File f = new File(ext, FILE);
      if (f.isFile()) return new FileInputStream(f);
    }
    return c.getAssets().open(FILE);
  }

  /** Loads in the background (~1 s for a 700 KB file on a RK3326 unit). Silently does nothing if there is no file. */
  void load(Context c) {
    try (DataInputStream in = new DataInputStream(new BufferedInputStream(open(c), 65536))) {
      byte[] magic = new byte[4];
      in.readFully(magic);
      if (!"KCR1".equals(new String(magic, "US-ASCII"))) return;
      int nNames = in.readUnsignedShort();
      names = new String[nNames];
      for (int i = 0; i < nNames; i++) {
        byte[] b = new byte[in.readUnsignedShort()];
        in.readFully(b);
        names[i] = new String(b, "UTF-8");
      }
      int nWays = in.readInt();
      List<float[]> segs = new ArrayList<>();
      List<Integer> segN = new ArrayList<>();
      for (int w = 0; w < nWays; w++) {
        int name = in.readUnsignedShort(), n = in.readUnsignedShort();
        float pLat = 0, pLon = 0;
        for (int i = 0; i < n; i++) {
          float lat = in.readInt() / 1e6f, lon = in.readInt() / 1e6f;
          if (i > 0) { segs.add(new float[]{pLat, pLon, lat, lon}); segN.add(name); }
          pLat = lat;
          pLon = lon;
        }
      }
      int ns = segs.size();
      segName = new int[ns];
      sLat1 = new float[ns]; sLon1 = new float[ns]; sLat2 = new float[ns]; sLon2 = new float[ns];
      Map<Long, List<Integer>> tmp = new HashMap<>();
      for (int i = 0; i < ns; i++) {
        float[] s = segs.get(i);
        segName[i] = segN.get(i);
        sLat1[i] = s[0]; sLon1[i] = s[1]; sLat2[i] = s[2]; sLon2[i] = s[3];
        int a0 = cell(Math.min(s[0], s[2])), a1 = cell(Math.max(s[0], s[2]));
        int b0 = cell(Math.min(s[1], s[3])), b1 = cell(Math.max(s[1], s[3]));
        for (int a = a0; a <= a1; a++) for (int b = b0; b <= b1; b++) {
          List<Integer> l = tmp.get(key(a, b));
          if (l == null) tmp.put(key(a, b), l = new ArrayList<>());
          l.add(i);
        }
      }
      for (Map.Entry<Long, List<Integer>> e : tmp.entrySet()) {
        int[] arr = new int[e.getValue().size()];
        for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
        grid.put(e.getKey(), arr);
      }
      int nc = in.readUnsignedShort();
      cityName = new int[nc];
      cityRings = new float[nc][][];
      cityBox = new float[nc][];
      for (int c2 = 0; c2 < nc; c2++) {
        cityName[c2] = in.readUnsignedShort();
        int rings = in.readUnsignedShort();
        cityRings[c2] = new float[rings][];
        float[] box = {90, 180, -90, -180};
        for (int r = 0; r < rings; r++) {
          int n = in.readUnsignedShort();
          float[] pts = new float[n * 2];
          for (int i = 0; i < n; i++) {
            pts[i * 2] = in.readInt() / 1e6f;
            pts[i * 2 + 1] = in.readInt() / 1e6f;
            box[0] = Math.min(box[0], pts[i * 2]); box[1] = Math.min(box[1], pts[i * 2 + 1]);
            box[2] = Math.max(box[2], pts[i * 2]); box[3] = Math.max(box[3], pts[i * 2 + 1]);
          }
          cityRings[c2][r] = pts;
        }
        cityBox[c2] = box;
      }
      ready = true;
    } catch (Exception ignored) {}
  }

  private static int cell(double v) { return (int) Math.floor(v / CELL); }

  private static long key(int a, int b) { return ((long) a << 32) ^ (b & 0xffffffffL); }

  private int lastSeg = -1;

  /**
   * Nearest street (within 40 m). `heading` in degrees (NaN = unknown): segments aligned with the car's
   * direction are preferred, which avoids picking the cross street at an intersection. The previous street
   * gets a small bonus so the name does not flicker.
   */
  String street(double lat, double lon, float heading) {
    if (!ready) return null;
    double kx = Math.cos(Math.toRadians(lat)) * 111_320, ky = 110_540;
    int ca = cell(lat), cb = cell(lon);
    int best = -1;
    double bestScore = Double.MAX_VALUE;
    for (int a = ca - 1; a <= ca + 1; a++) for (int b = cb - 1; b <= cb + 1; b++) {
      int[] list = grid.get(key(a, b));
      if (list == null) continue;
      for (int i : list) {
        double x1 = (sLon1[i] - lon) * kx, y1 = (sLat1[i] - lat) * ky, x2 = (sLon2[i] - lon) * kx, y2 = (sLat2[i] - lat) * ky;
        double dx = x2 - x1, dy = y2 - y1, len2 = dx * dx + dy * dy;
        double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(x1 * dx + y1 * dy) / len2));
        double px = x1 + t * dx, py = y1 + t * dy, d = Math.sqrt(px * px + py * py);
        if (d > 40) continue;
        double score = d;
        if (!Float.isNaN(heading) && len2 > 1) {
          double segBearing = Math.toDegrees(Math.atan2(dx, dy));
          double diff = Math.abs(((segBearing - heading) % 180 + 270) % 180 - 90); // 0 = same direction (either way)
          score += 18 * (diff / 90);
        }
        if (lastSeg >= 0 && segName[i] == segName[lastSeg]) score -= 6;
        if (score < bestScore) { bestScore = score; best = i; }
      }
    }
    if (best < 0) return null;
    lastSeg = best;
    return names[segName[best]];
  }

  /** City by its boundary (point in polygon). */
  String city(double lat, double lon) {
    if (!ready) return null;
    for (int c = 0; c < cityRings.length; c++) {
      float[] bx = cityBox[c];
      if (lat < bx[0] || lat > bx[2] || lon < bx[1] || lon > bx[3]) continue;
      for (float[] r : cityRings[c]) if (inside(r, lat, lon)) return names[cityName[c]];
    }
    return null;
  }

  private static boolean inside(float[] r, double lat, double lon) {
    boolean in = false;
    int n = r.length / 2;
    for (int i = 0, j = n - 1; i < n; j = i++) {
      double yi = r[i * 2], xi = r[i * 2 + 1], yj = r[j * 2], xj = r[j * 2 + 1];
      if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) in = !in;
    }
    return in;
  }
}
