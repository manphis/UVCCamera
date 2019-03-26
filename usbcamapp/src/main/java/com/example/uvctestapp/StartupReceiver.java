package com.example.uvctestapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

public class StartupReceiver extends BroadcastReceiver {
    private static final String TAG = "camera_StartupReceiver";
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.i(TAG, "receive startup event");
            Intent i = new Intent(context, MainActivity.class);
//            Bundle b = i.getExtras();
//
//            b.putBoolean(MainActivity.KEY_AUTO_RECORD, true);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            i.putExtras(b);

            context.startActivity(i);
        }
    }
}
