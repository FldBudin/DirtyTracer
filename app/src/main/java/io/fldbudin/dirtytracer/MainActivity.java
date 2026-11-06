package io.fldbudin.dirtytracer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String WAITING = "INFO: Waiting for isolated service...";
    private static final long PROBE_TIMEOUT_MS = 90000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView resultView;
    private int zygotePid = -1;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IProbeService probe = IProbeService.Stub.asInterface(binder);
            executor.execute(() -> {
                String report;
                try {
                    report = probe.getResult();
                } catch (Throwable t) {
                    report = "ERROR: " + Log.getStackTraceString(t);
                }
                String text = report;
                runOnUiThread(() -> {
                    resultView.setText(text);
                    unbind();
                });
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }

        @Override
        public void onNullBinding(ComponentName name) {
            resultView.setText("ERROR: isolated service refused binding (not plain isolated or fake environment)");
            unbind();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().setSubtitle(BuildConfig.VERSION_NAME);
        }
        zygotePid = parentPid();

        var layout = new RelativeLayout(this);
        if (Build.VERSION.SDK_INT >= 35) {
            layout.setOnApplyWindowInsetsListener((v, insets) -> {
                var systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(0, systemBars.top, 0, 0);
                return insets;
            });
        }

        var info = new TextView(this);
        info.setId(View.generateViewId());
        info.setTextIsSelectable(true);
        info.setTextSize(20);
        info.setText(Build.FINGERPRINT + "\n" + Os.uname().release);
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        var params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        params.addRule(RelativeLayout.CENTER_HORIZONTAL);
        params.setMargins(margin, margin, margin, margin);
        layout.addView(info, params);

        var scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        resultView = new TextView(this);
        resultView.setTextIsSelectable(true);
        resultView.setTextSize(20);
        resultView.setPadding(margin, margin, margin, margin);
        resultView.setText(WAITING);
        scroll.addView(resultView);
        params = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT);
        params.addRule(RelativeLayout.BELOW, info.getId());
        layout.addView(scroll, params);
        setContentView(layout);

        try {
            var intent = new Intent(this, ProbeService.class)
                    .putExtra(ProbeService.EXTRA_ZYGOTE_PID, zygotePid);
            if (bindIsolatedService(intent, Context.BIND_AUTO_CREATE,
                    "dirtytracer_probe", getMainExecutor(), connection)) {
                bound = true;
                resultView.postDelayed(() -> {
                    if (resultView.getText().toString().equals(WAITING)) {
                        resultView.setText("WARNING: probe timed out");
                        unbind();
                    }
                }, PROBE_TIMEOUT_MS);
            } else {
                resultView.setText("ERROR: Failed to bind service, service disabled?");
            }
        } catch (SecurityException e) {
            resultView.setText(Log.getStackTraceString(e));
        }
    }

    @Override
    protected void onDestroy() {
        unbind();
        executor.shutdownNow();
        super.onDestroy();
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        try {
            unbindService(connection);
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static int parentPid() {
        try (var reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("PPid:")) {
                    return Integer.parseInt(line.substring("PPid:".length()).trim());
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
