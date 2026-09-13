package com.iohelper.card;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * A second, headless front door: appears as its own icon ("Jarvis Talk") so
 * it can be bound directly to a hardware shortcut - the Galaxy's side button,
 * via Settings > Advanced features > Side button > Double press > Apps -
 * without Tasker or any other automation app in between.
 *
 * WHY A SEPARATE ACTIVITY. Samsung's side-button app picker (like any
 * launcher-app picker) can only fire a target's ordinary launch intent - no
 * extras. MainActivity's launch intent opens the settings screen; there is no
 * way to tell Samsung's picker "open MainActivity, but also toggle talk mode
 * for me." An activity of its own, with its own MAIN/LAUNCHER filter, IS
 * something Samsung's picker can target directly and unambiguously - and it
 * shows up in that picker automatically, the same way a second Activity with
 * its own icon creates a second app-drawer entry for one APK.
 *
 * WHY NO UI. This should feel like a hardware action, not an app launching:
 * toggle the session and get off the screen. NoDisplay theme requires
 * finish() before the window would ever be shown, which toggleOrHandoff()
 * always does, on every path.
 */
public class TalkActivity extends Activity {

    private static final int REQ_MIC = 11;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        toggleOrHandoff();
    }

    private void toggleOrHandoff() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            // A NoDisplay activity cannot itself show the system permission
            // dialog and stay alive to read its answer - Android requires a
            // visible window for that. So the FIRST press (before the
            // permission is granted, ever) hands off to the full app, which
            // already knows how to ask and then start talking (ShowReceiver's
            // TALK path does exactly this). Every press after that is granted
            // and never touches this fallback.
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("talk", true));
            finish();
            return;
        }
        if (TalkService.instance != null) {
            TalkService.stop(this);
            Toast.makeText(this, "Ending the GPT-Live session", Toast.LENGTH_SHORT).show();
        } else {
            TalkService.start(this);
            Toast.makeText(this, "Starting GPT-Live…", Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        // Never actually reached - see the comment above - but present so a
        // future change to that flow fails loudly instead of silently.
        super.onRequestPermissionsResult(code, perms, grants);
    }
}
