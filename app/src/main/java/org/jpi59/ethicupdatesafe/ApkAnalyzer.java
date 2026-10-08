/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethicupdatesafe;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Performs a read-only, deliberately conservative APK compatibility assessment. */
final class ApkAnalyzer {
    enum Verdict { SAFE, CAUTION, REPLACE, NEW_APP, INVALID }

    static final class Result {
        final Verdict verdict;
        final String headline;
        final String explanation;
        final String packageName;
        final String candidateVersion;
        final long candidateVersionCode;
        final String installedVersion;
        final String signer;
        final String architecture;
        final Set<String> addedDangerousPermissions;
        final Set<String> securityChanges;

        Result(Verdict verdict, String headline, String explanation, String packageName,
               String candidateVersion, long candidateVersionCode, String installedVersion, String signer,
               String architecture, Set<String> addedDangerousPermissions, Set<String> securityChanges) {
            this.verdict = verdict;
            this.headline = headline;
            this.explanation = explanation;
            this.packageName = packageName;
            this.candidateVersion = candidateVersion;
            this.candidateVersionCode = candidateVersionCode;
            this.installedVersion = installedVersion;
            this.signer = signer;
            this.architecture = architecture;
            this.addedDangerousPermissions = addedDangerousPermissions;
            this.securityChanges = securityChanges;
        }
    }

    private ApkAnalyzer() { }

    static Result analyze(Context context, File apk) throws IOException {
        PackageManager pm = context.getPackageManager();
        ArchiveFacts facts = inspectArchive(context, apk);
        if (!facts.hasDex) {
            if (facts.bundleFormat != null) {
                return result(Verdict.INVALID, context.getString(R.string.verdict_split_title, facts.bundleFormat),
                        context.getString(R.string.verdict_split_desc),
                        null, "—", -1, "—", "—", facts.architecture, Collections.emptySet());
            }
            return result(Verdict.INVALID, context.getString(R.string.verdict_no_dex_title),
                    context.getString(R.string.verdict_no_dex_desc),
                    null, "—", -1, "—", "—", facts.architecture, Collections.emptySet());
        }

        PackageInfo candidate = pm.getPackageArchiveInfo(apk.getAbsolutePath(), packageFlags());
        if (candidate == null || candidate.applicationInfo == null || candidate.packageName == null) {
            return result(Verdict.INVALID, context.getString(R.string.verdict_unreadable_title),
                    context.getString(R.string.verdict_unreadable_desc),
                    null, "—", -1, "—", "—", facts.architecture, Collections.emptySet());
        }
        candidate.applicationInfo.sourceDir = apk.getAbsolutePath();
        candidate.applicationInfo.publicSourceDir = apk.getAbsolutePath();
        String candidateVersion = version(context, candidate);
        long candidateCode = versionCode(candidate);
        int minSdk = candidate.applicationInfo.minSdkVersion;
        if (minSdk > Build.VERSION.SDK_INT) {
            return result(Verdict.CAUTION, context.getString(R.string.verdict_min_sdk_title),
                    context.getString(R.string.verdict_min_sdk_desc, minSdk, Build.VERSION.SDK_INT),
                    candidate.packageName, candidateVersion, candidateCode, "—", signerSummary(context, candidate), facts.architecture, Collections.emptySet());
        }
        if (!facts.abiCompatible) {
            return result(Verdict.CAUTION, context.getString(R.string.verdict_abi_mismatch_title),
                    context.getString(R.string.verdict_abi_mismatch_desc, facts.architecture),
                    candidate.packageName, candidateVersion, candidateCode, "—", signerSummary(context, candidate), facts.architecture, Collections.emptySet());
        }

        PackageInfo installed = installed(pm, candidate.packageName);
        if (installed == null) {
            return result(Verdict.NEW_APP, context.getString(R.string.verdict_new_app_title),
                    context.getString(R.string.verdict_new_app_desc, candidate.packageName),
                    candidate.packageName, candidateVersion, candidateCode, context.getString(R.string.not_installed), signerSummary(context, candidate), facts.architecture, Collections.emptySet());
        }

        Set<String> addedPermissions = addedDangerousPermissions(pm, installed, candidate);
        Set<String> securityChanges = securityChanges(context, installed, candidate);
        String installedVersion = version(context, installed);
        Set<String> installedSigners = currentSigners(installed);
        Set<String> candidateSigners = currentSigners(candidate);
        boolean sameCurrentSigner = installedSigners.equals(candidateSigners) && !installedSigners.isEmpty();
        boolean declaredRotation = signingHistory(candidate).containsAll(installedSigners) && !installedSigners.isEmpty();
        // A signature mismatch is more important than a version comparison: a newer APK with
        // a different signer is the exact case that can tempt a user to uninstall and lose data.
        if (!sameCurrentSigner && !declaredRotation) {
            return result(Verdict.REPLACE, context.getString(R.string.verdict_signature_mismatch_title),
                    permissionsSuffix(context, context.getString(R.string.verdict_signature_mismatch_desc), addedPermissions),
                    candidate.packageName, candidateVersion, candidateCode, installedVersion, signerSummary(context, candidate), facts.architecture, addedPermissions, securityChanges);
        }
        if (versionCode(candidate) <= versionCode(installed)) {
            return result(Verdict.CAUTION, context.getString(R.string.verdict_older_version_title),
                    context.getString(R.string.verdict_older_version_desc, versionCode(candidate), versionCode(installed)),
                    candidate.packageName, candidateVersion, candidateCode, installedVersion, signerSummary(context, candidate), facts.architecture, addedPermissions, securityChanges);
        }

        if (sameCurrentSigner) {
            return result(Verdict.SAFE, context.getString(R.string.verdict_compatible_title),
                    permissionsSuffix(context, context.getString(R.string.verdict_compatible_desc), addedPermissions),
                    candidate.packageName, candidateVersion, candidateCode, installedVersion, signerSummary(context, candidate), facts.architecture, addedPermissions, securityChanges);
        }
        if (declaredRotation) {
            return result(Verdict.CAUTION, context.getString(R.string.verdict_rotation_title),
                    permissionsSuffix(context, context.getString(R.string.verdict_rotation_desc), addedPermissions),
                    candidate.packageName, candidateVersion, candidateCode, installedVersion, signerSummary(context, candidate), facts.architecture, addedPermissions, securityChanges);
        }
        throw new AssertionError("All signing cases should be handled above.");
    }

