package org.droiddialogue.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.droiddialogue.core.NetUtil;
import org.droiddialogue.core.SatelliteCore;
import org.droiddialogue.wake.TemplateBuilder;

import java.util.List;
import java.util.Locale;

/** One screen: start/stop, status, wake word training and a few knobs. Built in code, no resources. */
public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private TextView sensLabel;
    private TextView gainLabel;
    private Button startStop;
    private EditText portEdit;
    private EditText nameEdit;
    private CheckBox wakeCheck;
    private CheckBox speakerCheck;
    private SeekBar sensBar;
    private SeekBar gainBar;
    private String ipText = "...";
    private String ipFirst = "";
    private long ipAt;

    private final Runnable ticker = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("wl", MODE_PRIVATE);
        Config.load(prefs);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(ticker);
        syncFromUi();
        Config.save(prefs);
        super.onPause();
    }

    // ------------------------------------------------------------------ UI construction

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll.addView(root);

        TextView title = text("Droid Dialogue Satellite", 20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        status = text("", 14);
        root.addView(status);

        startStop = button("Start", new View.OnClickListener() {
            public void onClick(View v) {
                onStartStop();
            }
        });
        root.addView(startStop);

        root.addView(label("Port"));
        portEdit = new EditText(this);
        portEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
        portEdit.setText(String.valueOf(Config.port));
        root.addView(portEdit);

        root.addView(label("Wake word (detected on this phone)"));
        nameEdit = new EditText(this);
        nameEdit.setSingleLine(true);
        nameEdit.setText(Config.wakeName);
        root.addView(nameEdit);

        wakeCheck = new CheckBox(this);
        wakeCheck.setText("Listen for the wake phrase");
        wakeCheck.setChecked(Config.wakeEnabled);
        wakeCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Config.wakeEnabled = checked;
            }
        });
        root.addView(wakeCheck);

        speakerCheck = new CheckBox(this);
        speakerCheck.setText("Also be a speaker (media player) in Home Assistant. Applies at the next Start");
        speakerCheck.setChecked(Config.speakerEnabled);
        speakerCheck.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                Config.speakerEnabled = checked;
            }
        });
        root.addView(speakerCheck);

        root.addView(button("Record sample", new View.OnClickListener() {
            public void onClick(View v) {
                onRecord();
            }
        }));
        root.addView(button("Clear samples", new View.OnClickListener() {
            public void onClick(View v) {
                Shared.clearRequested = true;
            }
        }));

        sensLabel = text("", 14);
        root.addView(sensLabel);
        sensBar = new SeekBar(this);
        sensBar.setMax(100);
        sensBar.setProgress(Config.sensitivity);
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                Config.sensitivity = progress;
                updateLabels();
            }

            public void onStartTrackingTouch(SeekBar bar) {
            }

            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        root.addView(sensBar);

        gainLabel = text("", 14);
        root.addView(gainLabel);
        gainBar = new SeekBar(this);
        gainBar.setMax(90);
        gainBar.setProgress(Config.gainTenths - 10);
        gainBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                Config.gainTenths = progress + 10;
                updateLabels();
            }

            public void onStartTrackingTouch(SeekBar bar) {
            }

            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        root.addView(gainBar);

        root.addView(button("Talk now (skip the wake word)", new View.OnClickListener() {
            public void onClick(View v) {
                onTalk();
            }
        }));

        TextView help = text(
                "How to set up\n"
                        + "1. Tap Start.\n"
                        + "2. In Home Assistant open Settings, Devices & services, add the Wyoming Protocol "
                        + "integration and enter the address and port shown above (it may also be found automatically).\n"
                        + "3. Tap Record sample three or more times and say your wake phrase the same way each time, "
                        + "in the room where the phone will stand.\n"
                        + "4. Watch the live score above while saying the phrase. It must drop below the threshold. "
                        + "Raise the sensitivity if it does not, lower it if it triggers by itself.\n"
                        + "Volume of the replies follows the media volume keys.", 13);
        help.setPadding(0, dp(18), 0, 0);
        root.addView(help);

        updateLabels();
        return scroll;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s, 14);
        t.setPadding(0, dp(14), 0, dp(2));
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setOnClickListener(l);
        return b;
    }

    private void updateLabels() {
        sensLabel.setText("Sensitivity: " + Config.sensitivity + " (higher triggers more easily)");
        gainLabel.setText(String.format(Locale.US, "Microphone gain: %.1fx", Config.gain()));
    }

    // ------------------------------------------------------------------ actions

    private void syncFromUi() {
        try {
            int p = Integer.parseInt(portEdit.getText().toString().trim());
            if (p >= 1 && p <= 65535) {
                Config.port = p;
            }
        } catch (NumberFormatException e) {
            // keep the old port
        }
        String n = nameEdit.getText().toString().trim();
        if (n.length() > 0) {
            Config.wakeName = n;
        }
    }

    private void onStartStop() {
        if (Shared.core != null) {
            stopService(new Intent(this, SatelliteService.class));
            return;
        }
        syncFromUi();
        Config.save(prefs);
        if (!Compat.ensurePermissions(this)) {
            toast("Allow the permissions, then tap Start again");
            return;
        }
        try {
            Compat.startSatelliteService(this, new Intent(this, SatelliteService.class));
        } catch (RuntimeException e) {
            toast("Could not start: " + e.getMessage());
        }
    }

    private void onRecord() {
        if (Shared.core == null) {
            toast("Tap Start first. The service owns the microphone");
            return;
        }
        Shared.enrollMsg = "Get ready...";
        Shared.enrollRequested = true;
    }

    private void onTalk() {
        SatelliteCore c = Shared.core;
        if (c == null) {
            toast("Tap Start first");
        } else if (c.onWakeDetected("manual")) {
            toast("Listening...");
        } else {
            toast("Home Assistant is not connected, or a request is already running");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ status display

    private void refresh() {
        SatelliteCore c = Shared.core;
        StringBuilder sb = new StringBuilder();
        sb.append("Satellite: ").append(c != null ? "running" : "stopped").append('\n');
        if (Shared.startError.length() > 0) {
            sb.append("Error: ").append(Shared.startError).append('\n');
        }
        if (c != null) {
            long now = System.currentTimeMillis();
            if (now - ipAt > 5000) {
                List<String> ips = NetUtil.localIPv4();
                ipText = ips.isEmpty() ? "(no network address)" : join(ips);
                ipFirst = ips.isEmpty() ? "" : ips.get(0);
                ipAt = now;
            }
            sb.append("Add to Home Assistant: ").append(ipText).append("  port ").append(Config.port).append('\n');
            sb.append("Home Assistant: ")
                    .append(c.status.connected ? "connected (" + c.status.peer + ")" : "waiting for connection")
                    .append('\n');
            sb.append("Pipeline: ").append(c.status.pipeline);
            if (c.status.lastEvent.length() > 0) {
                sb.append("   last event: ").append(c.status.lastEvent);
            }
            sb.append('\n');
            String mic = Shared.micError.length() > 0 ? Shared.micError
                    : (Shared.micInfo.length() > 0 ? "ok, " + Shared.micInfo : "starting");
            sb.append("Microphone: ").append(mic).append('\n');

            int speakerPort = Shared.speakerPort;
            if (Shared.speakerError.length() > 0) {
                sb.append("Speaker: ").append(Shared.speakerError).append('\n');
            }
            if (speakerPort > 0) {
                sb.append("Speaker (").append(Shared.speakerState).append("): in Home Assistant add the DLNA Digital Media "
                        + "Renderer integration. If it is not offered, use http://")
                        .append(ipFirst.length() > 0 ? ipFirst : "<address>").append(':').append(speakerPort)
                        .append("/description.xml\n");
            } else if ("starting".equals(Shared.speakerState)) {
                sb.append("Speaker: starting\n");
            }
        }

        int have = Shared.templateCount;
        sb.append("Wake word \"").append(Config.wakeName).append("\": ");
        if (have == 0) {
            sb.append("no samples yet, record at least ").append(TemplateBuilder.MIN_TEMPLATES);
        } else if (have < TemplateBuilder.MIN_TEMPLATES) {
            sb.append(have).append(" sample(s), record ").append(TemplateBuilder.MIN_TEMPLATES - have).append(" more");
        } else {
            double thr = Shared.threshold * Config.thresholdScale();
            sb.append(have).append(" samples, threshold ").append(String.format(Locale.US, "%.1f", thr));
            double live = Shared.liveScore;
            if (c != null && live >= 0 && live < 1e20) {
                sb.append(", live score ").append(String.format(Locale.US, "%.1f", live));
            } else if (c != null) {
                sb.append(", live score -");
            }
        }
        sb.append('\n');
        if (Shared.detections > 0) {
            sb.append("Detections: ").append(Shared.detections).append(", last ").append(Shared.lastDetect).append('\n');
        }
        if (Shared.enrollMsg.length() > 0) {
            sb.append(Shared.enrollMsg).append('\n');
        }
        status.setText(sb.toString());
        startStop.setText(c != null ? "Stop" : "Start");
    }

    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
