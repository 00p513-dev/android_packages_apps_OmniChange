package com.bytehamster.changelog;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.app.AlertDialog;
import android.text.Html;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import org.omnirom.omnichange.R;

import java.util.Map;

class Dialogs {
    
    public static void usingCacheAlert(final Activity activity, final String gerrit_url,
                                       final DialogInterface.OnClickListener retryPressedListener){
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder b = new AlertDialog.Builder(activity);
                b.setTitle(R.string.network_error);
                b.setMessage(R.string.using_cache);
                b.setCancelable(true);

                b.setNegativeButton(R.string.open_website, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(gerrit_url));
                        activity.startActivity(browserIntent);
                    }
                });
                b.setPositiveButton(R.string.ok, null);
                b.setNeutralButton(R.string.retry, retryPressedListener);
                b.show();
            }
        });
    }
}
