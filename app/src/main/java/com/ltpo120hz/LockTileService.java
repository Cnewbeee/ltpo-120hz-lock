package com.ltpo120hz;

import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import java.util.Locale;

/**
 * 下拉快捷开关：出门/看视频时快速关掉，避免一直高刷耗电。
 */
public final class LockTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        refresh();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (LockService.isRunning()) {
            LockService.stop(this);
        } else if (Settings.canDrawOverlays(this)) {
            try {
                LockService.start(this);
            } catch (Throwable t) {
                Logbuf.add("tile", "启动失败: " + t);
            }
        }
        // 状态在服务真正起来之后再刷新一次
        refresh();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::refresh, 700L);
    }

    private void refresh() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean on = LockService.isRunning();
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        if (Build.VERSION.SDK_INT >= 29) {
            float hz = Prefs.targetHz(this);
            String state;
            if (!on) {
                state = "已关闭";
            } else if (LockService.isGameSuspended()) {
                state = "游戏中·暂停";
            } else {
                state = String.format(Locale.US, "%.0fHz 锁定中", hz);
            }
            tile.setSubtitle(state);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            Icon icon = Icon.createWithResource(this, R.drawable.ic_stat_hz);
            tile.setIcon(icon);
        }
        tile.updateTile();
    }
}
