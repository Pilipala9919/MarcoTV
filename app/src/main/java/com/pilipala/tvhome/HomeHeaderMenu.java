// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

/**
 * Pure D-pad / touch state machine for the collapsible top-left MarcoTV header bar.
 *
 * <p>Collapsed by default so only the MarcoTV brand pill and top-right Wi-Fi icon remain visible.
 * Activating MarcoTV expands four quick entries in order: 桌面设置, Marco互联, MagicDesktop, 应用管理.
 */
final class HomeHeaderMenu {
    static final int SLOT_BRAND = 0;
    static final int SLOT_SETTINGS = 1;
    static final int SLOT_CONNECT = 2;
    static final int SLOT_MAGIC = 3;
    static final int SLOT_APP_MANAGER = 4;
    static final int SLOT_WIFI = 5;
    static final int LEFT_SLOT_COUNT = 5;

    static final String BRAND_LABEL = "MarcoTV";
    static final String[] EXPANDED_LABELS = {"桌面设置", "Marco互联", "MagicDesktop", "应用管理"};

    enum Action {
        NONE,
        EXPANDED,
        COLLAPSED,
        OPEN_SETTINGS,
        OPEN_CONNECT,
        OPEN_MAGIC_DESKTOP,
        OPEN_APP_MANAGER,
        OPEN_WIFI
    }

    private boolean expanded;
    private int selected = SLOT_BRAND;

    boolean isExpanded() {
        return expanded;
    }

    int selected() {
        return selected;
    }

    void expand() {
        expanded = true;
        selected = SLOT_SETTINGS;
    }

    void collapseToBrand() {
        expanded = false;
        selected = SLOT_BRAND;
    }

    void collapsePreservingWifi() {
        expanded = false;
        if (selected != SLOT_WIFI) selected = SLOT_BRAND;
    }

    void onLeaveTopRow() {
        if (expanded) {
            expanded = false;
            selected = SLOT_BRAND;
        }
    }

    boolean onLeft() {
        if (!expanded) {
            if (selected == SLOT_WIFI) {
                selected = SLOT_BRAND;
                return true;
            }
            return false;
        }
        if (selected == SLOT_WIFI) {
            selected = SLOT_APP_MANAGER;
            return true;
        }
        if (selected > SLOT_BRAND) {
            selected--;
            return true;
        }
        return false;
    }

    boolean onRight() {
        if (!expanded) {
            if (selected == SLOT_BRAND) {
                selected = SLOT_WIFI;
                return true;
            }
            return false;
        }
        if (selected < SLOT_APP_MANAGER) {
            selected++;
            return true;
        }
        if (selected == SLOT_APP_MANAGER) {
            expanded = false;
            selected = SLOT_WIFI;
            return true;
        }
        return false;
    }

    /** Returns true when Back collapsed the expanded menu instead of leaving the top row. */
    boolean onBack() {
        if (!expanded) return false;
        expanded = false;
        selected = SLOT_BRAND;
        return true;
    }

    Action onConfirm() {
        return activateSlot(selected);
    }

    Action activateSlot(int slot) {
        if (slot == SLOT_WIFI) {
            expanded = false;
            selected = SLOT_WIFI;
            return Action.OPEN_WIFI;
        }
        if (slot == SLOT_BRAND) {
            if (!expanded) {
                expanded = true;
                selected = SLOT_SETTINGS;
                return Action.EXPANDED;
            }
            expanded = false;
            selected = SLOT_BRAND;
            return Action.COLLAPSED;
        }
        if (!expanded) return Action.NONE;
        expanded = false;
        selected = SLOT_BRAND;
        if (slot == SLOT_SETTINGS) return Action.OPEN_SETTINGS;
        if (slot == SLOT_CONNECT) return Action.OPEN_CONNECT;
        if (slot == SLOT_MAGIC) return Action.OPEN_MAGIC_DESKTOP;
        if (slot == SLOT_APP_MANAGER) return Action.OPEN_APP_MANAGER;
        return Action.NONE;
    }

    String describeSelected() {
        if (selected == SLOT_WIFI) return "网络与 Wi-Fi";
        if (selected == SLOT_BRAND) return expanded ? "MarcoTV，按确定收起菜单" : "MarcoTV，按确定展开菜单";
        if (selected == SLOT_SETTINGS) return "桌面设置";
        if (selected == SLOT_CONNECT) return "Marco互联，用手机整理电视";
        if (selected == SLOT_MAGIC) return "MagicDesktop，预览并调整桌面";
        if (selected == SLOT_APP_MANAGER) return "应用管理";
        return BRAND_LABEL;
    }
}
