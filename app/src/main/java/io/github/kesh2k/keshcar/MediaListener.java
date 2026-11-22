package io.github.kesh2k.keshcar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;

/**
 * Notification listener: granting "notification access" is what lets the launcher read the active
 * media sessions (the "now playing" card).
 *
 * It also brings Kesh Car back to the front when the head unit powers on. On Autochips/Jancar units the
 * stock services force-stop third-party launchers on power-off and put the stock launcher on top; the
 * system re-binds this listener right after that, so it can bring Home back. On other units the
 * broadcast simply never arrives and this is a no-op.
 */
public class MediaListener extends NotificationListenerService {
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final BroadcastReceiver powerOn = new BroadcastReceiver() {
    @Override public void onReceive(Context c, Intent i) { home(200); home(2000); }
  };

  /** Puts Kesh Car on top (through the HOME intent, which is us when we are the default launcher). */
  private void home(long delay) {
    ui.postDelayed(() -> {
      try { startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
      catch (Exception ignored) {}
    }, delay);
  }

  @Override public void onCreate() {
    super.onCreate();
    registerReceiver(powerOn, new IntentFilter("autochips.intent.action.QB_POWERON"));
    // re-bound right after the stock force-stop on power-off: come back to the front right away
    home(500);
  }

  @Override public void onDestroy() {
    try { unregisterReceiver(powerOn); } catch (Exception ignored) {}
    super.onDestroy();
  }
}
