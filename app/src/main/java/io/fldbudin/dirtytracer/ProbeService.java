package io.fldbudin.dirtytracer;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;

public class ProbeService extends Service {
    static final String EXTRA_ZYGOTE_PID = "zygotePid";

    private int zygotePid = -1;

    private final IProbeService.Stub binder = new IProbeService.Stub() {
        @Override
        public synchronized String getResult() {
            return Probe.run(zygotePid);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null) {
            zygotePid = intent.getIntExtra(EXTRA_ZYGOTE_PID, -1);
        }
        // app_zygote children use 90000..98999; only the plain isolated fork
        // (99000..99999) keeps zygote's readproc gid 3009.
        int uid = Process.myUid() % 100000;
        if (Process.isIsolated() && uid >= 99000 && uid <= 99999) {
            return binder;
        }
        return null;
    }
}
