package com.iohelper.talk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The entire app. Launched (by a tap, or - the actual point - a hardware
 * shortcut like the side button), it sends one broadcast to iohelper and gets
 * off the screen.
 *
 * Deliberately dumb: this knows nothing about TalkService, the microphone, or
 * whether a session is already running - it reuses the ONE broadcast action
 * iohelper's ShowReceiver already exposes for exactly this
 * (`com.iohelper.card.TALK`, exported, no permission required), which routes
 * to iohelper's own toggle logic. If that ever changes, only iohelper needs
 * updating - this app never does.
 *
 * NoDisplay theme means the window must never actually appear; finish() runs
 * unconditionally, in the same call, before that could happen.
 */
public class LaunchActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.iohelper.card";
    private static final String TARGET_RECEIVER = "com.iohelper.card.ShowReceiver";
    private static final String ACTION_TALK = "com.iohelper.card.TALK";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            Intent i = new Intent(ACTION_TALK);
            i.setClassName(TARGET_PACKAGE, TARGET_RECEIVER);
            sendBroadcast(i);
        } catch (Exception e) {
            // Almost certainly "iohelper isn't installed" - the one thing
            // worth a word, since otherwise a side-button press does nothing
            // and looks identical to working.
            Toast.makeText(this, "Jarvis isn't installed", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
