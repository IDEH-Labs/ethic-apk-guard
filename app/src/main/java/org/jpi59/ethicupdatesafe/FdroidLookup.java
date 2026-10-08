/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicupdatesafe;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;

/** Small, opt-in client for public F-Droid records. It never uploads an APK or its hash. */
final class FdroidLookup {
    static final class Result {
        final boolean published;
        final String versions;
        final String description;
        final Set<String> antiFeatures;
        final String verification;
        final String note;

        Result(boolean published, String versions, String description, Set<String> antiFeatures, String verification, String note) {
            this.published = published;
            this.versions = versions;
            this.description = description;
            this.antiFeatures = antiFeatures;
            this.verification = verification;
            this.note = note;
        }
    }

    private static final String FDROID_API = "https://f-droid.org/api/v1/packages/";
    private static final String METADATA_YAML = "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/";
    private static final String METADATA_FASTLANE = "https://gitlab.com/fdroid/fdroiddata/-/raw/master/metadata/";
    private static final String VERIFY = "https://verification.f-droid.org/";

    private FdroidLookup() { }

    static Result query(Context context, String packageName, long versionCode) throws Exception {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_.]+")) {
            throw new IllegalArgumentException(context.getString(R.string.fdroid_err_invalid_pkg));
        }
        String encoded = URLEncoder.encode(packageName, "UTF-8");
        Response api = get(FDROID_API + encoded);
        if (api.code == HttpURLConnection.HTTP_NOT_FOUND) {
            return new Result(
                    false,
                    context.getString(R.string.fdroid_not_found_versions),
                    context.getString(R.string.fdroid_not_found_desc),
                    new LinkedHashSet<>(),
                    context.getString(R.string.fdroid_not_found_verify),
                    context.getString(R.string.fdroid_not_found_note)
            );
        }
        if (api.code != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException(context.getString(R.string.fdroid_err_http, api.code));
        }

        JSONObject object = new JSONObject(api.body);
        int suggested = object.optInt("suggestedVersionCode", -1);
        JSONArray versions = object.optJSONArray("packages");
        boolean candidateListed = false;
        int count = versions == null ? 0 : versions.length();
        if (versions != null) {
            for (int i = 0; i < versions.length(); i++) {
                if (versions.optJSONObject(i).optLong("versionCode", -1) == versionCode) {
                    candidateListed = true;
                    break;
                }
            }
        }
        StringBuilder versionBuilder = new StringBuilder(context.getString(R.string.fdroid_versions_published, count));
        if (suggested >= 0) {
            versionBuilder.append(context.getString(R.string.fdroid_suggested_version, suggested));
        }
        versionBuilder.append(candidateListed
                ? context.getString(R.string.fdroid_candidate_in_catalog)
                : context.getString(R.string.fdroid_candidate_not_in_catalog));
        String versionText = versionBuilder.toString();

        Set<String> antiFeatures = new LinkedHashSet<>();
        String description = context.getString(R.string.fdroid_desc_unavailable);
        Response metadataResponse = get(METADATA_YAML + encoded + ".yml");
        if (metadataResponse.code == HttpURLConnection.HTTP_OK) {
            String metadata = metadataResponse.body;
            String summary = scalar(metadata, "Summary");
            String fullDescription = block(metadata, "Description");
            if (!fullDescription.isEmpty()) {
                description = fullDescription;
            } else if (!summary.isEmpty()) {
                description = summary;
            }
            antiFeatures.addAll(extractAntiFeatures(metadata));
        }

        // Fastlane fallback if metadata YAML didn't specify summary/description
        if (description.equals(context.getString(R.string.fdroid_desc_unavailable))) {
            Response summaryResp = get(METADATA_FASTLANE + encoded + "/en-US/summary.txt");
            if (summaryResp.code == HttpURLConnection.HTTP_OK && !summaryResp.body.trim().isEmpty() && !summaryResp.body.contains("<!DOCTYPE html>")) {
                description = summaryResp.body.trim();
            } else {
                Response descResp = get(METADATA_FASTLANE + encoded + "/en-US/description.txt");
                if (descResp.code == HttpURLConnection.HTTP_OK && !descResp.body.trim().isEmpty() && !descResp.body.contains("<!DOCTYPE html>")) {
                    description = descResp.body.trim();
                }
            }
        }

        Response verificationResponse = get(VERIFY + encoded + ".json");
        String verification = context.getString(R.string.fdroid_verify_no_record);
        if (verificationResponse.code == HttpURLConnection.HTTP_OK) {
            JSONObject verificationObject = new JSONObject(verificationResponse.body);
            JSONArray reports = verificationObject.optJSONArray("apkReports");
            boolean reportForVersion = false;
            if (reports != null) {
                for (int i = 0; i < reports.length(); i++) {
                    if (reports.optString(i).contains("_" + versionCode + ".apk.json")) {
                        reportForVersion = true;
                        break;
                    }
                }
            }
            if (reportForVersion) {
                verification = context.getString(R.string.fdroid_verify_version_found);
            } else if (reports != null && reports.length() > 0) {
                verification = context.getString(R.string.fdroid_verify_other_version);
            }
        }
        String note = context.getString(R.string.fdroid_note_default);
        return new Result(true, versionText, description, antiFeatures, verification, note);
    }

    private static Response get(String address) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json, text/plain, text/yaml");
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : read(stream);
        connection.disconnect();
        return new Response(code, body);
    }

    private static String read(InputStream stream) throws Exception {
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            for (int count; (count = reader.read(buffer)) != -1; ) {
                if (result.length() + count > 512_000) throw new IllegalStateException("Response is too large.");
                result.append(buffer, 0, count);
            }
        }
        return result.toString();
    }

    private static String scalar(String yaml, String key) {
        String prefix = key + ":";
        for (String line : yaml.split("\\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim().replace("\"", "");
        }
        return "";
    }

    private static String block(String yaml, String key) {
        String[] lines = yaml.split("\\n");
        String prefix = key + ":";
        boolean active = false;
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            if (!active) {
                if (line.startsWith(prefix)) active = true;
                continue;
            }
            if (!line.startsWith(" ") && !line.isEmpty()) break;
            String text = line.trim();
            if (text.startsWith("|")) continue;
            if (result.length() > 0) result.append(" ");
            result.append(text);
        }
        return result.toString();
    }

    static Set<String> extractAntiFeatures(String yaml) {
        Set<String> results = new LinkedHashSet<>();
        String[] lines = yaml.split("\\n");
        boolean inAf = false;
        int afIndent = 0;
        for (String line : lines) {
            String stripped = line.trim();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') indent++;

            if (stripped.toLowerCase().startsWith("antifeatures:")) {
                inAf = true;
                afIndent = indent;
                String value = stripped.substring("antifeatures:".length()).trim();
                if (!value.isEmpty()) {
                    for (String item : value.split(",")) {
                        String s = item.trim();
                        if (!s.isEmpty()) results.add(s);
                    }
                }
                continue;
            }

            if (inAf) {
                if (indent <= afIndent) {
                    inAf = false;
                } else {
                    if (stripped.startsWith("- ")) {
                        String s = stripped.substring(2).trim();
                        if (!s.isEmpty()) results.add(s);
                    } else if (stripped.endsWith(":") && !stripped.contains(" ")) {
                        String key = stripped.substring(0, stripped.length() - 1).trim();
                        if (!key.isEmpty() && Character.isUpperCase(key.charAt(0)) && !key.contains("-")) {
                            results.add(key);
                        }
                    }
                }
            }
        }
        return results;
    }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body; }
    }
}
