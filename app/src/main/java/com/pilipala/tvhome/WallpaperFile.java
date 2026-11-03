// Copyright (C) 2026 Pilipala
// SPDX-License-Identifier: GPL-3.0-only
package com.pilipala.tvhome;

import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.IOException;

/** Same-directory atomic promotion preserves the established backup wallpaper identity. */
final class WallpaperFile {
    static File publish(File staged,File directory)throws IOException {
        if(!staged.isFile()||!staged.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))
            throw new IOException("Wallpaper must be staged in private storage");
        File target=new File(directory,"wallpaper-image");
        try{Os.rename(staged.getAbsolutePath(),target.getAbsolutePath());}
        catch(ErrnoException error){throw new IOException("Cannot publish wallpaper",error);}
        return target;
    }
}
