/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicupdatesafe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_APK = 101;
    private static final int INK = Color.rgb(18, 30, 24);
    private static final int MUTED = Color.rgb(79, 97, 88);
    private static final int CARD = Color.rgb(255, 255, 255);
    private static final int PRIMARY = Color.rgb(21, 107, 73);
    private static final int SAFE = Color.rgb(20, 115, 68);
    private static final int CAUTION = Color.rgb(168, 86, 0);
    private static final int RISK = Color.rgb(179, 38, 30);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Uri currentApkUri;
    private Button selectButton;
    private TextView liveStatus;
    private LinearLayout resultCard;
    private TextView verdict;
    private TextView explanation;
    private LinearLayout rows;
    private FdroidLookup.Result fdroidResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(245, 247, 245));
        scroll.setFillViewport(true);

        LinearLayout root = column();
        root.setPadding(dp(20), dp(22), dp(20), dp(24));
        scroll.addView(root);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(12));

        ImageButton btnMenu = new ImageButton(this);
        btnMenu.setImageResource(android.R.drawable.ic_menu_info_details);
        btnMenu.setBackground(round(Color.rgb(235, 240, 237), Color.rgb(205, 215, 210), 12));
        btnMenu.setColorFilter(INK);
        btnMenu.setContentDescription(getString(R.string.menu_title));
        btnMenu.setOnClickListener(v -> EthicEcosystemMenu.show(this, false));
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView eyebrow = text(getString(R.string.eyebrow_analyze), 12, PRIMARY);
        eyebrow.setLetterSpacing(.12f);
        bold(eyebrow);
        LinearLayout.LayoutParams eyebrowParams = new LinearLayout.LayoutParams(0, -2, 1f);
        eyebrowParams.setMarginStart(dp(8));
        topBar.addView(eyebrow, eyebrowParams);
        root.addView(topBar);

        TextView title = text(getString(R.string.app_name), 29, INK);
        bold(title);
        title.setPadding(0, dp(7), 0, 0);
        root.addView(title);

        TextView subtitle = text(getString(R.string.subtitle_analyze), 17, MUTED);
        subtitle.setPadding(0, dp(6), 0, dp(22));
        root.addView(subtitle);

        LinearLayout privacy = card();
        privacy.setOrientation(LinearLayout.HORIZONTAL);
        privacy.setGravity(Gravity.CENTER_VERTICAL);
        privacy.setBackground(round(Color.rgb(232, 245, 239), Color.rgb(193, 224, 211), 20));
        privacy.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView shield = text("✓", 23, PRIMARY);
        shield.setGravity(Gravity.CENTER);
        privacy.addView(shield, new LinearLayout.LayoutParams(dp(32), dp(36)));
        TextView privacyText = text(getString(R.string.privacy_banner), 14, INK);
        privacyText.setPadding(dp(10), 0, 0, 0);
        privacyText.setLineSpacing(dp(2), 1f);
        privacy.addView(privacyText, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(privacy, fullMargins(0, 0, 0, 18));

        selectButton = new Button(this);
        selectButton.setText(R.string.btn_choose_apk);
        selectButton.setTextSize(16);
        selectButton.setTextColor(Color.WHITE);
        selectButton.setAllCaps(false);
        bold(selectButton);
        selectButton.setContentDescription(getString(R.string.btn_choose_apk_desc));
        selectButton.setBackground(round(PRIMARY, PRIMARY, 18));
        selectButton.setOnClickListener(v -> pickApk());
        root.addView(selectButton, fullMargins(0, 0, 0, 8));

        liveStatus = text(getString(R.string.status_select_apk), 13, MUTED);
        liveStatus.setGravity(Gravity.CENTER);
        liveStatus.setPadding(0, dp(5), 0, dp(18));
        root.addView(liveStatus);

        resultCard = card();
        resultCard.setVisibility(View.GONE);
        resultCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        verdict = text("", 21, INK);
        bold(verdict);
        resultCard.addView(verdict);
        explanation = text("", 15, MUTED);
        explanation.setLineSpacing(dp(3), 1f);
        explanation.setPadding(0, dp(8), 0, dp(12));
        resultCard.addView(explanation);
        View line = new View(this);
        line.setBackgroundColor(Color.rgb(224, 230, 226));
        resultCard.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        rows = column();
        rows.setPadding(0, dp(8), 0, 0);
        resultCard.addView(rows);
        root.addView(resultCard, fullMargins(0, 0, 0, 18));

        LinearLayout limits = card();
        limits.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView limitsTitle = text(getString(R.string.limits_title), 16, INK);
        bold(limitsTitle);
        limits.addView(limitsTitle);
        TextView limitsText = text(getString(R.string.limits_desc), 14, MUTED);
        limitsText.setLineSpacing(dp(3), 1f);
        limitsText.setPadding(0, dp(7), 0, 0);
        limits.addView(limitsText);
        root.addView(limits);
        setContentView(scroll);
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        currentApkUri = uri;
        selectButton.setEnabled(false);
        liveStatus.setText(R.string.status_analyzing);
        resultCard.setVisibility(View.GONE);
        executor.execute(() -> {
            try {
                File apk = new File(getCacheDir(), "selected.apk");
                try {
                    ApkAnalyzer.copyToFile(this, uri, apk);
                    ApkAnalyzer.Result result = ApkAnalyzer.analyze(this, apk);
                    runOnUiThread(() -> showResult(result));
                } finally {
                    if (apk.exists() && !apk.delete()) apk.deleteOnExit();
                }
            } catch (Exception error) {
                currentApkUri = null;
                runOnUiThread(() -> showError(error.getMessage()));
            }
        });
    }

    private void showResult(ApkAnalyzer.Result result) {
        fdroidResult = null;
        selectButton.setEnabled(true);
        liveStatus.setText(R.string.status_analysis_finished);
        verdict.setText(result.headline);
        verdict.setTextColor(colorFor(result.verdict));
        explanation.setText(result.explanation);
        rows.removeAllViews();
        addRow(getString(R.string.row_package), result.packageName == null ? getString(R.string.not_available) : result.packageName);
        addRow(getString(R.string.row_candidate_apk), result.candidateVersion);
        addRow(getString(R.string.row_installed), result.installedVersion);
        addRow(getString(R.string.row_signer), result.signer);
        addRow(getString(R.string.row_libraries), result.architecture);
        if (!result.addedDangerousPermissions.isEmpty()) {
            addRow(getString(R.string.row_added_permissions), join(result.addedDangerousPermissions));
        }
        if (!result.securityChanges.isEmpty()) {
            addRow(getString(R.string.row_security_impact), join(result.securityChanges));
        }
        addInstallAction(result);

        Button share = new Button(this);
        share.setText(R.string.btn_share_report);
        share.setTextColor(PRIMARY);
        share.setAllCaps(false);
        share.setTextSize(14);
        share.setContentDescription(getString(R.string.btn_share_report_desc));
        share.setBackground(round(Color.WHITE, Color.rgb(193, 224, 211), 16));
        share.setOnClickListener(v -> confirmShare(result));
        rows.addView(share, fullMargins(0, 8, 0, 0));

        if (result.packageName != null && result.candidateVersionCode >= 0) {
            Button lookup = new Button(this);
            lookup.setText(R.string.btn_fdroid_lookup);
            lookup.setTextColor(PRIMARY);
            lookup.setAllCaps(false);
            lookup.setTextSize(14);
            lookup.setContentDescription(getString(R.string.btn_fdroid_lookup_desc));
            lookup.setBackground(round(Color.WHITE, Color.rgb(193, 224, 211), 16));
            lookup.setOnClickListener(v -> confirmFdroidLookup(result, lookup));
            rows.addView(lookup, fullMargins(0, 6, 0, 0));
        }
        resultCard.setVisibility(View.VISIBLE);
    }

    private void addInstallAction(ApkAnalyzer.Result result) {
        if (currentApkUri == null || result.verdict == ApkAnalyzer.Verdict.INVALID) return;
        if (result.verdict == ApkAnalyzer.Verdict.REPLACE) {
            Button blocked = new Button(this);
            blocked.setText(R.string.btn_blocked_signature);
            blocked.setAllCaps(false);
            blocked.setTextSize(14);
            blocked.setEnabled(false);
            rows.addView(blocked, fullMargins(0, 8, 0, 0));
            return;
        }
        String label = result.verdict == ApkAnalyzer.Verdict.NEW_APP
                ? getString(R.string.btn_install_new)
                : getString(R.string.btn_open_installer);
        Button install = new Button(this);
        install.setText(label);
        install.setTextColor(Color.WHITE);
        install.setAllCaps(false);
        install.setTextSize(15);
        install.setContentDescription(getString(R.string.btn_install_desc));
        install.setBackground(round(PRIMARY, PRIMARY, 16));
        install.setOnClickListener(v -> confirmInstall(result));
        rows.addView(install, fullMargins(0, 8, 0, 0));
    }

    private void confirmInstall(ApkAnalyzer.Result result) {
        String warning;
        if (result.verdict == ApkAnalyzer.Verdict.NEW_APP) {
            warning = getString(R.string.warn_new_app);
        } else if (result.verdict == ApkAnalyzer.Verdict.CAUTION) {
            warning = getString(R.string.warn_caution);
        } else {
            warning = getString(R.string.warn_safe);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_installer_title)
                .setMessage(warning + "\n\n" + getString(R.string.dialog_installer_disclaimer))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.btn_continue, (dialog, which) -> openSystemInstaller())
                .show();
    }

    private void openSystemInstaller() {
        if (currentApkUri == null) {
            liveStatus.setText(R.string.status_selection_unavailable);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_sources_title)
                    .setMessage(R.string.dialog_sources_msg)
                    .setNegativeButton(R.string.btn_cancel, null)
                    .setPositiveButton(R.string.btn_open_settings, (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))))
                    .show();
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(currentApkUri, "application/vnd.android.package-archive");
        install.setClipData(ClipData.newRawUri(getString(R.string.clip_analyzed_apk), currentApkUri));
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(install);
            liveStatus.setText(R.string.status_installer_handoff);
        } catch (Exception error) {
            liveStatus.setText(getString(R.string.status_installer_failed, safeMessage(error)));
        }
    }

    private void showError(String message) {
        selectButton.setEnabled(true);
        liveStatus.setText(R.string.status_analysis_failed);
        verdict.setText(R.string.verdict_read_error);
        verdict.setTextColor(RISK);
        explanation.setText(message == null ? getString(R.string.explanation_read_error) : message);
        rows.removeAllViews();
        resultCard.setVisibility(View.VISIBLE);
    }

    private void confirmShare(ApkAnalyzer.Result result) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.share_dialog_title)
                .setMessage(R.string.share_dialog_msg)
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.share_dialog_choose, (dialog, which) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, report(result));
                    startActivity(Intent.createChooser(send, getString(R.string.share_chooser_title)));
                }).show();
    }

    private void confirmFdroidLookup(ApkAnalyzer.Result result, Button lookup) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.fdroid_dialog_title)
                .setMessage(getString(R.string.fdroid_dialog_msg, result.packageName, result.candidateVersionCode))
                .setNegativeButton(R.string.btn_cancel, null)
                .setPositiveButton(R.string.fdroid_dialog_query, (dialog, which) -> {
                    lookup.setEnabled(false);
                    selectButton.setEnabled(false);
                    liveStatus.setText(R.string.status_querying_fdroid);
                    executor.execute(() -> {
                        try {
                            FdroidLookup.Result online = FdroidLookup.query(this, result.packageName, result.candidateVersionCode);
                            runOnUiThread(() -> showFdroidResult(online, lookup));
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                selectButton.setEnabled(true);
                                lookup.setEnabled(true);
                                liveStatus.setText(getString(R.string.status_query_failed, safeMessage(error)));
                            });
                        }
                    });
                }).show();
    }

    private void showFdroidResult(FdroidLookup.Result online, Button lookup) {
        fdroidResult = online;
        selectButton.setEnabled(true);
        lookup.setEnabled(false);
        lookup.setText(R.string.btn_fdroid_done);
        liveStatus.setText(R.string.status_query_completed);
        addRow(getString(R.string.row_fdroid), online.versions);
        addRow(getString(R.string.row_public_desc), online.description);
        if (!online.antiFeatures.isEmpty()) {
            addRow(getString(R.string.row_antifeatures), join(online.antiFeatures));
        } else {
            addRow(getString(R.string.row_antifeatures), getString(R.string.antifeatures_none));
        }
        addRow(getString(R.string.row_verification), online.verification);
        addRow(getString(R.string.row_query_limit), online.note);
    }

    private String report(ApkAnalyzer.Result result) {
        return getString(R.string.report_title) + "\n\n"
                + getString(R.string.report_result_label) + ": " + result.headline + "\n" + result.explanation
                + "\n\n" + getString(R.string.row_package) + ": " + result.packageName
                + "\n" + getString(R.string.row_candidate_apk) + ": " + result.candidateVersion
                + "\n" + getString(R.string.row_installed) + ": " + result.installedVersion
                + "\n" + getString(R.string.row_signer) + ": " + result.signer
                + "\n" + getString(R.string.row_libraries) + ": " + result.architecture
                + (result.addedDangerousPermissions.isEmpty() ? "" : "\n" + getString(R.string.row_added_permissions) + ": " + join(result.addedDangerousPermissions))
                + (result.securityChanges.isEmpty() ? "" : "\n" + getString(R.string.row_security_impact) + ": " + join(result.securityChanges))
                + (fdroidResult == null ? "" : "\n\n" + getString(R.string.report_fdroid_data_label) + "\n" + fdroidResult.versions
                    + "\n" + getString(R.string.report_desc_label) + ": " + fdroidResult.description
                    + "\n" + getString(R.string.report_antifeatures_label) + ": " + (fdroidResult.antiFeatures.isEmpty() ? getString(R.string.report_none_declared) : join(fdroidResult.antiFeatures))
                    + "\n" + fdroidResult.verification + "\n" + fdroidResult.note)
                + "\n\n" + getString(R.string.report_disclaimer);
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null ? getString(R.string.err_network_format) : error.getMessage();
    }

    private void addRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));
        TextView key = text(label.toUpperCase(), 11, MUTED);
        key.setLetterSpacing(.08f);
        bold(key);
        row.addView(key);
        TextView detail = text(value, 14, INK);
        detail.setTextIsSelectable(true);
        detail.setPadding(0, dp(3), 0, 0);
        row.addView(detail);
        rows.addView(row);
    }

    private int colorFor(ApkAnalyzer.Verdict verdict) {
        switch (verdict) {
            case SAFE: return SAFE;
            case REPLACE:
            case INVALID: return RISK;
            default: return CAUTION;
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setBackground(round(CARD, Color.rgb(225, 231, 227), 22));
        return view;
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private void bold(TextView view) {
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    }

    private GradientDrawable round(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams fullMargins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String join(java.util.Set<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(value);
        }
        return builder.toString();
    }
}
