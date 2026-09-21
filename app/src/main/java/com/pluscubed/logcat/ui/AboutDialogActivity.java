package com.pluscubed.logcat.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.fragment.app.DialogFragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pluscubed.logcat.R;
import com.pluscubed.logcat.helper.PackageHelper;
import com.pluscubed.logcat.util.ThemeWrapper;
import com.pluscubed.logcat.util.UtilLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

public class AboutDialogActivity extends BaseActivity {
    private static final String TAG = "AboutDialogActivity";

    private static UtilLogger log = new UtilLogger(AboutDialogActivity.class);


    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fix window background overlay in dialog activities
        getTheme().applyStyle(R.style.DialogOverlay, true);

        DialogFragment fragment = new AboutDialog();
        fragment.show(getSupportFragmentManager(), "aboutDialog");

    }

    public static class AboutDialog extends DialogFragment {

        @Override
        public void onDismiss(DialogInterface dialog) {
            super.onDismiss(dialog);
            getActivity().finish();
        }


        public void initializeWebView(WebView view) {
            // Match webview style with application theme
            Context context = view.getContext();
            String textColor = ThemeWrapper.isLightTheme(context) ? "#212121" : "#fff";
            String bgColor = ThemeWrapper.isLightTheme(context) ? "#fff" : "#212121";

            String text = loadTextFile(R.raw.about_body);
            String version = PackageHelper.getVersionName(getActivity());
            String changelog = loadTextFile(R.raw.changelog);

            String css = String.format(Locale.ENGLISH, loadTextFile(R.raw.about_css), bgColor, textColor);

            text = String.format(text, version, changelog, css);

            WebSettings settings = view.getSettings();
            settings.setDefaultTextEncodingName("utf-8");

            view.loadDataWithBaseURL(null, text, "text/html", "UTF-8", null);
        }

        private String loadTextFile(int resourceId) {

            InputStream is = getResources().openRawResource(resourceId);

            StringBuilder sb = new StringBuilder();

            try (BufferedReader buff = new BufferedReader(new InputStreamReader(is))) {
                while (buff.ready()) {
                    sb.append(buff.readLine()).append("\n");
                }
            } catch (IOException e) {
                log.e(e, "This should not happen");
            }

            return sb.toString();

        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            WebView view = new WebView(getActivity());
/*
            view.setWebViewClient(new AboutWebClient());*/
            initializeWebView(view);

            return new MaterialAlertDialogBuilder(getActivity())
                    .setView(view)
                    .setTitle(R.string.about_matlog)
                    .setIcon(R.mipmap.ic_launcher)
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
        }


        /*private void loadExternalUrl(String url) {
            Intent intent = new Intent();
            intent.setAction("android.intent.action.VIEW");
            intent.setData(Uri.parse(url));

            startActivity(intent);
        }*/

        /*private class AboutWebClient extends WebViewClient {

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, final String url) {
                log.d("shouldOverrideUrlLoading");

                // XXX hack to make the webview go to an external url if the hyperlink is
                // in my own HTML file - otherwise it says "Page not available" because I'm not calling
                // loadDataWithBaseURL.  But if I call loadDataWithBaseUrl using a fake URL, then
                // the links within the page itself don't work!!  Arggggh!!!

                if (url.startsWith("http") || url.startsWith("mailto") || url.startsWith("market")) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            loadExternalUrl(url);
                        }
                    });
                    return true;
                }
                return false;
            }
        }*/
    }
}
