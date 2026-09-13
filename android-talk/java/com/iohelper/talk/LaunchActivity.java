package com.iohelper.talk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The entire app. Launched (by a tap, or - the actual point - a hardware
 * shortcut like the side button), it hands straight over to iohelper's own
 * invisible toggle and gets off the screen.
 *
 * WHY IT STARTS AN ACTIVITY RATHER THAN SENDING A BROADCAST. iohelper exposes
 * a TALK broadcast, and using it looked right - but a broadcast receiver is a
 * BACKGROUND context, so when it tried to bring iohelper up Android refused:
 * "Background activity launch blocked! goo.gle/android-bal". The shortcut did
 * nothing at all, silently. THIS activity, though, was started by the user's
 * own press, so it is foreground and is allowed to start another - and
 * iohelper's TalkActivity, living in the same process as the voice service,
 * can then just call it.
 *
 * NoDisplay theme means the window must never actually appear; finish() runs
 * unconditionally before that could happen.
 */
public class LaunchActivity extends Activity {

    private static final String TARGET_PACKAGE = "com.iohelper.card";
    private static final String TARGET_ACTIVITY = "com.iohelper.card.TalkActivity";

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        try {
            Intent i = new Intent();
            i.setClassName(TARGET_PACKAGE, TARGET_ACTIVITY);
            // A new task of its own: this app is about to disappear, and the
            // toggle must not be left parented to a dying task.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // Almost certainly "iohelper isn't installed" - the one thing
            // worth a word, since otherwise a side-button press does nothing
            // and looks identical to working.
            Toast.makeText(this, "Jarvis isn't installed", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