    static void copyToFile(Context context, android.net.Uri uri, File destination) throws IOException {
        try (java.io.InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException(context.getString(R.string.err_open_selected_file));
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) != -1; ) output.write(buffer, 0, read);
        }
    }

    private static Result result(Verdict verdict, String headline, String explanation, String packageName,
                                 String candidateVersion, long candidateVersionCode, String installedVersion, String signer,
                                 String architecture, Set<String> permissions) {
        return result(verdict, headline, explanation, packageName, candidateVersion, candidateVersionCode, installedVersion, signer,
                architecture, permissions, Collections.emptySet());
    }

    private static Result result(Verdict verdict, String headline, String explanation, String packageName,
                                 String candidateVersion, long candidateVersionCode, String installedVersion, String signer,
                                 String architecture, Set<String> permissions, Set<String> securityChanges) {
        return new Result(verdict, headline, explanation, packageName, candidateVersion, candidateVersionCode, installedVersion,
                signer, architecture, permissions, securityChanges);
    }

    private static int packageFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                : PackageManager.GET_SIGNATURES | PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS;
    }

    private static PackageInfo installed(PackageManager pm, String packageName) {
        try { return pm.getPackageInfo(packageName, packageFlags()); }
        catch (PackageManager.NameNotFoundException ignored) { return null; }
    }

    static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }

    private static String version(Context context, PackageInfo info) {
        String name = info.versionName == null ? context.getString(R.string.version_unnamed) : info.versionName;
        return context.getString(R.string.version_format, name, versionCode(info));
    }

    private static Set<String> currentSigners(PackageInfo info) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) signatures = info.signingInfo.getApkContentsSigners();
        else signatures = info.signatures;
        return digests(signatures);
    }

    private static Set<String> signingHistory(PackageInfo info) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || info.signingInfo == null) return currentSigners(info);
        return digests(info.signingInfo.getSigningCertificateHistory());
    }

    private static Set<String> digests(Signature[] signatures) {
        Set<String> values = new LinkedHashSet<>();
        if (signatures == null) return values;
        for (Signature signature : signatures) values.add(sha256(signature.toByteArray()));
        return values;
    }

    private static String signerSummary(Context context, PackageInfo info) {
        Set<String> values = currentSigners(info);
        if (values.isEmpty()) return context.getString(R.string.not_available);
        String digest = values.iterator().next();
        return digest.substring(0, Math.min(16, digest.length())) + "…";
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte value : digest) text.append(String.format(Locale.US, "%02x", value));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static Set<String> addedDangerousPermissions(PackageManager pm, PackageInfo installed, PackageInfo candidate) {
        Set<String> oldPermissions = requested(installed);
        Set<String> added = new LinkedHashSet<>();
        for (String permission : requested(candidate)) {
            if (oldPermissions.contains(permission)) continue;
            try {
                PermissionInfo info = pm.getPermissionInfo(permission, 0);
                if ((info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS) added.add(permission);
            } catch (PackageManager.NameNotFoundException ignored) { /* custom permissions are not presented as Android-dangerous */ }
        }
        return added;
    }

    /** Reports only update-relevant changes that can be read from public package metadata. */
    private static Set<String> securityChanges(Context context, PackageInfo installed, PackageInfo candidate) {
        Set<String> changes = new LinkedHashSet<>();
        ApplicationInfo before = installed.applicationInfo;
        ApplicationInfo after = candidate.applicationInfo;
        if (after == null || before == null) return changes;
        if ((after.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) changes.add(context.getString(R.string.sec_change_debuggable));
        if ((after.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0 && (before.flags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) == 0) changes.add(context.getString(R.string.sec_change_cleartext));
        if ((after.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0 && (before.flags & ApplicationInfo.FLAG_ALLOW_BACKUP) == 0) changes.add(context.getString(R.string.sec_change_backup));
        if (after.targetSdkVersion < before.targetSdkVersion) changes.add(context.getString(R.string.sec_change_lowered_sdk, before.targetSdkVersion, after.targetSdkVersion));
        Set<String> newExported = exported(candidate);
        newExported.removeAll(exported(installed));
        for (String component : newExported) changes.add(context.getString(R.string.sec_change_exported, component));
        return changes;
    }

    private static Set<String> exported(PackageInfo info) {
        Set<String> names = new LinkedHashSet<>();
        addExported(names, info.activities);
        addExported(names, info.services);
        addExported(names, info.receivers);
        addExported(names, info.providers);
        return names;
    }

    private static void addExported(Set<String> names, ComponentInfo[] components) {
        if (components == null) return;
        for (ComponentInfo component : components) if (component.exported) names.add(component.name);
    }

    private static Set<String> requested(PackageInfo info) {
        Set<String> values = new LinkedHashSet<>();
        if (info.requestedPermissions != null) Collections.addAll(values, info.requestedPermissions);
        return values;
    }

    private static String permissionsSuffix(Context context, String base, Set<String> permissions) {
        if (permissions.isEmpty()) return base;
        return base + context.getString(R.string.permissions_suffix, join(permissions));
    }

    private static String join(Set<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) { if (result.length() > 0) result.append(", "); result.append(value); }
        return result.toString();
    }

    private static final class ArchiveFacts {
        boolean hasDex;
        boolean abiCompatible = true;
        String architecture;
        String bundleFormat;

        ArchiveFacts(String defaultArchitecture) {
            this.architecture = defaultArchitecture;
        }
    }

    private static ArchiveFacts inspectArchive(Context context, File file) throws IOException {
        ArchiveFacts facts = new ArchiveFacts(context.getString(R.string.no_native_libs));
        Set<String> abis = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(file)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.matches("classes(\\d+)?\\.dex")) facts.hasDex = true;
                if (name.equals("toc.pb")) facts.bundleFormat = "APKS";
                if (name.equals("manifest.json") && facts.bundleFormat == null) facts.bundleFormat = "XAPK";
                if (name.startsWith("lib/")) {
                    String[] parts = name.split("/");
                    if (parts.length >= 3) abis.add(parts[1]);
                }
            }
        }
        if (!abis.isEmpty()) {
            facts.architecture = join(abis);
            facts.abiCompatible = false;
            for (String deviceAbi : Build.SUPPORTED_ABIS) if (abis.contains(deviceAbi)) { facts.abiCompatible = true; break; }
        }
        return facts;
    }
}
