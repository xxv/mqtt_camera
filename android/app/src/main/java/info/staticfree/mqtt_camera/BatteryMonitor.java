package info.staticfree.mqtt_camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.support.annotation.NonNull;

public class BatteryMonitor {
    @NonNull
    private final Context context;
    @NonNull
    private final BatteryObserver observer;

    public BatteryMonitor(@NonNull Context context, @NonNull BatteryObserver observer) {
        this.context = context;
        this.observer = observer;
    }

    public void onResume() {
        Intent batteryStatus = context.registerReceiver(broadcastReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        if (batteryStatus != null) {
            onBatteryChanged(batteryStatus);
        }
    }

    public void onPause() {
        context.unregisterReceiver(broadcastReceiver);
    }

    private void onBatteryChanged(@NonNull Intent batteryStatus) {
        if (Intent.ACTION_BATTERY_CHANGED.equals(batteryStatus.getAction())) {
            notifyObserver(batteryStatus);
        }
    }

    private void notifyObserver(@NonNull Intent batteryStatus) {
        int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL;

        // How are we charging?
        int chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        int batteryPct = Math.round(100 *  (level / (float) scale));

        observer.onBatteryUpdate(isCharging, chargePlug, batteryPct);
    }

    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onBatteryChanged(intent);
        }
    };

    public interface BatteryObserver {
        void onBatteryUpdate(boolean isCharging, int chargePlug, int percent);
    }
}
