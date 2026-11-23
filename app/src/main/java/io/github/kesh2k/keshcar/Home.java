package io.github.kesh2k.keshcar;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.MapTileProviderBasic;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.modules.SqliteArchiveTileWriter;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.XYTileSource;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Overlay;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;

/**
 * Kesh Car: sidebar (clock, date, app shortcuts), map in the middle (OpenStreetMap, follows the GPS, swipe to the
 * "My car" page) and, on the right, quick tiles (Wi-Fi, Bluetooth, weather) + the now-playing card.
 * Everything is built in code with plain Android views to stay light on cheap head units.
 */
public class Home extends Activity {
  static final int TXT = 0xFFF8FAFC, MUTED = 0xB3FFFFFF, ACCENT = 0xFFFF4D6D, GLASS = 0x40000000, LINE = 0x22FFFFFF;
  static final String USER_AGENT = "KeshCar/1.0 (+https://github.com/Kesh-2k/kesh-car-launcher)";

  /**
   * Default sidebar shortcuts, one row per slot. Each row lists candidates {package, activity (null = default)};
   * the first installed one wins, so head-unit specific apps (Jancar, FYT/SYU, MTCD...) are only fallbacks and
   * are skipped when missing. A slot with nothing installed shows "+".
   */
  static final String[][][] DEFAULTS = {
      {{"com.waze", null}},
      {{"com.google.android.apps.maps", null}},
      {{"com.google.android.youtube", null}, {"com.google.android.apps.youtube.music", null}},
      {{"com.spotify.music", null}},
      {{"com.jancar.radio", null}, {"com.syu.radio", null}, {"com.microntek.radio", null}, {"com.android.fmradio", null}},
      {{"com.jancar.bluetooth", "com.jancar.bluetooth.activity.MainActivity"}, {"com.syu.bt", null}, {"com.microntek.bluetooth", null},
          {"com.google.android.dialer", null}, {"com.android.dialer", null}},
  };
  static final int SLOTS = 6;

  final Handler ui = new Handler(Looper.getMainLooper());
  FrameLayout root, drawer;
  TextView streetLabel, clock, dockTemp, speedNum, street, mTitle, mArtist, mApp;
  ImageView mArt, mBg;
  Btn bPrev, bPlay, bNext;
  View mBar;
  TextView mPos, mDur;
  FrameLayout mBarBox;
  LinearLayout dockApps, quick;
  MapView map;
  Me me;
  boolean dark, following = true;

  LocationManager lm;
  Location lastLoc, geoLoc;
  long lastWeatherAt, lastGeoAt;
  MediaSessionManager msm;
  MediaController ctl;
  ComponentName listener;
  Bitmap lastArt;

  // ---------- lifecycle ----------

  @Override protected void onCreate(Bundle b) {
    super.onCreate(b);
    org.osmdroid.config.IConfigurationProvider c = Configuration.getInstance();
    c.setUserAgentValue(USER_AGENT);
    // base path = where osmdroid looks for offline archives (downloaded areas); the tile cache stays in cache/
    File osm = OfflineMaps.dir(this);
    osm.mkdirs();
    c.setOsmdroidBasePath(osm);
    c.setOsmdroidTileCache(new File(getCacheDir(), "osm/tiles"));
    c.setTileFileSystemCacheMaxBytes(300L * 1024 * 1024);
    c.setTileFileSystemCacheTrimBytes(250L * 1024 * 1024);
    lm = (LocationManager) getSystemService(LOCATION_SERVICE);
    msm = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
    listener = new ComponentName(this, MediaListener.class);
    mph = getSharedPreferences("home", MODE_PRIVATE).getBoolean("mph", false);
    setContentView(build());
    fillApps();
    new Thread(() -> { streets.load(this); ui.post(this::maybeStreet); }).start();
  }

  @Override protected void onStart() {
    super.onStart();
    IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
    f.addAction(Intent.ACTION_TIME_CHANGED);
    f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
    registerReceiver(tick, f);
    IntentFilter p = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
    p.addAction(Intent.ACTION_PACKAGE_REMOVED);
    p.addAction(Intent.ACTION_PACKAGE_CHANGED);
    p.addDataScheme("package");
    registerReceiver(pkgs, p);
    updateClock();
    askLocation();
    startGps();
    startMedia();
    startStatus();
    maybeWeather();
  }

  @Override protected void onResume() { super.onResume(); immersive(); map.onResume(); }

  @Override protected void onPause() { super.onPause(); map.onPause(); }

  @Override protected void onStop() {
    super.onStop();
    try { unregisterReceiver(tick); } catch (Exception ignored) {}
    try { unregisterReceiver(pkgs); } catch (Exception ignored) {}
    try { unregisterReceiver(netBt); } catch (Exception ignored) {}
    try { lm.removeUpdates(gps); } catch (Exception ignored) {}
    try { lm.unregisterGnssStatusCallback(gnss); } catch (Exception ignored) {}
    stopMedia();
    getSharedPreferences("home", MODE_PRIVATE).edit().putLong("stopped_at", System.currentTimeMillis()).apply();
    if (tripLoaded) saveTrip();
    if (browser != null) { try { browser.disconnect(); } catch (Exception ignored) {} browser = null; }
  }

  boolean askedLocation;

