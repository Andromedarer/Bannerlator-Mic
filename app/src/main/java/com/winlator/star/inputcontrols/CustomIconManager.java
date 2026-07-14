package com.winlator.star.inputcontrols;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.winlator.star.core.FileUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CustomIconManager {
    private static final String CUSTOM_ICONS_DIR = "custom_icons";
    public static final short CUSTOM_ICON_ID_OFFSET = 100;
    private static final short MAX_CUSTOM_ICON_ID = 255;
    private final File customIconsDir;
    private final Context context;

    public CustomIconManager(Context context) {
        this.context = context;
        this.customIconsDir = new File(context.getFilesDir(), CUSTOM_ICONS_DIR);
        if (!customIconsDir.exists()) customIconsDir.mkdirs();
    }

    public short addCustomIcon(Uri uri) {
        short nextId = getNextAvailableId();
        if (nextId < 0) return -1;
        File outputFile = new File(customIconsDir, nextId + ".png");
        try (InputStream is = context.getContentResolver().openInputStream(uri)) {
            if (is == null) return -1;
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap == null) return -1;
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                    outputFile.delete();
                    return -1;
                }
            }
            finally {
                bitmap.recycle();
            }
            return nextId;
        } catch (IOException e) {
            outputFile.delete();
            e.printStackTrace();
        }
        return -1;
    }

    private short getNextAvailableId() {
        List<Short> ids = getCustomIconIds();
        for (int id = CUSTOM_ICON_ID_OFFSET; id <= MAX_CUSTOM_ICON_ID; id++) {
            if (!ids.contains((short) id)) return (short) id;
        }
        return -1;
    }

    public List<Short> getCustomIconIds() {
        List<Short> ids = new ArrayList<>();
        File[] files = customIconsDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files != null) {
            for (File file : files) {
                try {
                    short id = Short.parseShort(FileUtils.getBasename(file.getName()));
                    if (id >= CUSTOM_ICON_ID_OFFSET && id <= MAX_CUSTOM_ICON_ID) ids.add(id);
                } catch (NumberFormatException e) {}
            }
        }
        Collections.sort(ids);
        return ids;
    }

    public Bitmap loadIcon(short id) {
        File file = new File(customIconsDir, id + ".png");
        if (file.exists()) {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
            }
        return null;
    }
}
