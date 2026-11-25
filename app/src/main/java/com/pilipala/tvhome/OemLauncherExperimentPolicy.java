// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Standard-edition-only gating for the experimental OEM launcher disable flow.
 * Strictly excluded from automatic fallback and open only for explicitly verified (model, firmware)
 * records. Never claims black-screen immunity.
 */
final class OemLauncherExperimentPolicy {
    enum Eligibility {
        ALLOWED,
        BLOCKED_RESTRICTED,
        BLOCKED_KNOWN_UNSAFE,
        BLOCKED_UNVERIFIED,
        BLOCKED_INVALID_TARGET
    }

    static final class VerifiedDevice {
        final String model;
        final String firmware;
        final String oemPackage;
        final String note;

        VerifiedDevice(String model, String firmware, String oemPackage, String note) {
            this.model = model == null ? "" : model.trim();
            this.firmware = firmware == null ? "" : firmware.trim();
            this.oemPackage = oemPackage == null ? "" : oemPackage.trim();
            this.note = note == null ? "" : note.trim();
        }
    }

    static final class Decision {
        final Eligibility eligibility;
        final String reason;

        Decision(Eligibility eligibility, String reason) {
            this.eligibility = eligibility;
            this.reason = reason;
        }

        boolean allowed() {
            return eligibility == Eligibility.ALLOWED;
        }
    }

    /**
     * Verified safe allowlist of exact (model, firmware, oemPackage) entries.
     * Kept empty by default until a specific model + firmware passes full end-to-end verification
     * (including cold boot, foreground launch, and independent recovery verification).
     */
    static final List<VerifiedDevice> VERIFIED_ALLOWLIST = Collections.emptyList();

    private OemLauncherExperimentPolicy() { }

    static boolean validOemPackage(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")) return false;
        return !"com.pilipala.tvhome".equals(pkg)
                && !"android".equals(pkg)
                && !"com.android.settings".equals(pkg)
                && !"com.android.tv.settings".equals(pkg)
                && !"com.android.systemui".equals(pkg)
                && !"com.xiaomi.mitv.systemui".equals(pkg);
    }

    static boolean isFallbackOrBrokenHome(String resolvedComponent) {
        if (resolvedComponent == null || resolvedComponent.trim().isEmpty()) return true;
        String value = resolvedComponent.trim();
        return value.contains("FallbackHome")
                || value.contains("ResolverActivity")
                || value.contains("EmptyStubActivity")
                || value.startsWith("com.android.tv.settings/")
                || value.startsWith("com.android.settings/")
                || value.startsWith("android/");
    }

    static boolean isValidPostDisableHome(String resolvedComponent, String ownPackage) {
        if (ownPackage == null || ownPackage.isEmpty()) return false;
        if (isFallbackOrBrokenHome(resolvedComponent)) return false;
        return resolvedComponent.trim().startsWith(ownPackage + "/");
    }

    static Decision evaluate(boolean restricted, String model, String firmware, String oemPackage) {
        return evaluate(restricted, model, firmware, oemPackage, VERIFIED_ALLOWLIST);
    }

    static Decision evaluate(boolean restricted, String model, String firmware,
                             String oemPackage, List<VerifiedDevice> allowlist) {
        if (restricted) {
            return new Decision(Eligibility.BLOCKED_RESTRICTED,
                    "受限兼容版不含系统默认桌面声明，不支持停用原厂桌面实验。");
        }
        String normModel = model == null ? "" : model.trim();
        String normFirmware = firmware == null ? "" : firmware.trim();
        String normPkg = oemPackage == null ? "" : oemPackage.trim();
        String lowerModel = normModel.toLowerCase(Locale.ROOT);
        String lowerFirmware = normFirmware.toLowerCase(Locale.ROOT);

        if ("mitv-mftr0".equals(lowerModel)) {
            return new Decision(Eligibility.BLOCKED_KNOWN_UNSAFE,
                    "MiTV-MFTR0 已有停用 PatchWall 后持续黑屏的实机记录，已禁止执行停用原厂桌面。请改用「窗口回拉（兼容模式）」或「快速 Home」。");
        }
        if ("mitv-astp0".equals(lowerModel)
                && (lowerFirmware.contains("22.8.30.1385") || lowerFirmware.endsWith(".1385") || "1385".equals(lowerFirmware))) {
            return new Decision(Eligibility.BLOCKED_KNOWN_UNSAFE,
                    "MiTV-ASTP0 / 22.8.30.1385 实测停用原厂桌面后系统白名单仍拒绝第三方桌面并落回 FallbackHome，无法生效；已跳过停用原厂桌面，请使用「窗口回拉」或「快速 Home」。");
        }
        if (!validOemPackage(normPkg)) {
            return new Decision(Eligibility.BLOCKED_INVALID_TARGET,
                    "未识别到可安全操作的原厂桌面包名，已阻止执行停用操作。");
        }
        if (allowlist != null) {
            for (VerifiedDevice entry : allowlist) {
                if (entry.model.equalsIgnoreCase(normModel)
                        && entry.firmware.equalsIgnoreCase(normFirmware)
                        && entry.oemPackage.equals(normPkg)) {
                    return new Decision(Eligibility.ALLOWED,
                            entry.note.isEmpty() ? "该型号与固件在实机验证白名单内，需明确风险并确认后执行。" : entry.note);
                }
            }
        }
        return new Decision(Eligibility.BLOCKED_UNVERIFIED,
                "当前型号（" + (normModel.isEmpty() ? "未知" : normModel)
                        + "）与固件（" + (normFirmware.isEmpty() ? "未知" : normFirmware)
                        + "）尚无停用原厂桌面的实机验证记录，已禁止执行。部分机型停用原厂桌面可能导致黑屏或落回 FallbackHome，请使用「窗口回拉（兼容模式）」或「快速 Home」。");
    }
}