  /** Runtime location permission, asked once per launch (ADB-provisioned units already have it). */
  void askLocation() {
    if (askedLocation || checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return;
    askedLocation = true;
    requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
  }

  @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
    super.onRequestPermissionsResult(req, perms, res);
    if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
      street.setText(R.string.searching_gps);
      streetLabel.setText(gpsLabel());
      startGps();
    }
  }

  @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) immersive(); }

  @Override public void onBackPressed() { if (drawer != null) closeDrawer(); else if (areaBox != null) closeAreaPicker(); else showPage(0); }

  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); if (drawer != null) closeDrawer(); }

  /** Sticky immersive mode: the system bars show up on a swipe and hide again by themselves. */
  void immersive() {
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  final BroadcastReceiver tick = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) { updateClock(); maybeWeather(); }
  };
  final BroadcastReceiver pkgs = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) { favsCache = null; fillApps(); updateCanChip(); }
  };

  // ---------- layout ----------

  View build() {
    root = new FrameLayout(this);
    root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1E1B4B, 0xFF4C1D95, 0xFF831843, 0xFF7F1D1D}));
    root.addView(glow(0x66EC4899, 700), glowLp(700, Gravity.TOP | Gravity.END, 250, -300));
    root.addView(glow(0x553B82F6, 600), glowLp(600, Gravity.BOTTOM | Gravity.START, -250, 250));

    LinearLayout main = new LinearLayout(this);
    main.setPadding(dp(14), dp(14), dp(14), dp(14));
    root.addView(main, new FrameLayout.LayoutParams(-1, -1));

    main.addView(buildDock(), new LinearLayout.LayoutParams(dp(104), -1));
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -1, 1);
    mp.leftMargin = dp(14);
    main.addView(buildPager(), mp);

    LinearLayout right = new LinearLayout(this);
    right.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(470), -1);
    rp.leftMargin = dp(14);
    main.addView(right, rp);
    quick = glassBg(dp(26));
    quick.setOrientation(LinearLayout.HORIZONTAL);
    quick.setGravity(Gravity.CENTER);
    right.addView(quick, new LinearLayout.LayoutParams(-1, dp(170)));
    buildStatus();
    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, 0, 1);
    mlp.topMargin = dp(14);
    right.addView(buildMedia(), mlp);
    return root;
  }

  View buildDock() {
    LinearLayout d = glassBg(dp(26));
    d.setGravity(Gravity.CENTER_HORIZONTAL);
    d.setPadding(0, dp(18), 0, dp(14));
    clock = text(26, TXT, "sans-serif-medium");
    clock.setGravity(Gravity.CENTER);
    clock.setOnLongClickListener(v -> { if (!open("com.jancar.settings", null)) start(new Intent(android.provider.Settings.ACTION_SETTINGS)); return true; });
    d.addView(clock);
    dockTemp = text(13, MUTED, "sans-serif-medium");
    dockTemp.setGravity(Gravity.CENTER);
    dockTemp.setLetterSpacing(0.06f);
    d.addView(dockTemp);
    dockApps = new LinearLayout(this);
    dockApps.setOrientation(LinearLayout.VERTICAL);
    dockApps.setGravity(Gravity.CENTER);
    d.addView(dockApps, new LinearLayout.LayoutParams(-1, 0, 1));
    ImageView all = new ImageView(this);
    all.setImageDrawable(new Dots());
    all.setPadding(dp(18), dp(18), dp(18), dp(18));
    all.setOnClickListener(v -> openDrawer(-1));
    press(all);
    d.addView(all, new LinearLayout.LayoutParams(dp(72), dp(72)));
    return d;
  }

  // ---------- center pages: map <-> my car ----------

  Pager pager;
  View carPage;
  View[] dots;
  int page;
  TextView tripKm, tripTime, tripMax, totalKm, tripSince;

  View buildPager() {
    pager = new Pager(this);
    clip(pager, dp(26));
    pager.addView(buildMap(), new FrameLayout.LayoutParams(-1, -1));
    carPage = buildCar();
    pager.addView(carPage, new FrameLayout.LayoutParams(-1, -1));
    // page dots (top center)
    LinearLayout dl = new LinearLayout(this);
    dl.setGravity(Gravity.CENTER);
    dl.setPadding(dp(10), dp(6), dp(10), dp(6));
    GradientDrawable db = new GradientDrawable();
    db.setColor(0x66000000);
    db.setCornerRadius(dp(12));
    dl.setBackground(db);
    dots = new View[2];
    for (int i = 0; i < 2; i++) {
      final int pg = i;
      View d = new View(this);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(8), dp(8));
      lp.leftMargin = lp.rightMargin = dp(4);
      dl.addView(d, lp);
      d.setOnClickListener(v -> showPage(pg));
      dots[i] = d;
    }
    dl.setOnClickListener(v -> showPage(1 - page));
    FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    dlp.topMargin = dp(12);
    pager.addView(dl, dlp);
    dotsBar = dl;
    pager.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, orr, ob) -> placePages(false));
    paintDots();
    return pager;
  }

  void paintDots() {
    for (int i = 0; i < dots.length; i++) {
      GradientDrawable g = new GradientDrawable();
      g.setShape(GradientDrawable.OVAL);
      g.setColor(i == page ? Color.WHITE : 0x80FFFFFF);
      dots[i].setBackground(g);
    }
  }

  void showPage(int p) {
    if (p == page) return;
    page = p;
    paintDots();
    placePages(true);
    if (p == 1) updateTrip();
  }

  void placePages(boolean anim) {
    int w = pager.getWidth();
    if (w == 0) return;
    View mapBox = pager.getChildAt(0);
    float mx = page == 0 ? 0 : -w, cx = page == 0 ? w : 0;
    if (anim) {
      mapBox.animate().translationX(mx).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
      carPage.animate().translationX(cx).setDuration(280).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
    } else { mapBox.setTranslationX(mx); carPage.setTranslationX(cx); }
  }

  /**
   * Center frame that switches pages with a quick one-finger horizontal fling.
   * The map still gets the touch (a slow drag or two fingers moves the map); when the gesture turns out to be a
   * page switch, the map goes back to following the car if it was before.
   */
  class Pager extends FrameLayout {
    float x0, y0;
    long t0;
    boolean multi, wasFollowing;
    Pager(Context c) { super(c); }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
      if (areaBox != null) return super.dispatchTouchEvent(e);
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: x0 = e.getX(); y0 = e.getY(); t0 = e.getEventTime(); multi = false; wasFollowing = following; break;
        case MotionEvent.ACTION_POINTER_DOWN: multi = true; break;
        case MotionEvent.ACTION_UP: {
          float dx = e.getX() - x0, dy = e.getY() - y0;
          if (!multi && Math.abs(dx) > dp(110) && Math.abs(dx) > 2 * Math.abs(dy) && e.getEventTime() - t0 < 700) {
            int to = dx < 0 ? Math.min(1, page + 1) : Math.max(0, page - 1);
            if (to != page) {
              MotionEvent cancel = MotionEvent.obtain(e);
              cancel.setAction(MotionEvent.ACTION_CANCEL);
              super.dispatchTouchEvent(cancel);
              cancel.recycle();
              if (wasFollowing) {
                following = true;
                if (lastLoc != null) map.getController().setCenter(new GeoPoint(lastLoc));
              }
              showPage(to);
              return true;
            }
          }
          break;
        }
      }
      return super.dispatchTouchEvent(e);
    }
  }

  TextView carName, carDetails;
  ImageView carImg;
  static final int PICK_PHOTO = 41;
  static final String CAR_PHOTO = "car_photo";

  /** "My car" page: the user's car (generic illustration or their own photo) + trip numbers measured by the GPS. */
  View buildCar() {
    FrameLayout c = new FrameLayout(this);
    c.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xFF0F172A, 0xFF1E1B4B, 0xFF0B1020}));
    c.setClickable(true);
    // "showroom" light behind the car
    View spot = glow(0x5594A3B8, 620);
    c.addView(spot, new FrameLayout.LayoutParams(dp(620), dp(620), Gravity.CENTER));
    spot.setTranslationY(-dp(40));

    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    col.setPadding(dp(30), dp(44), dp(30), dp(20));
    c.addView(col, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.TOP);
    LinearLayout names = new LinearLayout(this);
    names.setOrientation(LinearLayout.VERTICAL);
    TextView brand = text(13, MUTED, "sans-serif-medium");
    brand.setLetterSpacing(0.3f);
    brand.setText(R.string.car_label);
    names.addView(brand);
    carName = text(38, TXT, "sans-serif-black");
    carName.setIncludeFontPadding(false);
    carName.setSingleLine(true);
    carName.setEllipsize(TextUtils.TruncateAt.END);
    names.addView(carName);
    carDetails = text(15, MUTED, "sans-serif");
    carDetails.setSingleLine(true);
    carDetails.setEllipsize(TextUtils.TruncateAt.END);
    names.addView(carDetails);
    head.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
    TextView edit = chip(getString(R.string.car_edit));
    edit.setOnClickListener(v -> editCar());
    head.addView(edit);
    col.addView(head);

    FrameLayout stage = new FrameLayout(this);
    View floor = new View(this) {
      final Paint sh = new Paint(Paint.ANTI_ALIAS_FLAG);
      @Override protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        sh.setShader(new android.graphics.RadialGradient(w / 2, h / 2, w / 2, 0xAA000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        cv.save();
        cv.scale(1f, h / w, w / 2, h / 2);
        cv.drawCircle(w / 2, h / 2, w / 2, sh);
        cv.restore();
      }
    };
    FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-1, dp(46), Gravity.BOTTOM);
    fl.leftMargin = fl.rightMargin = dp(10);
    stage.addView(floor, fl);
    carImg = new ImageView(this);
    carImg.setAdjustViewBounds(true);
    carImg.setScaleType(ImageView.ScaleType.FIT_CENTER);
    carImg.setOnLongClickListener(v -> { editCar(); return true; });
    FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    cl.bottomMargin = dp(16);
    stage.addView(carImg, cl);
    col.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
    showCarInfo();
    loadCarImage();

    // trip numbers
    LinearLayout stats = new LinearLayout(this);
    stats.setPadding(0, dp(6), 0, 0);
    tripKm = stat(stats, getString(R.string.trip));
    tripTime = stat(stats, getString(R.string.driving));
    tripMax = stat(stats, getString(R.string.top_speed));
    totalKm = stat(stats, getString(R.string.total));
    col.addView(stats, new LinearLayout.LayoutParams(-1, dp(92)));

    LinearLayout foot = new LinearLayout(this);
    foot.setGravity(Gravity.CENTER_VERTICAL);
    foot.setPadding(0, dp(12), 0, 0);
    tripSince = text(12, MUTED, "sans-serif");
    foot.addView(tripSince, new LinearLayout.LayoutParams(0, -2, 1));
    // CAN-bus vehicle data app of Jancar units: only shown when it is installed (see updateCanChip)
    canChip = chip(getString(R.string.vehicle_data));
    canChip.setOnClickListener(v -> open("com.can.activity", "com.can.ui.CanActivity"));
    foot.addView(canChip);
    updateCanChip();
    col.addView(foot);

    updateTrip();
    return c;
  }

  TextView canChip;

  void updateCanChip() {
    if (canChip != null) canChip.setVisibility(launchIntent("com.can.activity", "com.can.ui.CanActivity") != null ? View.VISIBLE : View.GONE);
  }

  /** Small rounded "glass" button. */
  TextView chip(String label) {
    TextView t = text(15, TXT, "sans-serif-medium");
    t.setText(label);
    t.setPadding(dp(18), dp(10), dp(18), dp(10));
    GradientDrawable cb = new GradientDrawable();
    cb.setColor(0x26FFFFFF);
    cb.setCornerRadius(dp(14));
    t.setBackground(cb);
    press(t);
    return t;
  }

  void showCarInfo() {
    android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
    String n = sp.getString("car_name", ""), d = sp.getString("car_details", "");
    carName.setText(n.isEmpty() ? getString(R.string.car_default_name) : n);
    carDetails.setText(n.isEmpty() && d.isEmpty() ? getString(R.string.car_default_details) : d);
  }

  /** The user's photo (files/car_photo) or the generic illustration. Decoded off the UI thread. */
  void loadCarImage() {
    final File f = new File(getFilesDir(), CAR_PHOTO);
    if (!f.isFile()) { carImg.setImageResource(R.drawable.car_generic); return; }
    new Thread(() -> {
      final Bitmap b = BitmapFactory.decodeFile(f.getPath());
      ui.post(() -> { if (b != null) carImg.setImageBitmap(b); else carImg.setImageResource(R.drawable.car_generic); });
    }).start();
  }

  /** Dialog to set the car name/details and pick or remove the photo (Edit button or long-press on the car). */
  void editCar() {
    final android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
    LinearLayout form = new LinearLayout(this);
    form.setOrientation(LinearLayout.VERTICAL);
    form.setPadding(dp(24), dp(8), dp(24), 0);
    final EditText name = new EditText(this), details = new EditText(this);
    name.setHint(R.string.car_name_hint);
    name.setText(sp.getString("car_name", ""));
    details.setHint(R.string.car_details_hint);
    details.setText(sp.getString("car_details", ""));
    for (EditText e : new EditText[]{name, details}) {
      e.setSingleLine(true);
      e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
      form.addView(e, new LinearLayout.LayoutParams(-1, -2));
    }
    final Runnable save = () -> {
      sp.edit().putString("car_name", name.getText().toString().trim()).putString("car_details", details.getText().toString().trim()).apply();
      showCarInfo();
    };
    final File photo = new File(getFilesDir(), CAR_PHOTO);
    AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(R.string.car_edit_title)
        .setView(form)
        .setPositiveButton(R.string.save, (d, w) -> save.run())
        .setNegativeButton(R.string.cancel, null)
        .setNeutralButton(R.string.car_choose_photo, (d, w) -> { save.run(); pickPhoto(); });
    if (photo.isFile()) {
      TextView rm = text(15, 0xFFFDA4AF, "sans-serif-medium");
      rm.setText(R.string.car_remove_photo);
      rm.setPadding(0, dp(14), 0, dp(6));
      form.addView(rm);
      final AlertDialog[] dlg = new AlertDialog[1];
      rm.setOnClickListener(v -> { save.run(); photo.delete(); loadCarImage(); if (dlg[0] != null) dlg[0].dismiss(); });
      dlg[0] = b.create();
      dlg[0].setOnDismissListener(d -> immersive());
      dlg[0].show();
      return;
    }
    AlertDialog d = b.create();
    d.setOnDismissListener(x -> immersive());
    d.show();
  }

  void pickPhoto() {
    Intent get = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
    try { startActivityForResult(get, PICK_PHOTO); return; } catch (Exception ignored) {}
    try { startActivityForResult(new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), PICK_PHOTO); return; }
    catch (Exception ignored) {}
    Toast.makeText(this, R.string.car_no_gallery, Toast.LENGTH_LONG).show();
  }

  static final int PICK_STREETS = 42;

  void pickStreets() {
    Intent get = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
    try { startActivityForResult(get, PICK_STREETS); }
    catch (Exception e) { Toast.makeText(this, R.string.car_no_gallery, Toast.LENGTH_LONG).show(); }
  }

  /** Copies the picked file to files/streets.bin if it is a valid street file, then reloads it. */
  void importStreets(final android.net.Uri uri) {
    new Thread(() -> {
      File tmp = new File(getFilesDir(), Streets.FILE + ".tmp");
      boolean ok = false;
      try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(tmp)) {
        byte[] buf = new byte[65536];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
          if (total == 0 && (n < 4 || buf[0] != 'K' || buf[1] != 'C' || buf[2] != 'R' || buf[3] != '1')) break;
          out.write(buf, 0, n);
          total += n;
        }
        ok = total > 0;
      } catch (Exception ignored) {}
      Streets fresh = null;
      if (ok && tmp.renameTo(new File(getFilesDir(), Streets.FILE))) {
        fresh = new Streets();
        fresh.load(this);
      } else tmp.delete();
      final Streets loaded = fresh != null && fresh.ready ? fresh : null;
      ui.post(() -> {
        Toast.makeText(this, loaded != null ? R.string.streets_imported : R.string.streets_invalid, Toast.LENGTH_LONG).show();
        if (loaded != null) { streets = loaded; offlineShown = null; maybeStreet(); }
      });
    }).start();
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (res != RESULT_OK || data == null || data.getData() == null) return;
    if (req == PICK_STREETS) { importStreets(data.getData()); return; }
    if (req != PICK_PHOTO) return;
    final android.net.Uri uri = data.getData();
    new Thread(() -> {
      final boolean ok = saveCarPhoto(uri);
      ui.post(() -> { if (ok) loadCarImage(); else Toast.makeText(this, R.string.car_photo_error, Toast.LENGTH_LONG).show(); });
    }).start();
  }

  /** Copies the picked image to files/car_photo: at most 1200 px, EXIF rotation applied, PNG if it has transparency. */
  boolean saveCarPhoto(android.net.Uri uri) {
    try {
      BitmapFactory.Options o = new BitmapFactory.Options();
      o.inJustDecodeBounds = true;
      try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, o); }
      if (o.outWidth <= 0 || o.outHeight <= 0) return false;
      int sample = 1;
      while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1200) sample *= 2;
      BitmapFactory.Options o2 = new BitmapFactory.Options();
      o2.inSampleSize = sample;
      Bitmap b;
      try (InputStream in = getContentResolver().openInputStream(uri)) { b = BitmapFactory.decodeStream(in, null, o2); }
      if (b == null) return false;
      int rot = 0;
      try (InputStream in = getContentResolver().openInputStream(uri)) {
        int or = new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
        rot = or == 6 ? 90 : or == 3 ? 180 : or == 8 ? 270 : 0;
      } catch (Exception ignored) {}
      float sc = Math.min(1f, 1200f / Math.max(b.getWidth(), b.getHeight()));
      if (rot != 0 || sc < 1f) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(sc, sc);
        m.postRotate(rot);
        b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
      }
      File tmp = new File(getFilesDir(), CAR_PHOTO + ".tmp");
      try (FileOutputStream out = new FileOutputStream(tmp)) {
        if (b.hasAlpha()) b.compress(Bitmap.CompressFormat.PNG, 100, out);
        else b.compress(Bitmap.CompressFormat.JPEG, 90, out);
      }
      return tmp.renameTo(new File(getFilesDir(), CAR_PHOTO));
    } catch (Exception | OutOfMemoryError e) { return false; }
  }

  TextView stat(LinearLayout row, String label) {
    LinearLayout b = new LinearLayout(this);
    b.setOrientation(LinearLayout.VERTICAL);
    b.setGravity(Gravity.CENTER);
    GradientDrawable g = new GradientDrawable();
    g.setColor(0x1AFFFFFF);
    g.setStroke(1, LINE);
    g.setCornerRadius(dp(18));
    b.setBackground(g);
    TextView v = text(24, TXT, "sans-serif-medium");
    v.setGravity(Gravity.CENTER);
    v.setSingleLine(true);
    b.addView(v);
    TextView l = text(11, MUTED, "sans-serif-medium");
    l.setLetterSpacing(0.12f);
    l.setText(label);
    l.setGravity(Gravity.CENTER);
    l.setPadding(0, dp(2), 0, 0);
    b.addView(l);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
    if (row.getChildCount() > 0) lp.leftMargin = dp(10);
    row.addView(b, lp);
    return v;
  }

  // ---------- trip (GPS): ends after 20 min stopped ----------

  float tripM, totalM, tripMaxKmh;
  long tripMs, tripStart, tripLastAt;
  boolean tripLoaded;

  void loadTrip() {
    if (tripLoaded) return;
    tripLoaded = true;
    android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
    tripM = sp.getFloat("trip_m", 0);
    totalM = sp.getFloat("total_m", 0);
    tripMaxKmh = sp.getFloat("trip_max", 0);
    tripMs = sp.getLong("trip_ms", 0);
    tripStart = sp.getLong("trip_start", 0);
    tripLastAt = sp.getLong("trip_last", 0);
  }

  void saveTrip() {
    getSharedPreferences("home", MODE_PRIVATE).edit()
        .putFloat("trip_m", tripM).putFloat("total_m", totalM).putFloat("trip_max", tripMaxKmh)
        .putLong("trip_ms", tripMs).putLong("trip_start", tripStart).putLong("trip_last", tripLastAt).apply();
  }

  /** Distance driven between two GPS fixes (already filtered: >= 8 m). */
  void addTrip(float meters, float secs, int kmh) {
    loadTrip();
    long now = System.currentTimeMillis();
    if (tripLastAt == 0 || now - tripLastAt > 20 * 60_000L) { tripM = 0; tripMs = 0; tripMaxKmh = 0; tripStart = now; }
    tripLastAt = now;
    if (meters < 400) { tripM += meters; totalM += meters; }
    if (secs > 0 && secs < 15) tripMs += (long) (secs * 1000);
    if (kmh > tripMaxKmh && kmh < 220) tripMaxKmh = kmh;
    if (page == 1) updateTrip();
  }

  void updateTrip() {
    if (tripKm == null) return;
    loadTrip();
    boolean old = tripLastAt == 0 || System.currentTimeMillis() - tripLastAt > 20 * 60_000L || tripM < 100;
    setIfChanged(tripKm, distance(old ? 0 : tripM, false));
    long min = old ? 0 : tripMs / 60_000;
    setIfChanged(tripTime, min >= 60 ? (min / 60) + "h" + String.format(Locale.US, "%02d", min % 60) : min + " min");
    setIfChanged(tripMax, (old ? 0 : Math.round(mph ? tripMaxKmh / KM_PER_MI : tripMaxKmh)) + " " + speedUnitLabel());
    setIfChanged(totalKm, distance(totalM, true));
    setIfChanged(tripSince, old ? getString(R.string.trip_none)
        : getString(R.string.trip_since, DateFormat.getTimeFormat(this).format(new Date(tripStart))));
  }

  // ---------- units: km/h + km or mph + miles ----------

  static final float KM_PER_MI = 1.609344f;
  boolean mph;
  float lastMps = -1;
  TextView speedUnit;

  String speedUnitLabel() { return mph ? "mph" : "km/h"; }

  /** Distance in the chosen unit: one decimal below 10 (100 for the odometer-like total), whole numbers above. */
  String distance(float meters, boolean total) {
    float v = meters / 1000f / (mph ? KM_PER_MI : 1f);
    String u = mph ? " mi" : " km";
    Locale loc = Locale.getDefault();
    return v < (total ? 100 : 10) ? String.format(loc, "%.1f", v) + u : String.format(loc, "%,d", Math.round(v)) + u;
  }

  void showSpeed() {
    if (lastMps < 0) return;
    int v = Math.round(lastMps * (mph ? 2.2369363f : 3.6f));
    setIfChanged(speedNum, String.valueOf(v < 2 ? 0 : v));
  }

  void setMph(boolean on) {
    if (on == mph) return;
    mph = on;
    getSharedPreferences("home", MODE_PRIVATE).edit().putBoolean("mph", on).apply();
    speedUnit.setText(speedUnitLabel());
    showSpeed();
    updateTrip();
  }

  FrameLayout mapBox;
  View speedBox, recenterBtn, gearBtn, streetBar, dotsBar;
  int mapOffsetY;

  View buildMap() {
    FrameLayout box = new FrameLayout(this);
    mapBox = box;
    clip(box, dp(26));
    map = new MapView(this);
    dark = isNight();
    map.setTileSource(tiles());
    applyNight();
    map.setMultiTouchControls(true);
    map.setTilesScaledToDpi(false);
    map.getZoomController().setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER);
    map.setMinZoomLevel(4.0);
    map.setMaxZoomLevel(19.0);
    // no position yet: whole-world view until the first GPS fix (or the saved position) zooms in
    map.getController().setZoom(4.0);
    map.getController().setCenter(new GeoPoint(20.0, 0.0));
    me = new Me();
    // the car sits in the lower third of the map to show more of the road ahead
    map.addOnFirstLayoutListener((v, l, t, r, b) -> { mapOffsetY = (b - t) / 5; if (areaBox == null) map.setMapCenterOffset(0, mapOffsetY); });
    map.getOverlays().add(me);
    map.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_MOVE) following = false; return false; });
    box.addView(map, new FrameLayout.LayoutParams(-1, -1));

    // map data attribution (required by the OpenStreetMap license)
    TextView attr = text(10, 0xCCFFFFFF, "sans-serif");
    attr.setText(R.string.osm_attribution);
    attr.setPadding(dp(8), dp(3), dp(8), dp(3));
    GradientDrawable ab = new GradientDrawable();
    ab.setColor(0x80000000);
    ab.setCornerRadius(dp(8));
    attr.setBackground(ab);
    FrameLayout.LayoutParams al = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    al.setMargins(dp(14), dp(14), 0, 0);
    box.addView(attr, al);

    // offline map download in progress (tap to cancel)
    dlChip = text(13, TXT, "sans-serif-medium");
    dlChip.setPadding(dp(12), dp(6), dp(12), dp(6));
    GradientDrawable dcb = new GradientDrawable();
    dcb.setColor(0xE6111827);
    dcb.setCornerRadius(dp(10));
    dlChip.setBackground(dcb);
    dlChip.setVisibility(View.GONE);
    dlChip.setOnClickListener(v -> confirmCancelDownload());
    FrameLayout.LayoutParams dcl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    dcl.setMargins(dp(14), dp(44), 0, 0);
    box.addView(dlChip, dcl);

    // speed in the corner
    LinearLayout sp = new LinearLayout(this);
    sp.setOrientation(LinearLayout.VERTICAL);
    sp.setGravity(Gravity.CENTER);
    GradientDrawable sb = new GradientDrawable();
    sb.setShape(GradientDrawable.OVAL);
    sb.setColor(0xF2111827);
    sb.setStroke(dp(5), ACCENT);
    sp.setBackground(sb);
    sp.setElevation(dp(6));
    speedNum = text(34, TXT, "sans-serif-medium");
    speedNum.setIncludeFontPadding(false);
    speedNum.setGravity(Gravity.CENTER);
    speedNum.setText("--");
    sp.addView(speedNum, new LinearLayout.LayoutParams(-2, -2));
    speedUnit = text(12, MUTED, "sans-serif");
    speedUnit.setText(speedUnitLabel());
    sp.addView(speedUnit, new LinearLayout.LayoutParams(-2, -2));
    FrameLayout.LayoutParams spl = new FrameLayout.LayoutParams(dp(100), dp(100), Gravity.TOP | Gravity.END);
    spl.setMargins(0, dp(16), dp(16), 0);
    box.addView(sp, spl);
    speedBox = sp;

    // re-center on the car
    ImageView rc = new ImageView(this);
    rc.setImageDrawable(new Target());
    rc.setPadding(dp(14), dp(14), dp(14), dp(14));
    GradientDrawable rb = new GradientDrawable();
    rb.setShape(GradientDrawable.OVAL);
    rb.setColor(0xF2FFFFFF);
    rc.setBackground(rb);
    rc.setElevation(dp(4));
    rc.setOnClickListener(v -> {
      following = true;
      map.getController().setZoom(16.5);
      if (lastLoc != null) map.getController().animateTo(new GeoPoint(lastLoc));
      if (headingOk) rotateMap(-heading);
    });
    press(rc);
    FrameLayout.LayoutParams rcl = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM | Gravity.END);
    rcl.setMargins(0, 0, dp(18), dp(104));
    box.addView(rc, rcl);
    recenterBtn = rc;

    // settings: units, offline maps, street file
    ImageView gear = new ImageView(this);
    gear.setImageDrawable(new Gear());
    gear.setPadding(dp(14), dp(14), dp(14), dp(14));
    GradientDrawable gb0 = new GradientDrawable();
    gb0.setShape(GradientDrawable.OVAL);
    gb0.setColor(0xF2FFFFFF);
    gear.setBackground(gb0);
    gear.setElevation(dp(4));
    gear.setContentDescription(getString(R.string.settings));
    gear.setOnClickListener(v -> openSettings());
    press(gear);
    FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(dp(54), dp(54), Gravity.BOTTOM | Gravity.END);
    gl.setMargins(0, 0, dp(18), dp(170));
    box.addView(gear, gl);
    gearBtn = gear;

    // bottom bar: current street + "Where to?"
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(22), 0, dp(10), 0);
    GradientDrawable bb = new GradientDrawable();
    bb.setColor(0xF7FFFFFF);
    bb.setCornerRadius(dp(22));
    bar.setBackground(bb);
    bar.setElevation(dp(6));
    LinearLayout st = new LinearLayout(this);
    st.setOrientation(LinearLayout.VERTICAL);
    TextView sl = text(11, 0xFF64748B, "sans-serif-medium");
    sl.setLetterSpacing(0.12f);
    sl.setText(R.string.you_are_on);
    streetLabel = sl;
    st.addView(sl);
    street = text(19, 0xFF0F172A, "sans-serif-medium");
    street.setSingleLine(true);
    street.setEllipsize(TextUtils.TruncateAt.END);
    street.setText(R.string.searching_gps);
    st.addView(street);
    // long-press: import an offline street file (streets.bin, see tools/build-streets.py)
    st.setOnLongClickListener(v -> { pickStreets(); return true; });
    bar.addView(st, new LinearLayout.LayoutParams(0, -2, 1));
    TextView go = text(17, Color.WHITE, "sans-serif-medium");
    go.setText(R.string.where_to);
    go.setGravity(Gravity.CENTER);
    go.setPadding(dp(22), 0, dp(22), 0);
    GradientDrawable gb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF4D6D, 0xFFE11D48});
    gb.setCornerRadius(dp(16));
    go.setBackground(gb);
    go.setOnClickListener(v -> { if (!open("com.waze", null)) open("com.google.android.apps.maps", null); });
    press(go);
    bar.addView(go, new LinearLayout.LayoutParams(-2, dp(52)));
    FrameLayout.LayoutParams bl = new FrameLayout.LayoutParams(-1, dp(76), Gravity.BOTTOM);
    bl.setMargins(dp(14), 0, dp(14), dp(14));
    box.addView(bar, bl);
    streetBar = bar;
    return box;
  }

  View buildMedia() {
    FrameLayout c = new FrameLayout(this);
    clip(c, dp(26));
    c.setBackgroundColor(0xFF1F1235);
    mBg = new ImageView(this);
    mBg.setScaleType(ImageView.ScaleType.CENTER_CROP);
    c.addView(mBg, new FrameLayout.LayoutParams(-1, -1));
    View shade = new View(this);
    shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x55000000, 0x99000000, 0xDD000000}));
    c.addView(shade, new FrameLayout.LayoutParams(-1, -1));
    c.setOnClickListener(v -> openPlayer());

    LinearLayout col = new LinearLayout(this);
    col.setOrientation(LinearLayout.VERTICAL);
    col.setGravity(Gravity.CENTER_HORIZONTAL);
    col.setPadding(dp(26), dp(22), dp(26), dp(18));
    c.addView(col, new FrameLayout.LayoutParams(-1, -1));

    mArt = new ImageView(this);
    mArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
    GradientDrawable ab = new GradientDrawable();
    ab.setColor(0x33FFFFFF);
    ab.setCornerRadius(dp(18));
    mArt.setBackground(ab);
    clip(mArt, dp(18));
    mArt.setElevation(dp(10));
    col.addView(mArt, new LinearLayout.LayoutParams(dp(196), dp(196)));

    mApp = text(11, MUTED, "sans-serif-medium");
    mApp.setLetterSpacing(0.14f);
    mApp.setPadding(0, dp(16), 0, dp(2));
    mApp.setGravity(Gravity.CENTER);
    col.addView(mApp, new LinearLayout.LayoutParams(-1, -2));
    mTitle = text(24, TXT, "sans-serif-black");
    mTitle.setSingleLine(true);
    mTitle.setEllipsize(TextUtils.TruncateAt.END);
    mTitle.setGravity(Gravity.CENTER);
    col.addView(mTitle, new LinearLayout.LayoutParams(-1, -2));
    mArtist = text(16, MUTED, "sans-serif");
    mArtist.setSingleLine(true);
    mArtist.setEllipsize(TextUtils.TruncateAt.END);
    mArtist.setGravity(Gravity.CENTER);
    col.addView(mArtist, new LinearLayout.LayoutParams(-1, -2));

    mBarBox = new FrameLayout(this);
    GradientDrawable track = new GradientDrawable();
    track.setColor(0x33FFFFFF);
    track.setCornerRadius(dp(3));
    mBarBox.setBackground(track);
    mBar = new View(this);
    GradientDrawable fill = new GradientDrawable();
    fill.setColor(Color.WHITE);
    fill.setCornerRadius(dp(3));
    mBar.setBackground(fill);
    mBar.setPivotX(0);
    mBar.setScaleX(0);
    mBarBox.addView(mBar, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(5));
    bp.topMargin = dp(14);
    col.addView(mBarBox, bp);
    LinearLayout times = new LinearLayout(this);
    mPos = text(12, MUTED, "sans-serif-medium");
    mDur = text(12, MUTED, "sans-serif-medium");
    mDur.setGravity(Gravity.END);
    times.addView(mPos, new LinearLayout.LayoutParams(0, -2, 1));
    times.addView(mDur, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
    tp.topMargin = dp(4);
    tp.bottomMargin = dp(6);
    col.addView(times, tp);

    LinearLayout ctrls = new LinearLayout(this);
    ctrls.setGravity(Gravity.CENTER);
    bPrev = new Btn(this, Btn.PREV, false);
    bPlay = new Btn(this, Btn.PLAY, true);
    bNext = new Btn(this, Btn.NEXT, false);
    ctrls.addView(bPrev, new LinearLayout.LayoutParams(dp(58), dp(58)));
    LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(70), dp(70));
    pp.leftMargin = dp(22);
    pp.rightMargin = dp(22);
    ctrls.addView(bPlay, pp);
    ctrls.addView(bNext, new LinearLayout.LayoutParams(dp(58), dp(58)));
    col.addView(ctrls);

    bPrev.setOnClickListener(v -> { if (ctl != null) ctl.getTransportControls().skipToPrevious(); });
    bNext.setOnClickListener(v -> { if (ctl != null) ctl.getTransportControls().skipToNext(); });
    bPlay.setOnClickListener(v -> {
      if (ctl == null) { resumeLast(); return; }
      PlaybackState s = ctl.getPlaybackState();
      if (s != null && s.getState() == PlaybackState.STATE_PLAYING) ctl.getTransportControls().pause();
      else ctl.getTransportControls().play();
    });
    showMedia();
    return c;
  }

  // ---------- shortcuts (sidebar) ----------

  /** Parsed shortcuts {label, package, activity}; cached, reset when they change or apps are (un)installed. */
  String[][] favsCache;

  String[][] loadFavs() {
    if (favsCache != null) return favsCache;
    String raw = getSharedPreferences("home", MODE_PRIVATE).getString("favs2", null);
    String[][] out = new String[SLOTS][];
    if (raw == null) {
      // never customized: first installed candidate of each slot (not saved, so apps installed later show up)
      for (int i = 0; i < SLOTS; i++)
        for (String[] c : DEFAULTS[i])
          if (launchIntent(c[0], c[1]) != null) { out[i] = new String[]{pmLabel(c[0]), c[0], c[1]}; break; }
      return favsCache = out;
    }
    String[] lines = raw.split("\n", -1);
    for (int i = 0; i < SLOTS && i < lines.length; i++) {
      String[] f = lines[i].split("\\|", -1);
      if (f.length == 3 && !f[1].isEmpty()) out[i] = new String[]{f[0], f[1], f[2].isEmpty() ? null : f[2]};
    }
    return favsCache = out;
  }

  void saveFav(int slot, String[] fav) {
    String[][] favs = loadFavs();
    favs[slot] = fav;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < SLOTS; i++) {
      if (i > 0) sb.append('\n');
      String[] f = favs[i];
      if (f != null) sb.append(f[0].replace("|", " ")).append('|').append(f[1]).append('|').append(f[2] == null ? "" : f[2]);
    }
    getSharedPreferences("home", MODE_PRIVATE).edit().putString("favs2", sb.toString()).apply();
    favsCache = null;
    fillApps();
  }

  void fillApps() {
    if (dockApps == null) return;
    dockApps.removeAllViews();
    String[][] favs = loadFavs();
    for (int s = 0; s < SLOTS; s++) {
      final int slot = s;
      String[] f = favs[s];
      Intent i = f == null ? null : launchIntent(f[1], f[2]);
      Drawable ic = null;
      if (i != null) try { ic = getPackageManager().getActivityIcon(i); } catch (Exception e) { ic = null; }
      final String pkg = f == null ? null : f[1], act = f == null ? null : f[2];
      final boolean empty = ic == null;
      ImageView v = new ImageView(this);
      v.setImageDrawable(empty ? new Plus() : ic);
      v.setPadding(dp(8), dp(8), dp(8), dp(8));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(80), dp(80));
      lp.topMargin = dp(6);
      dockApps.addView(v, lp);
      if (empty) v.setAlpha(0.6f);
      v.setOnClickListener(x -> { if (empty) openDrawer(slot); else open(pkg, act); });
      v.setOnLongClickListener(x -> { openDrawer(slot); return true; });
      press(v);
    }
  }

  // ---------- app drawer ----------

  /** `slot` >= 0: picking the app for that shortcut; -1: just launching apps. */
  void openDrawer(int slot) {
    if (drawer != null) return;
    PackageManager pm = getPackageManager();
    List<ResolveInfo> all = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
    final List<ResolveInfo> apps = new ArrayList<>();
    for (ResolveInfo r : all) if (!r.activityInfo.packageName.equals(getPackageName())) apps.add(r);
    final List<String> names = new ArrayList<>();
    for (ResolveInfo r : apps) names.add(String.valueOf(r.loadLabel(pm)));
    final List<Integer> idx = new ArrayList<>();
    for (int i = 0; i < apps.size(); i++) idx.add(i);
    Collections.sort(idx, (a, b) -> names.get(a).compareToIgnoreCase(names.get(b)));

    drawer = new FrameLayout(this);
    drawer.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF1E1B4B, 0xFF3B0D4F, 0xFF4A0E2E}));
    drawer.setOnClickListener(v -> closeDrawer());
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(36), dp(24), dp(36), dp(16));
    drawer.addView(box, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout head = new LinearLayout(this);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView h = text(26, TXT, "sans-serif-light");
    h.setText(slot < 0 ? R.string.all_apps : R.string.choose_shortcut_app);
    head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
    if (slot >= 0 && loadFavs()[slot] != null) {
      TextView rm = text(17, 0xFFFDA4AF, "sans-serif-medium");
      rm.setText(R.string.remove_shortcut);
      rm.setPadding(dp(20), dp(10), dp(20), dp(10));
      rm.setOnClickListener(v -> { closeDrawer(); saveFav(slot, null); });
      head.addView(rm);
    }
    TextView close = text(17, TXT, "sans-serif-medium");
    close.setText(R.string.close);
    close.setPadding(dp(20), dp(10), dp(4), dp(10));
    close.setOnClickListener(v -> closeDrawer());
    head.addView(close);
    box.addView(head);

    GridView gv = new GridView(this);
    gv.setNumColumns(7);
    gv.setVerticalSpacing(dp(6));
    gv.setHorizontalSpacing(dp(6));
    gv.setSelector(new GradientDrawable());
    gv.setPadding(0, dp(16), 0, 0);
    gv.setClipToPadding(false);
    gv.setAdapter(new BaseAdapter() {
      @Override public int getCount() { return idx.size(); }
      @Override public Object getItem(int p) { return apps.get(idx.get(p)); }
      @Override public long getItemId(int p) { return p; }
      @Override public View getView(int p, View cv, ViewGroup parent) {
        LinearLayout t;
        if (cv == null) {
          t = new LinearLayout(Home.this);
          t.setOrientation(LinearLayout.VERTICAL);
          t.setGravity(Gravity.CENTER);
          t.setPadding(dp(4), dp(10), dp(4), dp(8));
          GradientDrawable pr = new GradientDrawable();
          pr.setColor(0x1FFFFFFF);
          pr.setCornerRadius(dp(20));
          StateListDrawable st = new StateListDrawable();
          st.addState(new int[]{android.R.attr.state_pressed}, pr);
          t.setBackground(st);
          t.addView(new ImageView(Home.this), new LinearLayout.LayoutParams(dp(64), dp(64)));
          TextView l = text(15, TXT, "sans-serif");
          l.setGravity(Gravity.CENTER);
          l.setSingleLine(true);
          l.setEllipsize(TextUtils.TruncateAt.END);
          l.setPadding(0, dp(8), 0, 0);
          t.addView(l, new LinearLayout.LayoutParams(-1, -2));
        } else t = (LinearLayout) cv;
        ResolveInfo r = apps.get(idx.get(p));
        ((ImageView) t.getChildAt(0)).setImageDrawable(r.loadIcon(getPackageManager()));
        ((TextView) t.getChildAt(1)).setText(names.get(idx.get(p)));
        return t;
      }
    });
    gv.setOnItemClickListener((a, v, p, id) -> {
      ResolveInfo r = apps.get(idx.get(p));
      closeDrawer();
      if (slot >= 0) { saveFav(slot, new String[]{names.get(idx.get(p)), r.activityInfo.packageName, r.activityInfo.name}); return; }
      start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(r.activityInfo.packageName, r.activityInfo.name));
    });
    box.addView(gv, new LinearLayout.LayoutParams(-1, 0, 1));
    drawer.setAlpha(0f);
    root.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
    drawer.animate().alpha(1f).setDuration(120).start();
  }

  void closeDrawer() {
    if (drawer == null) return;
    root.removeView(drawer);
    drawer = null;
  }

  // ---------- settings ----------

  AlertDialog settingsDlg;
  LinearLayout settingsBody;

  /** Settings dialog (gear button on the map). */
  void openSettings() {
    android.widget.ScrollView sv = new android.widget.ScrollView(this);
    settingsBody = new LinearLayout(this);
    settingsBody.setOrientation(LinearLayout.VERTICAL);
    settingsBody.setPadding(dp(24), dp(4), dp(24), dp(8));
    sv.addView(settingsBody);
    settingsDlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(R.string.settings)
        .setView(sv)
        .setPositiveButton(R.string.close, null)
        .create();
    settingsDlg.setOnDismissListener(d -> { settingsDlg = null; settingsBody = null; immersive(); });
    fillSettings();
    settingsDlg.show();
  }

  /** (Re)draws the settings content, so a change shows up right away. */
  void fillSettings() {
    LinearLayout col = settingsBody;
    if (col == null) return;
    col.removeAllViews();

    settingsHead(col, R.string.settings_units);
    LinearLayout units = new LinearLayout(this);
    TextView km = choice(getString(R.string.units_metric), !mph), mi = choice(getString(R.string.units_imperial), mph);
    km.setOnClickListener(v -> { setMph(false); fillSettings(); });
    mi.setOnClickListener(v -> { setMph(true); fillSettings(); });
    units.addView(km);
    LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
    ml.leftMargin = dp(10);
    units.addView(mi, ml);
    col.addView(units);

    settingsHead(col, R.string.settings_offline_maps);
    java.util.List<OfflineMaps.Area> areas = OfflineMaps.load(this);
    if (areas.isEmpty() && dlArea == null) {
      TextView none = text(14, MUTED, "sans-serif");
      none.setText(R.string.offline_none);
      col.addView(none);
    }
    for (final OfflineMaps.Area a : areas) {
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(0, dp(4), 0, dp(4));
      LinearLayout t = new LinearLayout(this);
      t.setOrientation(LinearLayout.VERTICAL);
      TextView n = text(16, TXT, "sans-serif-medium");
      n.setText(a.name);
      n.setSingleLine(true);
      n.setEllipsize(TextUtils.TruncateAt.END);
      t.addView(n);
      TextView d = text(13, MUTED, "sans-serif");
      d.setText(getString(R.string.offline_area_info, android.text.format.Formatter.formatShortFileSize(this, a.file(this).length()),
          detailName(a.maxZoom), a.source.equals(tiles().name()) ? "" : " · " + getString(R.string.offline_other_server)));
      t.addView(d);
      row.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
      TextView show = text(15, TXT, "sans-serif-medium");
      show.setText(R.string.offline_show);
      show.setPadding(dp(14), dp(10), dp(14), dp(10));
      show.setOnClickListener(v -> {
        if (settingsDlg != null) settingsDlg.dismiss();
        following = false;
        map.zoomToBoundingBox(new BoundingBox(a.north, a.east, a.south, a.west), true, dp(30));
      });
      row.addView(show);
      TextView rm = text(15, 0xFFFDA4AF, "sans-serif-medium");
      rm.setText(R.string.offline_delete);
      rm.setPadding(dp(14), dp(10), 0, dp(10));
      rm.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
          .setMessage(getString(R.string.offline_delete_confirm, a.name))
          .setPositiveButton(R.string.offline_delete, (x, w) -> { OfflineMaps.remove(this, a); reloadTiles(); fillSettings(); })
          .setNegativeButton(R.string.cancel, null).show());
      row.addView(rm);
      col.addView(row);
    }
    if (dlArea != null) {
      TextView p = text(14, TXT, "sans-serif");
      p.setText(dlChip.getText());
      col.addView(p);
    } else {
      TextView add = chip(getString(R.string.offline_choose_area));
      add.setOnClickListener(v -> { if (settingsDlg != null) settingsDlg.dismiss(); startAreaPicker(); });
      LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
      al.topMargin = dp(10);
      col.addView(add, al);
    }

    settingsHead(col, R.string.settings_server);
    TextView srv = text(14, TXT, "sans-serif");
    srv.setText(tileUrl().isEmpty() ? getString(R.string.server_osm) : tileUrl());
    col.addView(srv);
    TextView sh0 = text(13, MUTED, "sans-serif");
    sh0.setText(R.string.server_hint);
    sh0.setPadding(0, dp(4), 0, 0);
    col.addView(sh0);
    TextView chg = chip(getString(R.string.server_change));
    chg.setOnClickListener(v -> editServer());
    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
    cl.topMargin = dp(10);
    col.addView(chg, cl);

    settingsHead(col, R.string.settings_streets);
    TextView sh = text(14, MUTED, "sans-serif");
    sh.setText(R.string.settings_streets_hint);
    col.addView(sh);
    TextView imp = chip(getString(R.string.settings_streets_import));
    imp.setOnClickListener(v -> { if (settingsDlg != null) settingsDlg.dismiss(); pickStreets(); });
    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(-2, -2);
    il.topMargin = dp(10);
    col.addView(imp, il);
  }

  /** Tile server URL template (empty = OpenStreetMap). */
  void editServer() {
    final EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    e.setHint("https://tile.example.com/{z}/{x}/{y}.png");
    e.setText(tileUrl());
    FrameLayout f = new FrameLayout(this);
    f.setPadding(dp(24), dp(8), dp(24), 0);
    f.addView(e);
    AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(R.string.server_title)
        .setMessage(R.string.server_help)
        .setView(f)
        .setPositiveButton(R.string.save, (x, w) -> {
          String u = e.getText().toString().trim();
          if (!u.isEmpty() && (!u.matches("(?i)https?://.+") || !u.contains("{z}") || !u.contains("{x}") || !u.contains("{y}"))) {
            Toast.makeText(this, R.string.server_invalid, Toast.LENGTH_LONG).show();
            return;
          }
          getSharedPreferences("home", MODE_PRIVATE).edit().putString("tile_url", u).apply();
          reloadTiles();
          fillSettings();
        })
        .setNeutralButton(R.string.server_osm_short, (x, w) -> {
          getSharedPreferences("home", MODE_PRIVATE).edit().putString("tile_url", "").apply();
          reloadTiles();
          fillSettings();
        })
        .setNegativeButton(R.string.cancel, null)
        .create();
    d.show();
  }

  String detailName(int zoom) {
    int[] names = {R.string.detail_low, R.string.detail_medium, R.string.detail_high, R.string.detail_max};
    for (int i = 0; i < OfflineMaps.DETAIL.length; i++) if (OfflineMaps.DETAIL[i] == zoom) return getString(names[i]);
    return "z" + zoom;
  }

  // ---------- offline map: choose an area on the map, then download it ----------

  FrameLayout areaBox;
  AreaFrame areaFrame;
  TextView areaInfo, areaDetail, areaGo;
  EditText areaSearch;
  String areaName;
  boolean areaOk;
  MapListener areaListener;
  final Runnable areaUpdate = this::updateArea;

  /** Rectangle in the middle of the map that will be downloaded; outside it is dimmed. */
  class AreaFrame extends View {
    final Paint dim = new Paint(), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    final android.graphics.Rect r = new android.graphics.Rect();
    AreaFrame(Context c) {
      super(c);
      dim.setColor(0x8C000000);
      line.setColor(Color.WHITE);
      line.setStyle(Paint.Style.STROKE);
      line.setStrokeWidth(dp(3));
    }
    android.graphics.Rect hole() {
      r.set(dp(30), dp(88), getWidth() - dp(30), getHeight() - dp(150));
      return r;
    }
    @Override protected void onDraw(Canvas c) {
      android.graphics.Rect h = hole();
      int w = getWidth(), ht = getHeight();
      c.drawRect(0, 0, w, h.top, dim);
      c.drawRect(0, h.bottom, w, ht, dim);
      c.drawRect(0, h.top, h.left, h.bottom, dim);
      c.drawRect(h.right, h.top, w, h.bottom, dim);
      c.drawRoundRect(h.left, h.top, h.right, h.bottom, dp(6), dp(6), line);
    }
  }

  void startAreaPicker() {
    if (areaBox != null || dlArea != null) return;
    showPage(0);
    following = false;
    if (rotAnim != null) rotAnim.cancel();
    map.setMapOrientation(0);
    map.setMapCenterOffset(0, 0);
    if (map.getZoomLevelDouble() > 13 || map.getZoomLevelDouble() < 9) map.getController().setZoom(12.0);
    for (View v : new View[]{speedBox, recenterBtn, gearBtn, streetBar, dotsBar}) v.setVisibility(View.GONE);
    areaName = null;

    areaBox = new FrameLayout(this);
    areaFrame = new AreaFrame(this);
    areaBox.addView(areaFrame, new FrameLayout.LayoutParams(-1, -1));

    // search a place
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    areaSearch = new EditText(this);
    areaSearch.setSingleLine(true);
    areaSearch.setHint(R.string.area_search_hint);
    areaSearch.setTextColor(0xFF0F172A);
    areaSearch.setHintTextColor(0xFF64748B);
    areaSearch.setTextSize(17);
    areaSearch.setPadding(dp(18), 0, dp(18), 0);
    areaSearch.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    GradientDrawable sbg = new GradientDrawable();
    sbg.setColor(0xF7FFFFFF);
    sbg.setCornerRadius(dp(16));
    areaSearch.setBackground(sbg);
    areaSearch.setOnEditorActionListener((v, id, ev) -> { searchArea(); return true; });
    top.addView(areaSearch, new LinearLayout.LayoutParams(0, dp(52), 1));
    TextView find = chip(getString(R.string.area_search));
    find.setGravity(Gravity.CENTER);
    find.setOnClickListener(v -> searchArea());
    GradientDrawable fbg = new GradientDrawable();
    fbg.setColor(0xE6111827);
    fbg.setCornerRadius(dp(16));
    find.setBackground(fbg);
    LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, dp(52));
    fl.leftMargin = dp(10);
    top.addView(find, fl);
    FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    tl.setMargins(dp(14), dp(20), dp(14), 0);
    areaBox.addView(top, tl);

    // bottom card: size, detail, cancel, download
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setPadding(dp(20), dp(12), dp(12), dp(12));
    GradientDrawable cb = new GradientDrawable();
    cb.setColor(0xF7FFFFFF);
    cb.setCornerRadius(dp(22));
    card.setBackground(cb);
    card.setElevation(dp(6));
    card.setClickable(true);
    TextView head = text(11, 0xFF64748B, "sans-serif-medium");
    head.setLetterSpacing(0.12f);
    head.setText(R.string.area_title);
    card.addView(head);
    areaInfo = text(15, 0xFF0F172A, "sans-serif-medium");
    areaInfo.setMaxLines(2);
    card.addView(areaInfo);
    LinearLayout btns = new LinearLayout(this);
    btns.setGravity(Gravity.CENTER_VERTICAL);
    btns.setPadding(0, dp(8), 0, 0);
    areaDetail = lightChip("");
    areaDetail.setOnClickListener(v -> {
      int i = detailIndex();
      getSharedPreferences("home", MODE_PRIVATE).edit().putInt("offline_detail", (i + 1) % OfflineMaps.DETAIL.length).apply();
      updateArea();
    });
    btns.addView(areaDetail);
    btns.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    TextView cancel = lightChip(getString(R.string.cancel));
    cancel.setOnClickListener(v -> closeAreaPicker());
    btns.addView(cancel);
    areaGo = text(16, Color.WHITE, "sans-serif-medium");
    areaGo.setText(R.string.area_download);
    areaGo.setGravity(Gravity.CENTER);
    areaGo.setPadding(dp(20), 0, dp(20), 0);
    GradientDrawable gb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF4D6D, 0xFFE11D48});
    gb.setCornerRadius(dp(14));
    areaGo.setBackground(gb);
    areaGo.setOnClickListener(v -> { if (areaOk) askAreaName(); });
    press(areaGo);
    LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, dp(46));
    gl.leftMargin = dp(10);
    btns.addView(areaGo, gl);
    card.addView(btns);
    FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    cl.setMargins(dp(14), 0, dp(14), dp(14));
    areaBox.addView(card, cl);

    mapBox.addView(areaBox, new FrameLayout.LayoutParams(-1, -1));
    areaListener = new MapListener() {
      @Override public boolean onScroll(ScrollEvent e) { ui.removeCallbacks(areaUpdate); ui.postDelayed(areaUpdate, 150); return false; }
      @Override public boolean onZoom(ZoomEvent e) { ui.removeCallbacks(areaUpdate); ui.postDelayed(areaUpdate, 150); return false; }
    };
    map.addMapListener(areaListener);
    areaBox.post(this::updateArea);
  }

  TextView lightChip(String label) {
    TextView t = text(15, 0xFF0F172A, "sans-serif-medium");
    t.setText(label);
    t.setGravity(Gravity.CENTER_VERTICAL);
    t.setPadding(dp(16), dp(11), dp(16), dp(11));
    GradientDrawable g = new GradientDrawable();
    g.setColor(0xFFE2E8F0);
    g.setCornerRadius(dp(14));
    t.setBackground(g);
    press(t);
    return t;
  }

  void closeAreaPicker() {
    if (areaBox == null) return;
    hideKeyboard();
    ui.removeCallbacks(areaUpdate);
    map.removeMapListener(areaListener);
    mapBox.removeView(areaBox);
    areaBox = null;
    for (View v : new View[]{speedBox, recenterBtn, gearBtn, streetBar, dotsBar}) v.setVisibility(View.VISIBLE);
    map.setMapCenterOffset(0, mapOffsetY);
    following = true;
    if (lastLoc != null) { map.getController().setZoom(16.5); map.getController().setCenter(new GeoPoint(lastLoc)); }
    if (headingOk) rotateMap(-heading);
  }

  void hideKeyboard() {
    try {
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(mapBox.getWindowToken(), 0);
    } catch (Exception ignored) {}
  }

  int detailIndex() {
    int i = getSharedPreferences("home", MODE_PRIVATE).getInt("offline_detail", 1);
    return i < 0 || i >= OfflineMaps.DETAIL.length ? 1 : i;
  }

  /** Geographic box under the frame (the map is north-up while choosing). */
  BoundingBox areaBounds() {
    android.graphics.Rect h = areaFrame.hole();
    org.osmdroid.api.IGeoPoint a = map.getProjection().fromPixels(h.left, h.top), b = map.getProjection().fromPixels(h.right, h.bottom);
    return new BoundingBox(a.getLatitude(), b.getLongitude(), b.getLatitude(), a.getLongitude());
  }

  void updateArea() {
    if (areaBox == null || areaFrame.getWidth() == 0) return;
    int zmax = OfflineMaps.DETAIL[detailIndex()];
    areaDetail.setText(getString(R.string.area_detail, detailName(zmax)));
    BoundingBox bb = areaBounds();
    long total = OfflineMaps.tileCount(bb.getLatNorth(), bb.getLatSouth(), bb.getLonEast(), bb.getLonWest(), OfflineMaps.MIN_ZOOM, zmax);
    long detail = OfflineMaps.tileCount(bb.getLatNorth(), bb.getLatSouth(), bb.getLonEast(), bb.getLonWest(), OfflineMaps.OSM_DETAIL_ZOOM, zmax);
    boolean osm = tileUrl().isEmpty();
    areaOk = false;
    if (osm && detail > OfflineMaps.OSM_MAX_DETAIL_TILES) {
      areaInfo.setTextColor(0xFFE11D48);
      areaInfo.setText(R.string.area_osm_limit);
    } else if (total > OfflineMaps.MAX_TILES) {
      areaInfo.setTextColor(0xFFE11D48);
      areaInfo.setText(R.string.area_too_big);
    } else {
      areaOk = true;
      areaInfo.setTextColor(0xFF0F172A);
      areaInfo.setText(getString(R.string.area_size, String.format(Locale.getDefault(), "%,d", total),
          android.text.format.Formatter.formatShortFileSize(this, total * OfflineMaps.AVG_TILE_BYTES)));
    }
    areaGo.setAlpha(areaOk ? 1f : 0.4f);
  }

  /** Finds a place with Nominatim (one request per search) and fits it in the frame. */
  void searchArea() {
    final String q = areaSearch.getText().toString().trim();
    if (q.isEmpty()) return;
    hideKeyboard();
    new Thread(() -> {
      String name = null;
      BoundingBox bb = null;
      try {
        URL u = new URL("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language="
            + Locale.getDefault().toLanguageTag() + "&q=" + java.net.URLEncoder.encode(q, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        String body;
        try (InputStream in = c.getInputStream(); Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) { body = sc.hasNext() ? sc.next() : "[]"; }
        org.json.JSONArray a = new org.json.JSONArray(body);
        if (a.length() > 0) {
          JSONObject o = a.getJSONObject(0);
          org.json.JSONArray b = o.getJSONArray("boundingbox"); // south, north, west, east
          bb = new BoundingBox(b.getDouble(1), b.getDouble(3), b.getDouble(0), b.getDouble(2));
          name = o.optString("name", "");
          if (name.isEmpty()) name = o.optString("display_name", q).split(",")[0];
        }
      } catch (Exception ignored) {}
      final BoundingBox fb = bb;
      final String fn = name;
      ui.post(() -> {
        if (areaBox == null) return;
        if (fb == null) { Toast.makeText(this, R.string.area_not_found, Toast.LENGTH_LONG).show(); return; }
        areaName = fn;
        double pad = 0.02; // a little margin around the place
        double dl = (fb.getLatNorth() - fb.getLatSouth()) * pad, dn = (fb.getLonEast() - fb.getLonWest()) * pad;
        map.zoomToBoundingBox(new BoundingBox(fb.getLatNorth() + dl, fb.getLonEast() + dn, fb.getLatSouth() - dl, fb.getLonWest() - dn), true, dp(160));
      });
    }).start();
  }

  void askAreaName() {
    final EditText e = new EditText(this);
    e.setSingleLine(true);
    e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    e.setHint(R.string.area_name_hint);
    e.setText(areaName != null ? areaName : "");
    FrameLayout f = new FrameLayout(this);
    f.setPadding(dp(24), dp(8), dp(24), 0);
    f.addView(e);
    AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(R.string.area_name_title)
        .setView(f)
        .setPositiveButton(R.string.area_download, (x, w) -> {
          String n = e.getText().toString().trim();
          startDownload(areaBounds(), OfflineMaps.DETAIL[detailIndex()], n.isEmpty() ? getString(R.string.area_default_name, OfflineMaps.load(this).size() + 1) : n);
          closeAreaPicker();
        })
        .setNegativeButton(R.string.cancel, null)
        .create();
    d.setOnDismissListener(x -> immersive());
    d.show();
  }

  // ---------- offline map download (osmdroid CacheManager into a SQLite archive) ----------

  OfflineMaps.Area dlArea;
  CacheManager dlManager;
  SqliteArchiveTileWriter dlWriter;
  TextView dlChip;
  int dlTotal;

  void startDownload(BoundingBox bb, int zmax, String name) {
    if (dlArea != null) return;
    final OfflineMaps.Area a = new OfflineMaps.Area();
    a.id = Long.toString(System.currentTimeMillis(), 36);
    a.name = name;
    a.north = bb.getLatNorth();
    a.south = bb.getLatSouth();
    a.east = bb.getLonEast();
    a.west = bb.getLonWest();
    a.maxZoom = zmax;
    OnlineTileSourceBase src = tiles();
    a.source = src.name();
    try {
      dlWriter = new SqliteArchiveTileWriter(a.file(this).getPath());
      dlManager = new CacheManager(src, dlWriter, OfflineMaps.MIN_ZOOM, zmax);
    } catch (Exception e) {
      if (dlWriter != null) dlWriter.onDetach();
      dlWriter = null;
      a.file(this).delete();
      Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_LONG).show();
      return;
    }
    dlArea = a;
    dlTotal = 0;
    dlProgress(0);
    dlChip.setVisibility(View.VISIBLE);
    final CacheManager mgr = dlManager;
    dlManager.downloadAreaAsyncNoUI(getApplicationContext(), bb, OfflineMaps.MIN_ZOOM, zmax, new CacheManager.CacheManagerCallback() {
      @Override public void onTaskComplete() { if (dlManager == mgr) finishDownload(0); }
      @Override public void onTaskFailed(int errors) { if (dlManager == mgr) finishDownload(errors); }
      @Override public void updateProgress(int progress, int zoom, int zmin, int zmax2) { if (dlManager == mgr) dlProgress(progress); }
      @Override public void downloadStarted() {}
      @Override public void setPossibleTilesInArea(int total) { dlTotal = total; }
    });
  }

  void dlProgress(int done) {
    int pct = dlTotal > 0 ? Math.min(100, done * 100 / dlTotal) : 0;
    dlChip.setText(getString(R.string.offline_downloading, dlArea.name, pct));
  }

  void finishDownload(int errors) {
    OfflineMaps.Area a = dlArea;
    if (dlWriter != null) dlWriter.onDetach();
    dlWriter = null;
    dlManager = null;
    dlArea = null;
    dlChip.setVisibility(View.GONE);
    if (a == null) return;
    if (dlTotal > 0 && errors >= dlTotal) {
      a.file(this).delete();
      Toast.makeText(this, R.string.offline_failed, Toast.LENGTH_LONG).show();
    } else {
      a.tiles = dlTotal - errors;
      OfflineMaps.add(this, a);
      reloadTiles();
      Toast.makeText(this, errors > 0 ? getString(R.string.offline_partial, a.name, errors) : getString(R.string.offline_done, a.name), Toast.LENGTH_LONG).show();
    }
    fillSettings();
  }

  void confirmCancelDownload() {
    if (dlArea == null) return;
    new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setMessage(getString(R.string.offline_cancel_confirm, dlArea.name))
        .setPositiveButton(R.string.offline_stop, (x, w) -> cancelDownload())
        .setNegativeButton(R.string.offline_keep, null)
        .show();
  }

  void cancelDownload() {
    if (dlManager == null) return;
    final OfflineMaps.Area a = dlArea;
    final SqliteArchiveTileWriter w = dlWriter;
    dlManager.cancelAllJobs();
    dlManager = null;
    dlWriter = null;
    dlArea = null;
    dlChip.setVisibility(View.GONE);
    // the background task may still be writing its last tile: close and delete a moment later
    ui.postDelayed(() -> { try { w.onDetach(); } catch (Exception ignored) {} a.file(this).delete(); new File(a.file(this).getPath() + "-journal").delete(); }, 3000);
  }

  void settingsHead(LinearLayout col, int res) {
    TextView h = text(12, MUTED, "sans-serif-medium");
    h.setLetterSpacing(0.12f);
    h.setText(res);
    h.setPadding(0, dp(col.getChildCount() == 0 ? 8 : 22), 0, dp(8));
    col.addView(h);
  }

  /** Chip that shows whether it is the selected option. */
  TextView choice(String label, boolean on) {
    TextView t = chip(label);
    GradientDrawable g = new GradientDrawable();
    g.setColor(on ? ACCENT : 0x26FFFFFF);
    g.setCornerRadius(dp(14));
    t.setBackground(g);
    return t;
  }

  // ---------- clock and day/night map ----------

  void updateClock() {
    Date now = new Date();
    Locale loc = Locale.getDefault();
    // no AM/PM marker: it does not fit the sidebar
    setIfChanged(clock, new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", loc).format(now));
    // short weekday + day/month in the device's order, e.g. "SAT 11/14" / "SÁB 14/11"
    if (datePattern == null) datePattern = DateFormat.getBestDateTimePattern(loc, "EEEddMM");
    setIfChanged(dockTemp, new SimpleDateFormat(datePattern, loc).format(now).replace(".", "").replace(",", "").toUpperCase(loc));
    boolean n = isNight();
    if (n != dark) { dark = n; applyNight(); }
  }

  String datePattern;

  static boolean isNight() {
    int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    return h >= 18 || h < 6;
  }

  /**
   * Map tiles: OpenStreetMap (free, no key) unless the user set their own server in Settings.
   * At night the colors are inverted to darken it.
   */
  OnlineTileSourceBase tiles() {
    String url = tileUrl();
    if (url.isEmpty()) return new XYTileSource("osm", 3, 19, 256, ".png", new String[]{"https://tile.openstreetmap.org/"}, "© OpenStreetMap contributors");
    return new UrlTiles(url);
  }

  String tileUrl() { return getSharedPreferences("home", MODE_PRIVATE).getString("tile_url", ""); }

  /** Tile server given as a URL template: https://example.com/{z}/{x}/{y}.png ({s} becomes "a"). */
  static class UrlTiles extends OnlineTileSourceBase {
    final String tpl;
    UrlTiles(String tpl) {
      super("url-" + Integer.toHexString(tpl.hashCode()), 3, 19, 256, "", new String[]{tpl}, "© OpenStreetMap contributors");
      this.tpl = tpl;
    }
    @Override public String getTileURLString(long i) {
      return tpl.replace("{z}", String.valueOf(MapTileIndex.getZoom(i))).replace("{x}", String.valueOf(MapTileIndex.getX(i)))
          .replace("{y}", String.valueOf(MapTileIndex.getY(i))).replace("{s}", "a");
    }
  }

  /** New tile provider: picks up added/removed offline archives and a changed tile server. */
  void reloadTiles() {
    map.setTileProvider(new MapTileProviderBasic(getApplicationContext(), tiles()));
    applyNight();
  }

  void applyNight() {
    map.getOverlayManager().getTilesOverlay().setColorFilter(dark ? night() : null);
    map.invalidate();
  }

  /** Inverts the colors and tints them dark blue (GPS-style "night mode"). */
  static android.graphics.ColorMatrixColorFilter night() {
    android.graphics.ColorMatrix inv = new android.graphics.ColorMatrix(new float[]{
        -1, 0, 0, 0, 255, 0, -1, 0, 0, 255, 0, 0, -1, 0, 255, 0, 0, 0, 1, 0});
    android.graphics.ColorMatrix tint = new android.graphics.ColorMatrix(new float[]{
        0.75f, 0, 0, 0, 0, 0, 0.8f, 0, 0, 5, 0, 0, 0.95f, 0, 20, 0, 0, 0, 1, 0});
    android.graphics.ColorMatrix sat = new android.graphics.ColorMatrix();
    sat.setSaturation(0.35f);
    inv.postConcat(sat);
    inv.postConcat(tint);
    return new android.graphics.ColorMatrixColorFilter(inv);
  }

  // ---------- GPS ----------

  final LocationListener gps = new LocationListener() {
    @Override public void onLocationChanged(Location l) { onLocation(l); }
    @Override public void onStatusChanged(String p, int s, Bundle e) {}
    @Override public void onProviderEnabled(String p) { if (!hasFix) { streetLabel.setText(gpsLabel()); if (lastLoc == null) street.setText(R.string.searching_gps); } }
    @Override public void onProviderDisabled(String p) { hasFix = false; lastMps = -1; speedNum.setText("--"); streetLabel.setText(R.string.gps_off_label); }
  };

  /** true after the first real GPS fix (the saved position does not count). */
  boolean hasFix;
  int satsSeen, satsUsed;
  long lastSavedAt;

  /** Satellites while searching: shows whether the GPS is getting a signal (it won't in a garage). */
  final android.location.GnssStatus.Callback gnss = new android.location.GnssStatus.Callback() {
    @Override public void onSatelliteStatusChanged(android.location.GnssStatus st) {
      int seen = st.getSatelliteCount(), used = 0;
      for (int i = 0; i < seen; i++) if (st.usedInFix(i)) used++;
      satsSeen = seen;
      satsUsed = used;
      if (!hasFix) setIfChanged(streetLabel, gpsLabel());
    }
  };

  String gpsLabel() {
    String base = getString(lastLoc != null ? R.string.gps_last_position_label : R.string.gps_searching_label);
    return satsSeen > 0 ? base + " · " + getResources().getQuantityString(R.plurals.satellites, satsSeen, satsSeen) : base;
  }

  /** Saves the position (at most every 30 s): when the car starts without a signal, map, street and weather show up right away. */
  void saveLoc(Location l) {
    long now = SystemClock.elapsedRealtime();
    if (lastSavedAt != 0 && now - lastSavedAt < 30_000) return;
    lastSavedAt = now;
    if (tripLoaded) saveTrip();
    getSharedPreferences("home", MODE_PRIVATE).edit()
        .putString("loc", l.getLatitude() + "," + l.getLongitude() + "," + l.getTime()).apply();
  }

  Location savedLoc() {
    String raw = getSharedPreferences("home", MODE_PRIVATE).getString("loc", null);
    if (raw == null) return null;
    try {
      String[] f = raw.split(",");
      Location l = new Location("saved");
      l.setLatitude(Double.parseDouble(f[0]));
      l.setLongitude(Double.parseDouble(f[1]));
      l.setTime(Long.parseLong(f[2]));
      return l;
    } catch (Exception e) { return null; }
  }

  /** Shows the saved position without pretending it is current: speed stays "--" and it is not used for the heading. */
  void showSavedLoc(Location l) {
    lastLoc = l;
    me.set(l, Float.NaN);
    zoomInIfWorld();
    map.getController().setCenter(new GeoPoint(l));
    streetLabel.setText(gpsLabel());
    maybeWeather();
    maybeStreet();
  }

  Location prevLoc, mapLoc;
  float heading;
  boolean headingOk;
  android.animation.ValueAnimator rotAnim;

  void onLocation(Location l) {
    boolean first = !hasFix;
    hasFix = true;
    lastLoc = l;
    saveLoc(l);
    if (first) { streetLabel.setText(R.string.you_are_on); lastWeatherAt = 0; zoomInIfWorld(); }
    float dist = prevLoc == null ? 0 : prevLoc.distanceTo(l);
    float secs = prevLoc == null ? 0 : (l.getTime() - prevLoc.getTime()) / 1000f;
    float mps = l.hasSpeed() ? l.getSpeed() : (secs > 0 ? dist / secs : 0);
    int kmh = Math.round(mps * 3.6f);
    lastMps = mps;
    showSpeed();
    // heading: the GPS bearing while moving; otherwise computed from the displacement (some GPS chips don't send it)
    if (l.hasBearing() && mps > 1.5f) { heading = l.getBearing(); headingOk = true; }
    else if (prevLoc != null && dist >= 8) { heading = (prevLoc.bearingTo(l) + 360) % 360; headingOk = true; }
    // trip: real movement only (a stationary GPS "wanders" a few meters and reports fake speeds)
    if (prevLoc != null && dist >= 8 && kmh >= 8 && (!l.hasAccuracy() || l.getAccuracy() < 30)) addTrip(dist, secs, kmh);
    if (prevLoc == null || dist >= 8) prevLoc = l;
    // Stopped (or the GPS jittering a few meters): don't touch the map, it is the most expensive part
    float moved = mapLoc == null ? Float.MAX_VALUE : mapLoc.distanceTo(l);
    if (moved >= 4) {
      mapLoc = l;
      me.set(l, headingOk ? heading : Float.NaN);
      if (following) {
        GeoPoint gp = new GeoPoint(l);
        // Small steps (up to ~40 m per second) are just a few pixels: re-center directly, without animation
        if (moved > 60 && moved < 300) map.getController().animateTo(gp, null, 500L);
        else map.getController().setCenter(gp);
        if (headingOk) rotateMap(-heading);
      } else map.invalidate();
    }
    maybeWeather();
    maybeStreet();
  }

  /** First position after the initial world view: zoom in to street level. */
  void zoomInIfWorld() {
    if (map.getZoomLevelDouble() < 10) map.getController().setZoom(16.5);
  }

  /** Rotates the map smoothly the shortest way (car heading always up). */
  void rotateMap(float target) {
    float from = map.getMapOrientation();
    float diff = ((target - from) % 360 + 540) % 360 - 180;
    if (Math.abs(diff) < 6) return;
    if (rotAnim != null) rotAnim.cancel();
    rotAnim = android.animation.ValueAnimator.ofFloat(from, from + diff);
    rotAnim.setDuration(600);
    rotAnim.addUpdateListener(a -> map.setMapOrientation((Float) a.getAnimatedValue()));
    rotAnim.start();
  }

  void startGps() {
    try {
      lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, gps, Looper.getMainLooper());
      Location best = null;
      for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
        Location l = lm.getLastKnownLocation(p);
        if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
      }
      try { lm.registerGnssStatusCallback(gnss, ui); } catch (Exception ignored) {}
      // stale system fix (> 10 min) or none: use the saved position, labeled as "last known position"
      boolean fresh = best != null && System.currentTimeMillis() - best.getTime() < 10 * 60_000L;
      if (fresh) { onLocation(best); map.getController().setCenter(new GeoPoint(best)); }
      else if (!hasFix) {
        Location saved = savedLoc();
        if (best != null && (saved == null || best.getTime() > saved.getTime())) saved = best;
        if (saved != null) showSavedLoc(saved);
        else { streetLabel.setText(gpsLabel()); street.setText(lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? R.string.searching_gps : R.string.gps_off); maybeWeather(); }
      }
    } catch (SecurityException e) {
      street.setText(R.string.no_location_permission);
    } catch (Exception ignored) {}
  }

  // ---------- street name ----------

  Location streetOkLoc;
  boolean streetBusy;
  /** Replaced by a freshly loaded instance after importing a new file. */
  volatile Streets streets = new Streets();
  String offlineShown;

  /**
   * Street name: 1) the offline streets.bin (instant, no internet; optional), else 2) Android's Geocoder and then
   * 3) Nominatim (OpenStreetMap), both online. Online lookups run every 15 s or 60 m (Nominatim at most every
   * 10 s, see {@link #nominatim}). If the car moved more than 150 m since the last good name, says it is stale.
   */
  void maybeStreet() {
    if (lastLoc == null) return;
    final Streets st = streets;
    if (st.ready) {
      String name = st.street(lastLoc.getLatitude(), lastLoc.getLongitude(), headingOk && lastLoc.getSpeed() > 1.5f ? heading : Float.NaN);
      if (name != null) {
        String city = st.city(lastLoc.getLatitude(), lastLoc.getLongitude());
        String txt = city != null ? name + " · " + city : name;
        if (!txt.equals(offlineShown)) { street.setText(txt); offlineShown = txt; }
        setIfChanged(streetLabel, hasFix ? getString(R.string.you_are_on) : gpsLabel());
        streetOkLoc = lastLoc;
        return;
      }
    }
    offlineShown = null;
    if (hasFix && streetOkLoc != null && lastLoc.distanceTo(streetOkLoc) > 150) streetLabel.setText(R.string.offline_last_street);
    long now = SystemClock.elapsedRealtime();
    if (streetBusy || (geoLoc != null && now - lastGeoAt < 15_000 && lastLoc.distanceTo(geoLoc) < 60)) return;
    lastGeoAt = now;
    geoLoc = lastLoc;
    streetBusy = true;
    final Location at = lastLoc;
    final double lat = at.getLatitude(), lon = at.getLongitude();
    new Thread(() -> {
      String s = null;
      try {
        if (Geocoder.isPresent()) {
          List<Address> a = new Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1);
          if (a != null && !a.isEmpty()) {
            Address ad = a.get(0);
            String road = ad.getThoroughfare();
            String city = ad.getSubAdminArea() != null ? ad.getSubAdminArea() : ad.getLocality();
            s = road != null ? road + (city != null ? " · " + city : "") : city;
          }
        }
      } catch (Exception ignored) {}
      if (s == null) s = nominatim(lat, lon);
      final String r = s;
      ui.post(() -> {
        streetBusy = false;
        if (r == null) return;
        street.setText(r);
        streetLabel.setText(hasFix ? getString(R.string.you_are_on) : gpsLabel());
        streetOkLoc = at;
      });
    }).start();
  }

  /** Last Nominatim request (elapsedRealtime); failures push the next try further away. */
  static long nominatimAt, nominatimWait = 10_000;

  /**
   * Reverse geocoding with Nominatim. Follows its usage policy: identifying User-Agent, at most one request every
   * 10 s (well below the 1 req/s limit), backing off up to 5 min after errors.
   */
  static String nominatim(double lat, double lon) {
    long now = SystemClock.elapsedRealtime();
    if (nominatimAt != 0 && now - nominatimAt < nominatimWait) return null;
    nominatimAt = now;
    try {
      URL u = new URL(String.format(Locale.US, "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=%s&lat=%.6f&lon=%.6f",
          Locale.getDefault().toLanguageTag(), lat, lon));
      HttpURLConnection c = (HttpURLConnection) u.openConnection();
      c.setRequestProperty("User-Agent", USER_AGENT);
      c.setConnectTimeout(7000);
      c.setReadTimeout(7000);
      String body;
      try (InputStream in = c.getInputStream(); Scanner sc = new Scanner(in, "UTF-8").useDelimiter("\\A")) { body = sc.hasNext() ? sc.next() : ""; }
      nominatimWait = 10_000;
      JSONObject ad = new JSONObject(body).optJSONObject("address");
      if (ad == null) return null;
      String road = ad.optString("road", null);
      String city = ad.optString("city", ad.optString("town", ad.optString("village", ad.optString("municipality", null))));
      if (road == null && city == null) return null;
      return road != null ? road + (city != null ? " · " + city : "") : city;
    } catch (Exception e) {
      nominatimWait = Math.min(nominatimWait * 2, 5 * 60_000L);
      return null;
    }
  }

  // ---------- weather (Open-Meteo, no key) ----------

  void maybeWeather() {
    long now = SystemClock.elapsedRealtime();
    if (lastWeatherAt != 0 && now - lastWeatherAt < 30 * 60_000L) return;
    // no position at all yet: wait for one (onLocation/showSavedLoc call this again)
    if (lastLoc == null) return;
    lastWeatherAt = now;
    final double lat = lastLoc.getLatitude(), lon = lastLoc.getLongitude();
    new Thread(() -> {
      try {
        URL u = new URL(String.format(Locale.US,
            "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto", lat, lon));
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        String body;
        try (InputStream in = c.getInputStream(); Scanner s = new Scanner(in, "UTF-8").useDelimiter("\\A")) { body = s.hasNext() ? s.next() : ""; }
        JSONObject j = new JSONObject(body), cur = j.getJSONObject("current"), day = j.getJSONObject("daily");
        final int t = (int) Math.round(cur.getDouble("temperature_2m"));
        final int code = cur.getInt("weather_code");
        final int max = (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0));
        final int min = (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0));
        ui.post(() -> {
          wIc.kind = code == 0 ? Glyph.SUN : (code >= 51 ? Glyph.RAIN : Glyph.CLOUD);
          wIc.invalidateSelf();
          String extra = "   ↑" + max + "° ↓" + min + "°";
          android.text.SpannableString sp = new android.text.SpannableString(t + "°" + extra);
          int st = sp.length() - extra.length();
          sp.setSpan(new android.text.style.RelativeSizeSpan(0.75f), st, sp.length(), 0);
          sp.setSpan(new android.text.style.ForegroundColorSpan(MUTED), st, sp.length(), 0);
          wVal.setText(sp);
          wSub.setText(weatherText(code));
        });
      } catch (Exception e) {
        lastWeatherAt = SystemClock.elapsedRealtime() - 25 * 60_000L;
      }
    }).start();
  }

  /** WMO weather code -> short description. */
  String weatherText(int c) {
    int id;
    if (c == 0) id = R.string.weather_clear;
    else if (c <= 2) id = R.string.weather_partly_cloudy;
    else if (c == 3) id = R.string.weather_overcast;
    else if (c == 45 || c == 48) id = R.string.weather_fog;
    else if (c >= 95) id = R.string.weather_thunderstorm;
    else if (c >= 80) id = R.string.weather_showers;
    else if (c >= 61) id = R.string.weather_rain;
    else if (c >= 51) id = R.string.weather_drizzle;
    else id = R.string.weather_overcast;
    return getString(id);
  }

  // ---------- quick tiles: Wi-Fi, Bluetooth and weather ----------

  TextView wifiVal, wifiSub, btVal, btSub, wVal, wSub;
  Glyph wifiIc, btIc, wIc;
  GradientDrawable wifiBg, btBg;
  final java.util.Set<String> btNames = new java.util.LinkedHashSet<>();

  void buildStatus() {
    wifiIc = new Glyph(Glyph.WIFI);
    btIc = new Glyph(Glyph.BT);
    wIc = new Glyph(Glyph.CLOUD);
    wifiBg = circleBg(0xFF3B82F6);
    btBg = circleBg(0xFF6366F1);
    View w = statusTile(wifiBg, wifiIc, getString(R.string.wifi), 0);
    View b = statusTile(btBg, btIc, getString(R.string.bluetooth), 1);
    View t = statusTile(circleBg(0xFFF59E0B), wIc, "--°", 2);
    w.setOnClickListener(v -> { if (!start(new Intent("android.settings.panel.action.WIFI"))) start(new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)); });
    b.setOnClickListener(v -> { if (!open("com.jancar.bluetooth", "com.jancar.bluetooth.activity.MainActivity")) start(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)); });
    t.setOnClickListener(v -> { lastWeatherAt = 0; maybeWeather(); });
    wSub.setText(R.string.weather_searching);
  }

  GradientDrawable circleBg(int color) {
    GradientDrawable g = new GradientDrawable();
    g.setShape(GradientDrawable.OVAL);
    g.setColor(color);
    return g;
  }

  View statusTile(GradientDrawable bg, Glyph icon, String title, int idx) {
    LinearLayout q = new LinearLayout(this);
    q.setOrientation(LinearLayout.VERTICAL);
    q.setGravity(Gravity.CENTER);
    q.setPadding(dp(6), 0, dp(6), 0);
    ImageView iv = new ImageView(this);
    iv.setBackground(bg);
    iv.setImageDrawable(icon);
    iv.setPadding(dp(16), dp(16), dp(16), dp(16));
    iv.setElevation(dp(6));
    q.addView(iv, new LinearLayout.LayoutParams(dp(68), dp(68)));
    TextView v = text(16, TXT, "sans-serif-medium");
    v.setText(title);
    v.setSingleLine(true);
    v.setEllipsize(TextUtils.TruncateAt.END);
    v.setGravity(Gravity.CENTER);
    v.setPadding(0, dp(10), 0, 0);
    q.addView(v, new LinearLayout.LayoutParams(-1, -2));
    TextView sub = text(13, MUTED, "sans-serif");
    sub.setSingleLine(true);
    sub.setEllipsize(TextUtils.TruncateAt.END);
    sub.setGravity(Gravity.CENTER);
    q.addView(sub, new LinearLayout.LayoutParams(-1, -2));
    if (idx == 0) { wifiVal = v; wifiSub = sub; } else if (idx == 1) { btVal = v; btSub = sub; } else { wVal = v; wSub = sub; }
    press(q);
    quick.addView(q, new LinearLayout.LayoutParams(0, -1, 1));
    return q;
  }

  final BroadcastReceiver netBt = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) {
      android.bluetooth.BluetoothDevice d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE);
      if (d != null && android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())) btNames.add(btName(d));
      if (d != null && android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(i.getAction())) btNames.remove(btName(d));
      updateStatus();
    }
  };

  void startStatus() {
    IntentFilter f = new IntentFilter(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION);
    f.addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION);
    f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
    f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
    f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED);
    registerReceiver(netBt, f);
    // devices connected before the launcher started: ask each audio/phone profile
    android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
    if (ad != null && ad.isEnabled()) {
      for (int prof : new int[]{1, 2, 11, 16}) {
        try {
          ad.getProfileProxy(this, new android.bluetooth.BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int p, android.bluetooth.BluetoothProfile proxy) {
              for (android.bluetooth.BluetoothDevice d : proxy.getConnectedDevices()) btNames.add(btName(d));
              ad.closeProfileProxy(p, proxy);
              ui.post(Home.this::updateStatus);
            }
            @Override public void onServiceDisconnected(int p) {}
          }, prof);
        } catch (Exception ignored) {}
      }
    }
    updateStatus();
  }

  String btName(android.bluetooth.BluetoothDevice d) {
    try { String n = d.getName(); return n == null ? d.getAddress() : n; } catch (Exception e) { return getString(R.string.bt_device); }
  }

  void updateStatus() {
    if (wifiSub == null) return;
    android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
    boolean on = wm != null && wm.isWifiEnabled();
    String ssid = null;
    if (on) {
      try {
        android.net.wifi.WifiInfo wi = wm.getConnectionInfo();
        if (wi != null && wi.getNetworkId() != -1) {
          ssid = wi.getSSID();
          if (ssid != null) ssid = ssid.replace("\"", "");
          if (ssid == null || ssid.contains("unknown ssid")) ssid = getString(R.string.wifi_connected);
        }
      } catch (Exception ignored) {}
    }
    wifiSub.setText(!on ? getString(R.string.state_off) : ssid == null ? getString(R.string.wifi_no_network) : ssid);
    wifiIc.off = !on || ssid == null;
    wifiBg.setColor(wifiIc.off ? 0x40FFFFFF : 0xFF3B82F6);
    wifiIc.invalidateSelf();

    android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
    boolean bt = ad != null && ad.isEnabled();
    if (!bt) btNames.clear();
    String dev = btNames.isEmpty() ? null : btNames.iterator().next();
    btSub.setText(!bt ? getString(R.string.state_off) : dev == null ? getString(R.string.bt_none_connected) : dev);
    btIc.off = !bt || dev == null;
    btBg.setColor(!bt ? 0x40FFFFFF : dev == null ? 0x806366F1 : 0xFF6366F1);
    btIc.invalidateSelf();
  }

  /** Quick-tile icons, drawn in code. */
  static class Glyph extends Shape {
    static final int WIFI = 0, BT = 1, SUN = 2, CLOUD = 3, RAIN = 4;
    int kind;
    boolean off;
    Glyph(int k) { kind = k; }
    @Override public void draw(Canvas c) {
      float w = getBounds().width(), h = getBounds().height(), x = getBounds().left, y = getBounds().top;
      p.setColor(off ? 0xB3FFFFFF : Color.WHITE);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
      if (kind == WIFI) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.09f);
        float cx = x + w / 2, cy = y + h * 0.82f;
        for (int i = 1; i <= 3; i++) {
          float r = w * 0.2f * i;
          c.drawArc(cx - r, cy - r, cx + r, cy + r, 225, 90, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, w * 0.07f, p);
        if (off) { p.setStyle(Paint.Style.STROKE); c.drawLine(x + w * 0.15f, y + h * 0.15f, x + w * 0.85f, y + h * 0.85f, p); }
      } else if (kind == BT) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.08f);
        Path t = new Path();
        t.moveTo(x + w * 0.28f, y + h * 0.3f);
        t.lineTo(x + w * 0.7f, y + h * 0.68f);
        t.lineTo(x + w * 0.5f, y + h * 0.88f);
        t.lineTo(x + w * 0.5f, y + h * 0.12f);
        t.lineTo(x + w * 0.7f, y + h * 0.32f);
        t.lineTo(x + w * 0.28f, y + h * 0.7f);
        c.drawPath(t, p);
      } else if (kind == SUN) {
        p.setStyle(Paint.Style.FILL);
        float cx = x + w / 2, cy = y + h / 2;
        c.drawCircle(cx, cy, w * 0.2f, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w * 0.07f);
        for (int i = 0; i < 8; i++) {
          double a = Math.PI / 4 * i;
          c.drawLine(cx + (float) Math.cos(a) * w * 0.32f, cy + (float) Math.sin(a) * w * 0.32f, cx + (float) Math.cos(a) * w * 0.45f, cy + (float) Math.sin(a) * w * 0.45f, p);
        }
      } else {
        p.setStyle(Paint.Style.FILL);
        float by = kind == RAIN ? y + h * 0.55f : y + h * 0.66f;
        c.drawCircle(x + w * 0.36f, by - h * 0.06f, w * 0.17f, p);
        c.drawCircle(x + w * 0.58f, by - h * 0.14f, w * 0.22f, p);
        c.drawRoundRect(x + w * 0.14f, by - h * 0.08f, x + w * 0.86f, by + h * 0.12f, h * 0.1f, h * 0.1f, p);
        if (kind == RAIN) {
          p.setStyle(Paint.Style.STROKE);
          p.setStrokeWidth(w * 0.06f);
          for (int i = 0; i < 3; i++) {
            float lx = x + w * (0.32f + 0.18f * i);
            c.drawLine(lx, y + h * 0.75f, lx - w * 0.05f, y + h * 0.9f, p);
          }
        }
      }
    }
  }

  // ---------- now playing ----------

  final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pickSession;

  final MediaController.Callback ctlCb = new MediaController.Callback() {
    @Override public void onPlaybackStateChanged(PlaybackState s) { showMedia(); }
    @Override public void onMetadataChanged(MediaMetadata m) { showMedia(); }
    @Override public void onSessionDestroyed() { setCtl(null); }
  };

  /** Notification access not granted yet: the music card asks for it instead. */
  boolean noMediaAccess;

  void startMedia() {
    noMediaAccess = false;
    try {
      msm.addOnActiveSessionsChangedListener(sessions, listener, ui);
      pickSession(msm.getActiveSessions(listener));
    } catch (SecurityException e) {
      noMediaAccess = true;
      setCtl(null);
    }
    ui.post(progress);
    if (ctl == null) wakeLastPlayer();
  }

  /**
   * Car started and no music app is running: wakes up the last one through its MediaBrowserService (apps that
   * support playback resumption restore their queue and it shows on the card). If it was playing when the car was
   * turned off, sends "play" like the steering-wheel button does, so it continues where it stopped.
   */
  void wakeLastPlayer() {
    android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
    final String pkg = sp.getString("last_pkg", null);
    ComponentName svc = pkg == null ? null : browserService(pkg);
    if (svc == null) return;
    long off = System.currentTimeMillis() - sp.getLong("stopped_at", 0);
    final boolean autoplay = sp.getBoolean("last_playing", false) && (off > 60_000 || SystemClock.elapsedRealtime() < 5 * 60_000);
    if (browser != null) try { browser.disconnect(); } catch (Exception ignored) {}
    browser = new android.media.browse.MediaBrowser(this, svc, new android.media.browse.MediaBrowser.ConnectionCallback() {
      @Override public void onConnected() {
        try {
          MediaController c = new MediaController(Home.this, browser.getSessionToken());
          if (ctl == null) setCtl(c);
        } catch (Exception ignored) {}
        if (autoplay) ui.postDelayed(() -> {
          if (ctl != null && ctl.getPlaybackState() != null && ctl.getPlaybackState().getState() == PlaybackState.STATE_PLAYING) return;
          mediaButton(pkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
        }, 1500);
      }
      @Override public void onConnectionFailed() {}
    }, null);
    try { browser.connect(); } catch (Exception ignored) {}
  }

  /** Same path as the steering-wheel button: the app resumes its saved queue even with an empty player. */
  void mediaButton(String pkg, int code) {
    for (int action : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
      Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
          .putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(action, code));
      List<ResolveInfo> r = getPackageManager().queryBroadcastReceivers(i, 0);
      if (r != null && !r.isEmpty()) {
        i.setComponent(new ComponentName(pkg, r.get(0).activityInfo.name));
        sendBroadcast(i);
      } else if (ctl != null) {
        ctl.getTransportControls().play();
        return;
      }
    }
  }

  void stopMedia() {
    try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) {}
    if (ctl != null) ctl.unregisterCallback(ctlCb);
    ctl = null;
    ui.removeCallbacks(progress);
  }

  void pickSession(List<MediaController> list) {
    MediaController best = null;
    if (list != null) {
      for (MediaController c : list) {
        PlaybackState s = c.getPlaybackState();
        if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
      }
      if (best == null && !list.isEmpty()) best = list.get(0);
    }
    setCtl(best);
  }

  void setCtl(MediaController c) {
    if (ctl != null) ctl.unregisterCallback(ctlCb);
    ctl = c;
    if (ctl != null) ctl.registerCallback(ctlCb, ui);
    showMedia();
  }

  String shownKey;

  /** setText only when it changes (setText always requests a new layout). */
  static void setIfChanged(TextView t, CharSequence v) {
    if (!TextUtils.equals(t.getText(), v)) t.setText(v);
  }

  void showMedia() {
    if (mTitle == null) return;
    MediaMetadata m = ctl == null ? null : ctl.getMetadata();
    PlaybackState s = ctl == null ? null : ctl.getPlaybackState();
    boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
    if (ctl != null && s != null && s.getState() != PlaybackState.STATE_BUFFERING && s.getState() != PlaybackState.STATE_CONNECTING) {
      android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
      if (sp.getBoolean("last_playing", false) != playing) sp.edit().putBoolean("last_playing", playing).apply();
    }
    bPlay.setKind(playing ? Btn.PAUSE : Btn.PLAY);
    if (ctl == null || m == null) {
      setProgress(0);
      lastArt = null;
      shownKey = null;
      if (!noMediaAccess && showSaved()) return;
      mApp.setText(R.string.media_music);
      mTitle.setText(noMediaAccess ? R.string.media_need_access : R.string.media_nothing_playing);
      mArtist.setText(noMediaAccess ? R.string.media_tap_to_allow : R.string.media_tap_to_open);
      mArt.setImageDrawable(new Note());
      mArt.setPadding(dp(50), dp(50), dp(50), dp(50));
      mBg.setImageDrawable(null);
      return;
    }
    String app = appLabel(ctl.getPackageName());
    setIfChanged(mApp, getString(playing ? R.string.media_playing : R.string.media_paused, app.toUpperCase(Locale.getDefault())));
    CharSequence title = m.getText(MediaMetadata.METADATA_KEY_TITLE);
    if (TextUtils.isEmpty(title)) title = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
    CharSequence artist = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
    if (TextUtils.isEmpty(artist)) artist = m.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
    if (TextUtils.isEmpty(artist)) artist = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
    setIfChanged(mTitle, TextUtils.isEmpty(title) ? app : title);
    setIfChanged(mArtist, TextUtils.isEmpty(artist) ? "" : artist);
    Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
    if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
    if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
    art = trimBars(art);
    // The artwork is set on every update (cheap); blurring the background and saving the artwork only when it changes
    String key = ctl.getPackageName() + "|" + title + "|" + artist + "|" + (art == null ? 0 : art.getWidth() * 10000 + art.getHeight());
    boolean changed = !key.equals(shownKey);
    shownKey = key;
    if (art != null) {
      mArt.setPadding(0, 0, 0, 0);
      mArt.setImageBitmap(art);
      if (changed) {
        mBg.setImageBitmap(blur(art));
        saveLast(ctl.getPackageName(), title, artist, art);
      }
      lastArt = art;
      return;
    }
    if (!changed) return;
    // No artwork (still loading or offline): use the saved one if it is the same track
    Bitmap saved = TextUtils.equals(title, getSharedPreferences("home", MODE_PRIVATE).getString("last_title", null))
        ? android.graphics.BitmapFactory.decodeFile(new File(getFilesDir(), "last_art.jpg").getPath()) : null;
    if (saved != null) {
      mArt.setPadding(0, 0, 0, 0);
      mArt.setImageBitmap(saved);
      mBg.setImageBitmap(blur(saved));
    } else {
      mArt.setPadding(dp(40), dp(40), dp(40), dp(40));
      try { mArt.setImageDrawable(getPackageManager().getApplicationIcon(ctl.getPackageName())); }
      catch (Exception e) { mArt.setImageDrawable(new Note()); }
      mBg.setImageDrawable(null);
      saveLast(ctl.getPackageName(), title, artist, null);
    }
    lastArt = null;
  }

  // ---------- last track (shown at startup, before the player app is opened) ----------

  CharSequence savedTitle;

  void saveLast(String pkg, CharSequence title, CharSequence artist, Bitmap art) {
    savedTitle = title;
    getSharedPreferences("home", MODE_PRIVATE).edit()
        .putString("last_pkg", pkg)
        .putString("last_title", title == null ? "" : title.toString())
        .putString("last_artist", artist == null ? "" : artist.toString()).apply();
    final Bitmap a = art;
    new Thread(() -> {
      File f = new File(getFilesDir(), "last_art.jpg");
      if (a == null) { f.delete(); return; }
      try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
        Bitmap small = a.getWidth() > 400 ? Bitmap.createScaledBitmap(a, 400, 400 * a.getHeight() / a.getWidth(), true) : a;
        small.compress(Bitmap.CompressFormat.JPEG, 88, o);
      } catch (Exception ignored) {}
    }).start();
  }

  /** Shows the last saved track; false if there is none. */
  boolean showSaved() {
    android.content.SharedPreferences sp = getSharedPreferences("home", MODE_PRIVATE);
    String pkg = sp.getString("last_pkg", null), title = sp.getString("last_title", "");
    if (pkg == null || title.isEmpty() || launchIntent(pkg, null) == null) return false;
    mApp.setText(getString(R.string.media_last_played, appLabel(pkg).toUpperCase(Locale.getDefault())));
    mTitle.setText(title);
    mArtist.setText(sp.getString("last_artist", ""));
    Bitmap art = android.graphics.BitmapFactory.decodeFile(new File(getFilesDir(), "last_art.jpg").getPath());
    if (art != null) {
      mArt.setPadding(0, 0, 0, 0);
      mArt.setImageBitmap(art);
      mBg.setImageBitmap(blur(art));
    } else {
      mArt.setPadding(dp(40), dp(40), dp(40), dp(40));
      try { mArt.setImageDrawable(getPackageManager().getApplicationIcon(pkg)); } catch (Exception e) { mArt.setImageDrawable(new Note()); }
      mBg.setImageDrawable(null);
    }
    return true;
  }

  android.media.browse.MediaBrowser browser;

  /**
   * ▶ with nothing playing: connects to the last app's media service and asks it to play.
   * If it has not started within 6 s (the app can't resume by itself), opens the app.
   */
  void resumeLast() {
    String pkg = getSharedPreferences("home", MODE_PRIVATE).getString("last_pkg", null);
    ComponentName svc = pkg == null ? null : browserService(pkg);
    if (svc == null) { openPlayer(); return; }
    if (browser != null) try { browser.disconnect(); } catch (Exception ignored) {}
    final Runnable fallback = () -> {
      if (ctl == null || ctl.getPlaybackState() == null || ctl.getPlaybackState().getState() != PlaybackState.STATE_PLAYING) openPlayer();
    };
    browser = new android.media.browse.MediaBrowser(this, svc, new android.media.browse.MediaBrowser.ConnectionCallback() {
      @Override public void onConnected() {
        try {
          MediaController c = new MediaController(Home.this, browser.getSessionToken());
          setCtl(c);
          if (c.getMetadata() != null) c.getTransportControls().play();
          else mediaButton(pkg, android.view.KeyEvent.KEYCODE_MEDIA_PLAY);
        } catch (Exception ignored) {}
        ui.postDelayed(fallback, 6000);
      }
      @Override public void onConnectionFailed() { openPlayer(); }
    }, null);
    try { browser.connect(); } catch (Exception e) { openPlayer(); }
  }

  ComponentName browserService(String pkg) {
    Intent i = new Intent("android.media.browse.MediaBrowserService").setPackage(pkg);
    List<ResolveInfo> r = getPackageManager().queryIntentServices(i, 0);
    if (r == null || r.isEmpty()) return null;
    return new ComponentName(pkg, r.get(0).serviceInfo.name);
  }

  Bitmap trimSrc, trimOut;

  /** Video artwork (16:9 thumbnail inside a square) comes with black bars on top and bottom: crop them. */
  Bitmap trimBars(Bitmap b) {
    if (b == null) return null;
    if (b == trimSrc) return trimOut;
    trimSrc = b;
    trimOut = b;
    try {
      int w = b.getWidth(), h = b.getHeight(), top = 0, bot = h - 1;
      while (top < h / 3 && darkRow(b, top, w)) top++;
      while (bot > h * 2 / 3 && darkRow(b, bot, w)) bot--;
      if (top > h / 20 || h - 1 - bot > h / 20) trimOut = Bitmap.createBitmap(b, 0, top, w, bot - top + 1);
    } catch (Exception ignored) {}
    return trimOut;
  }

  static boolean darkRow(Bitmap b, int y, int w) {
    for (int i = 0; i < 16; i++) {
      int c = b.getPixel(w * (2 * i + 1) / 32, y);
      if (Color.red(c) + Color.green(c) + Color.blue(c) > 60) return false;
    }
    return true;
  }

  /** Cheap blur: shrink to a few pixels and let the ImageView scale it up with filtering. */
  static Bitmap blur(Bitmap b) {
    try {
      Bitmap small = Bitmap.createScaledBitmap(b, 12, 12, true);
      return Bitmap.createScaledBitmap(small, 96, 96, true);
    } catch (Exception e) { return null; }
  }

  final Runnable progress = new Runnable() {
    @Override public void run() {
      float f = 0;
      long shownPos = -1, shownDur = -1;
      if (ctl != null && ctl.getMetadata() != null && ctl.getPlaybackState() != null) {
        long dur = ctl.getMetadata().getLong(MediaMetadata.METADATA_KEY_DURATION);
        PlaybackState s = ctl.getPlaybackState();
        long pos = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0)
          pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        if (dur > 0) { f = Math.max(0, Math.min(1, pos / (float) dur)); shownPos = Math.min(pos, dur); shownDur = dur; }
      }
      setProgress(f);
      // elapsed / total time (hidden when there is no duration, e.g. live radio)
      if (mPos != null) { setIfChanged(mPos, shownDur > 0 ? mmss(shownPos) : ""); setIfChanged(mDur, shownDur > 0 ? mmss(shownDur) : ""); }
      boolean playing = ctl != null && ctl.getPlaybackState() != null && ctl.getPlaybackState().getState() == PlaybackState.STATE_PLAYING;
      ui.postDelayed(this, playing ? 1000 : 3000);
    }
  };

  static String mmss(long ms) {
    long t = Math.max(0, ms / 1000);
    return t >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) : String.format(Locale.US, "%d:%02d", t / 60, t % 60);
  }

  void setProgress(float f) {
    if (mBar == null) return;
    if (Math.abs(mBar.getScaleX() - f) > 0.001f) mBar.setScaleX(f);
  }

  void openPlayer() {
    if (noMediaAccess && start(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))) return;
    if (ctl != null) {
      if (ctl.getSessionActivity() != null) {
        try { ctl.getSessionActivity().send(); return; } catch (Exception ignored) {}
      }
      if (open(ctl.getPackageName(), null)) return;
    }
    // no session: the default music app, else the head unit's own player (Jancar) if there is one
    if (!start(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)))
      open("com.jancar.music", "com.jancar.music.activity.MainActivity");
  }

  // ---------- helpers ----------

  Intent launchIntent(String pkg, String act) {
    PackageManager pm = getPackageManager();
    try {
      if (!pm.getApplicationInfo(pkg, 0).enabled) return null;
    } catch (Exception e) { return null; }
    if (act != null && pm.resolveActivity(new Intent(Intent.ACTION_MAIN).setClassName(pkg, act), 0) == null) act = null;
    if (act != null) return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, act);
    return pm.getLaunchIntentForPackage(pkg);
  }

  boolean open(String pkg, String act) {
    Intent i = launchIntent(pkg, act);
    return i != null && start(i);
  }

  boolean start(Intent i) {
    try {
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
      startActivity(i);
      return true;
    } catch (Exception e) { return false; }
  }

  String appLabel(String pkg) {
    for (String[] f : loadFavs()) if (f != null && f[1].equals(pkg)) return f[0];
    return pmLabel(pkg);
  }

  String pmLabel(String pkg) {
    try {
      PackageManager pm = getPackageManager();
      return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
    } catch (Exception e) { return pkg; }
  }

  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  TextView text(float sp, int color, String family) {
    TextView t = new TextView(this);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setTypeface(Typeface.create(family, Typeface.NORMAL));
    return t;
  }

  LinearLayout glassBg(int radius) {
    LinearLayout c = new LinearLayout(this);
    c.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable d = new GradientDrawable();
    d.setColor(GLASS);
    d.setCornerRadius(radius);
    d.setStroke(1, LINE);
    c.setBackground(d);
    return c;
  }

  void clip(View v, int radius) {
    v.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View x, Outline o) { o.setRoundRect(0, 0, x.getWidth(), x.getHeight(), radius); }
    });
    v.setClipToOutline(true);
  }

  View glow(int color, int size) {
    View g = new View(this);
    GradientDrawable d = new GradientDrawable();
    d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
    d.setColors(new int[]{color, color & 0x00FFFFFF});
    d.setGradientRadius(dp(size / 2f));
    g.setBackground(d);
    return g;
  }

  FrameLayout.LayoutParams glowLp(int size, int gravity, int dx, int dy) {
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(size), dp(size), gravity);
    if ((gravity & Gravity.END) == Gravity.END) lp.rightMargin = -dp(dx); else lp.leftMargin = dp(dx);
    if ((gravity & Gravity.BOTTOM) == Gravity.BOTTOM) lp.bottomMargin = -dp(dy); else lp.topMargin = dp(dy);
    return lp;
  }

  /** Touch feedback: shrinks a little. */
  void press(View v) {
    v.setOnTouchListener((x, e) -> {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN: x.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).start(); break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_CANCEL: x.animate().scaleX(1f).scaleY(1f).setDuration(110).start(); break;
      }
      return false;
    });
  }

  // ---------- drawings (no bitmaps in the APK) ----------

  /** Current position on the map: blue arrow rotated to the heading. */
  class Me extends Overlay {
    GeoPoint at;
    float bearing;
    boolean hasBearing;
    final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Point pt = new Point();
    final Path arrow = new Path();
    Me() {
      halo.setColor(0x333B82F6);
      ring.setColor(Color.WHITE);
      ring.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
      fill.setColor(0xFF2563EB);
    }
    void set(Location l, float b) {
      at = new GeoPoint(l);
      if (!Float.isNaN(b)) { bearing = b; hasBearing = true; }
    }
    @Override public void draw(Canvas c, MapView mv, boolean shadow) {
      if (shadow || at == null) return;
      mv.getProjection().toPixels(at, pt);
      float r = dp(13);
      c.drawCircle(pt.x, pt.y, r * 2.6f, halo);
      c.drawCircle(pt.x, pt.y, r * 1.35f, ring);
      if (!hasBearing) { c.drawCircle(pt.x, pt.y, r, fill); return; }
      c.save();
      c.rotate(bearing, pt.x, pt.y);
      arrow.reset();
      arrow.moveTo(pt.x, pt.y - r * 1.05f);
      arrow.lineTo(pt.x + r * 0.8f, pt.y + r * 0.8f);
      arrow.lineTo(pt.x, pt.y + r * 0.35f);
      arrow.lineTo(pt.x - r * 0.8f, pt.y + r * 0.8f);
      arrow.close();
      c.drawPath(arrow, fill);
      c.restore();
    }
  }

  /** Round media button drawn in code. */
  static class Btn extends View {
    static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3;
    int kind;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    Btn(Context c, int kind, boolean filled) {
      super(c);
      this.kind = kind;
      p.setColor(filled ? 0xFF111827 : Color.WHITE);
      bg.setColor(filled ? Color.WHITE : 0x26FFFFFF);
      setClickable(true);
    }
    void setKind(int k) { if (k != kind) { kind = k; invalidate(); } }
    @Override protected void onDraw(Canvas c) {
      float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2;
      c.drawCircle(cx, cy, r, bg);
      float s = r * 0.4f;
      path.reset();
      if (kind == PLAY) {
        path.moveTo(cx - s * 0.6f, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx - s * 0.6f, cy + s); path.close();
        c.drawPath(path, p);
      } else if (kind == PAUSE) {
        c.drawRoundRect(cx - s * 0.8f, cy - s, cx - s * 0.2f, cy + s, 3, 3, p);
        c.drawRoundRect(cx + s * 0.2f, cy - s, cx + s * 0.8f, cy + s, 3, 3, p);
      } else {
        float d = kind == NEXT ? 1 : -1;
        path.moveTo(cx - d * s * 0.8f, cy - s * 0.8f); path.lineTo(cx + d * s * 0.4f, cy); path.lineTo(cx - d * s * 0.8f, cy + s * 0.8f); path.close();
        c.drawPath(path, p);
        float x = cx + d * s * 0.5f;
        c.drawRect(Math.min(x, x + d * s * 0.3f), cy - s * 0.8f, Math.max(x, x + d * s * 0.3f), cy + s * 0.8f, p);
      }
    }
    @Override public void setPressed(boolean pr) { super.setPressed(pr); setAlpha(pr ? 0.6f : 1f); }
  }

  static abstract class Shape extends Drawable {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    @Override public void setAlpha(int a) {}
    @Override public void setColorFilter(android.graphics.ColorFilter f) {}
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
  }

  /** 3×3 app drawer icon. */
  static class Dots extends Shape {
    @Override public void draw(Canvas c) {
      float w = getBounds().width(), h = getBounds().height();
      p.setColor(Color.WHITE);
      float r = w * 0.085f;
      for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++)
        c.drawCircle(getBounds().left + w * (0.18f + 0.32f * i), getBounds().top + h * (0.18f + 0.32f * j), r, p);
    }
  }

  /** "+" for an empty slot. */
  static class Plus extends Shape {
    @Override public void draw(Canvas c) {
      float w = getBounds().width(), h = getBounds().height(), x = getBounds().left, y = getBounds().top;
      p.setStyle(Paint.Style.STROKE);
      p.setColor(0xCCFFFFFF);
      p.setStrokeWidth(w * 0.07f);
      p.setStrokeCap(Paint.Cap.ROUND);
      c.drawLine(x + w * 0.5f, y + h * 0.28f, x + w * 0.5f, y + h * 0.72f, p);
      c.drawLine(x + w * 0.28f, y + h * 0.5f, x + w * 0.72f, y + h * 0.5f, p);
    }
  }

  /** Settings cog. */
  static class Gear extends Shape {
    @Override public void draw(Canvas c) {
      float w = getBounds().width(), x = getBounds().left + w / 2, y = getBounds().top + getBounds().height() / 2f;
      p.setStyle(Paint.Style.FILL);
      p.setColor(0xFF334155);
      for (int i = 0; i < 8; i++) {
        c.save();
        c.rotate(i * 45, x, y);
        c.drawRoundRect(x - w * 0.08f, y - w * 0.48f, x + w * 0.08f, y - w * 0.25f, w * 0.03f, w * 0.03f, p);
        c.restore();
      }
      c.drawCircle(x, y, w * 0.33f, p);
      p.setColor(0xFFFFFFFF);
      c.drawCircle(x, y, w * 0.13f, p);
    }
  }

  /** "Re-center on the car" crosshair. */
  static class Target extends Shape {
    @Override public void draw(Canvas c) {
      float w = getBounds().width(), x = getBounds().left + w / 2, y = getBounds().top + getBounds().height() / 2f;
      p.setColor(0xFF2563EB);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(w * 0.1f);
      c.drawCircle(x, y, w * 0.3f, p);
      p.setStyle(Paint.Style.FILL);
      c.drawCircle(x, y, w * 0.12f, p);
      p.setStyle(Paint.Style.STROKE);
      c.drawLine(x, y - w * 0.5f, x, y - w * 0.32f, p);
      c.drawLine(x, y + w * 0.32f, x, y + w * 0.5f, p);
      c.drawLine(x - w * 0.5f, y, x - w * 0.32f, y, p);
      c.drawLine(x + w * 0.32f, y, x + w * 0.5f, y, p);
    }
  }

  /** Music note for when there is no artwork. */
  static class Note extends Shape {
    @Override public void draw(Canvas c) {
      float w = getBounds().width(), h = getBounds().height(), x = getBounds().left, y = getBounds().top;
      p.setColor(0xCCFFFFFF);
      c.drawCircle(x + w * 0.36f, y + h * 0.7f, w * 0.14f, p);
      c.drawRect(x + w * 0.46f, y + h * 0.2f, x + w * 0.5f, y + h * 0.7f, p);
      Path t = new Path();
      t.moveTo(x + w * 0.46f, y + h * 0.2f); t.lineTo(x + w * 0.74f, y + h * 0.28f); t.lineTo(x + w * 0.74f, y + h * 0.4f); t.lineTo(x + w * 0.5f, y + h * 0.33f); t.close();
      c.drawPath(t, p);
    }
  }
}
