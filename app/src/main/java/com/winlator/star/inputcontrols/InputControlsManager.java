package com.winlator.star.inputcontrols;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.util.JsonReader;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.winlator.star.SettingsFragment;
import com.winlator.star.core.AppUtils;
import com.winlator.star.core.FileUtils;

import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class InputControlsManager {
    private static final Object PROFILE_IMPORT_LOCK = new Object();
    private final Context context;
    private ArrayList<ControlsProfile> profiles;
    private int maxProfileId;
    private boolean profilesLoaded = false;

    public InputControlsManager(Context context) {
        this.context = context;
    }

    public static File getProfilesDir(Context context) {
        File profilesDir = new File(context.getFilesDir(), "profiles");
        if (!profilesDir.isDirectory()) profilesDir.mkdir();
        return profilesDir;
    }

    public ArrayList<ControlsProfile> getProfiles() {
        return getProfiles(false);
    }

    public ArrayList<ControlsProfile> getProfiles(boolean ignoreTemplates) {
        if (!profilesLoaded) loadProfiles(ignoreTemplates);
        return profiles;
    }

    private void copyAssetProfilesIfNeeded() {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        if (FileUtils.isEmpty(profilesDir)) {
            FileUtils.copy(context, "inputcontrols/profiles", profilesDir);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);

        int newVersion = AppUtils.getVersionCode(context);
        int oldVersion = preferences.getInt("inputcontrols_app_version", 0);
        if (oldVersion == newVersion) return;
        preferences.edit().putInt("inputcontrols_app_version", newVersion).apply();

        File[] files = profilesDir.listFiles();
        if (files == null) return;

        try {
            AssetManager assetManager = context.getAssets();
            String[] assetFiles = assetManager.list("inputcontrols/profiles");
            for (String assetFile : assetFiles) {
                String assetPath = "inputcontrols/profiles/"+assetFile;
                ControlsProfile originProfile = loadProfile(context, assetManager.open(assetPath));

                File targetFile = null;
                for (File file : files) {
                    ControlsProfile targetProfile = loadProfile(context, file);
                    if (originProfile.id == targetProfile.id && originProfile.getName().equals(targetProfile.getName())) {
                        targetFile = file;
                        break;
                    }
                }

                if (targetFile != null) {
                    FileUtils.copy(context, assetPath, targetFile);
                }
            }
        }
        catch (IOException e) {}
    }

    public void loadProfiles(boolean ignoreTemplates) {
        File profilesDir = InputControlsManager.getProfilesDir(context);
        copyAssetProfilesIfNeeded();

        ArrayList<ControlsProfile> profiles = new ArrayList<>();
        File[] files = profilesDir.listFiles();
        if (files != null) {
            for (File file : files) {
                ControlsProfile profile = loadProfile(context, file);
                if (profile == null) continue;
                if (!(ignoreTemplates && profile.isTemplate())) profiles.add(profile);
                maxProfileId = Math.max(maxProfileId, profile.id);
            }
        }

        Collections.sort(profiles);
        this.profiles = profiles;
        profilesLoaded = true;
    }

    public ControlsProfile createProfile(String name) {
        ControlsProfile profile = new ControlsProfile(context, ++maxProfileId);
        profile.setName(name);
        profile.save();
        profiles.add(profile);
        return profile;
    }

    public ControlsProfile duplicateProfile(ControlsProfile source) {
        String newName;
        for (int i = 1;;i++) {
            newName = source.getName() + " ("+i+")";
            boolean found = false;
            for (ControlsProfile profile : profiles) {
                if (profile.getName().equals(newName)) {
                    found = true;
                    break;
                }
            }
            if (!found) break;
        }

        int newId = ++maxProfileId;
        File newFile = ControlsProfile.getProfileFile(context, newId);

        try {
            JSONObject data = new JSONObject(FileUtils.readString(ControlsProfile.getProfileFile(context, source.id)));
            data.put("schemaVersion", ControlsProfile.SCHEMA_VERSION);
            data.put("minEditorVersion", ControlsProfile.MIN_EDITOR_VERSION);
            data.put("id", newId);
            data.put("name", newName);
            if (data.has("template")) data.remove("template");
            FileUtils.writeString(newFile, data.toString());
        }
        catch (JSONException e) {}

        ControlsProfile profile = loadProfile(context, newFile);
        profiles.add(profile);
        return profile;
    }

    public void removeProfile(ControlsProfile profile) {
        File file = ControlsProfile.getProfileFile(context, profile.id);
        if (file.isFile() && file.delete()) profiles.remove(profile);
    }

    @Nullable
    public ControlsProfile importProfile(JSONObject data) {
        synchronized (PROFILE_IMPORT_LOCK) {
            return importProfileLocked(data);
        }
    }

    private ControlsProfile importProfileLocked(JSONObject data) {
        CustomIconManager customIconManager = new CustomIconManager(context);
        ArrayList<Short> importedIconIds = new ArrayList<>();
        try {
            Object profileName = data.opt("name");
            if (!data.has("id") || !(profileName instanceof String)
                    || ((String)profileName).trim().isEmpty()) return null;
            int schemaVersion = data.optInt("schemaVersion", 1);
            int minEditorVersion = data.optInt("minEditorVersion", 1);
            if (schemaVersion > ControlsProfile.SCHEMA_VERSION
                    || minEditorVersion > ControlsProfile.EDITOR_VERSION) return null;

            Map<Integer, Integer> iconIdMap = new HashMap<>();
            JSONArray embeddedIcons = data.optJSONArray("customIcons");
            if (embeddedIcons != null) {
                JSONArray elements = data.optJSONArray("elements");
                if (elements != null) {
                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject element = elements.optJSONObject(i);
                        int iconId = element != null ? element.optInt("iconId", 0) : 0;
                        if (iconId >= CustomIconManager.CUSTOM_ICON_ID_OFFSET
                                && iconId <= CustomIconManager.MAX_CUSTOM_ICON_ID) iconIdMap.put(iconId, 0);
                    }
                }
                for (int i = 0; i < embeddedIcons.length(); i++) {
                    JSONObject embeddedIcon = embeddedIcons.optJSONObject(i);
                    if (embeddedIcon == null) continue;
                    int sourceId = embeddedIcon.optInt("id", -1);
                    if (!iconIdMap.containsKey(sourceId) || iconIdMap.get(sourceId) != 0) continue;
                    CustomIconManager.ImportedIcon importedIcon = customIconManager.importEncodedIcon(
                            embeddedIcon.optString("png", null));
                    if (importedIcon != null) {
                        iconIdMap.put(sourceId, (int)importedIcon.id);
                        if (importedIcon.created) importedIconIds.add(importedIcon.id);
                    }
                }
                for (int targetId : iconIdMap.values()) {
                    if (targetId == 0) {
                        rollbackImportedIcons(customIconManager, importedIconIds);
                        return null;
                    }
                }
                data.remove("customIcons");
            }
            remapIconIds(data, iconIdMap);

            int newId = ++maxProfileId;
            File newFile = ControlsProfile.getProfileFile(context, newId);
            data.put("schemaVersion", ControlsProfile.SCHEMA_VERSION);
            data.put("minEditorVersion", ControlsProfile.MIN_EDITOR_VERSION);
            data.put("id", newId);
            if (!FileUtils.writeString(newFile, data.toString())) {
                rollbackImportedIcons(customIconManager, importedIconIds);
                return null;
            }
            ControlsProfile newProfile = loadProfile(context, newFile);
            if (newProfile == null) {
                newFile.delete();
                rollbackImportedIcons(customIconManager, importedIconIds);
                return null;
            }

            int foundIndex = -1;
            for (int i = 0; i < profiles.size(); i++) {
                ControlsProfile profile = profiles.get(i);
                if (profile.getName().equals(newProfile.getName())) {
                    foundIndex = i;
                    break;
                }
            }

            if (foundIndex != -1) {
                ControlsProfile replacedProfile = profiles.get(foundIndex);
                File replacedFile = ControlsProfile.getProfileFile(context, replacedProfile.id);
                if (!replacedFile.equals(newFile)) replacedFile.delete();
                profiles.set(foundIndex, newProfile);
            }
            else profiles.add(newProfile);
            return newProfile;
        }
        catch (JSONException | RuntimeException e) {
            rollbackImportedIcons(customIconManager, importedIconIds);
            return null;
        }
    }

    public File exportProfile(ControlsProfile profile) {
        if (!profile.save()) return null;
        File destination;
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String winlatorPath = sp.getString("winlator_path_uri", null);
        if (winlatorPath != null) {
            Uri winlatorUri = Uri.parse(winlatorPath);
            destination = new File(FileUtils.getFilePathFromUri(context, winlatorUri), "profiles/" + getSafeProfileName(profile) + ".icp");
        }
        else {
            destination = new File(SettingsFragment.DEFAULT_WINLATOR_PATH, "profiles/" + getSafeProfileName(profile) + ".icp");
        }
        File source = ControlsProfile.getProfileFile(context, profile.id);
        try {
            JSONObject data = new JSONObject(FileUtils.readString(source));
            JSONArray elements = data.optJSONArray("elements");
            Set<Integer> iconIds = new HashSet<>();
            if (elements != null) {
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject element = elements.optJSONObject(i);
                    if (element == null) continue;
                    int iconId = element.optInt("iconId", 0);
                    if (iconId >= CustomIconManager.CUSTOM_ICON_ID_OFFSET) iconIds.add(iconId);
                }
            }

            CustomIconManager customIconManager = new CustomIconManager(context);
            JSONArray embeddedIcons = new JSONArray();
            for (int iconId : iconIds) {
                String encodedIcon = customIconManager.encodeIcon(iconId);
                if (encodedIcon == null) return null;
                JSONObject embeddedIcon = new JSONObject();
                embeddedIcon.put("id", iconId);
                embeddedIcon.put("png", encodedIcon);
                embeddedIcons.put(embeddedIcon);
            }
            if (embeddedIcons.length() > 0) data.put("customIcons", embeddedIcons);
            else data.remove("customIcons");
            File parent = destination.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return null;
            File temporaryFile = new File(parent, destination.getName() + ".tmp");
            if (!FileUtils.writeString(temporaryFile, data.toString())) return null;
            try {
                Files.move(temporaryFile.toPath(), destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (IOException atomicMoveError) {
                Files.move(temporaryFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (JSONException | IOException e) {
            return null;
        }
        MediaScannerConnection.scanFile(context, new String[]{destination.getAbsolutePath()}, null, null);
        return destination.isFile() ? destination : null;
    }

    static void remapIconIds(JSONObject data, Map<Integer, Integer> iconIdMap) throws JSONException {
        JSONArray elements = data.optJSONArray("elements");
        if (elements == null || iconIdMap.isEmpty()) return;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element == null) continue;
            int sourceIconId = element.optInt("iconId", 0);
            Integer targetIconId = iconIdMap.get(sourceIconId);
            if (targetIconId != null) element.put("iconId", targetIconId);
        }
    }

    private static String getSafeProfileName(ControlsProfile profile) {
        return profile.getName().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static void rollbackImportedIcons(CustomIconManager manager, ArrayList<Short> importedIconIds) {
        for (short iconId : importedIconIds) manager.deleteIcon(iconId);
    }


    public static ControlsProfile loadProfile(Context context, File file) {
        try {
            return loadProfile(context, new FileInputStream(file));
        }
        catch (FileNotFoundException e) {
            return null;
        }
    }

    public static ControlsProfile loadProfile(Context context, InputStream inStream) {
        try (JsonReader reader = new JsonReader(new InputStreamReader(inStream, StandardCharsets.UTF_8))) {
            int profileId = 0;
            String profileName = null;
            float cursorSpeed = Float.NaN;
            boolean customAccentEnabled = false;
            int customAccentColor = 0xFF0055FF;

            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();

                if (name.equals("id")) {
                    profileId = reader.nextInt();
                }
                else if (name.equals("name")) {
                    profileName = reader.nextString();
                }
                else if (name.equals("cursorSpeed")) {
                    cursorSpeed = (float) reader.nextDouble();
                }
                else if (name.equals("customAccentEnabled")) {
                    customAccentEnabled = reader.nextBoolean();
                }
                else if (name.equals("customAccentColor")) {
                    customAccentColor = reader.nextInt();
                }
                else {
                    // Stop as soon as the heavy arrays are reached — they're always written after the
                    // lightweight header, so the header fields are guaranteed read by now. Robust to
                    // the optional accent fields appearing in any order (old profiles without them
                    // simply keep the defaults). Skip any other unknown header field.
                    if (name.equals("elements") || name.equals("controllers")) break;
                    else reader.skipValue();
                }
            }

            if (profileName == null || profileName.trim().isEmpty()) return null;
            ControlsProfile profile = new ControlsProfile(context, profileId);
            profile.setName(profileName);
            profile.setCursorSpeed(Float.isNaN(cursorSpeed) ? 1.0f : cursorSpeed);
            profile.setCustomAccentEnabled(customAccentEnabled);
            profile.setCustomAccentColor(customAccentColor);
            return profile;
        }
        catch (IOException | IllegalStateException | NumberFormatException e) {
            return null;
        }
    }

    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : getProfiles()) if (profile.id == id) return profile;
        return null;
    }
}
