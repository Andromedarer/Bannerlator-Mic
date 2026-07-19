# Wrapper Version Manager — Implementation Plan

Tracking issue: [#132](https://github.com/The412Banner/Bannerlator/issues/132)
Status: **planned, not started.** Reference studied: GunaCharanTeja/WinlatorMali (`bionic-mali-1.1`).

---

## Plain-language summary

Let users bring their own graphics **wrappers** (the layer that makes Windows games talk to the phone's GPU) instead of only the ones we bundle — and have each wrapper's settings appear automatically.

**Three steps, safe → ambitious:**

1. **Update the wrappers we already ship** *(simple, safe)* — an **Update** button per built-in wrapper (swap in a newer file) + **Reset** (revert to ours). Nothing else changes, so nothing existing can break. Delivers the core ask: newer wrappers **without an app update**.
2. **Add brand-new wrappers** *(medium)* — import / name / delete arbitrary wrappers, which then appear in the driver menu. Care needed: the menu grows/shrinks, and deleting a wrapper a game uses must auto-reset that game.
3. **Wrappers describe their own settings** *(adaptable)* — a wrapper can include a small "menu card" declaring its toggles/sliders; the app builds those controls automatically. Import a wrapper → its settings appear.

**If a wrapper has no menu card** it still works — we fall back, in order:
1. It runs on its own defaults (nothing breaks).
2. We show the **standard settings** almost every Winlator-family wrapper understands; a wrapper ignores any it doesn't (harmless).
3. A **"add your own setting = value"** box for power users.
4. For popular wrappers we can bundle a card ourselves.
So the menu card makes settings *perfect*; it is not required to *work*.

**Effort / risk:** Step 1 ≈ a day, low risk. Step 2 ≈ a few days, medium. Step 3 = the biggest piece; worth it if wrappers proliferate.

**Testing catch:** we can build/verify the app parts (import, menu, settings) ourselves; **"does an imported wrapper actually run a game" needs a Mali/Exynos community tester** (we have no such device).

**Suggested order:** ship **Step 1 + the "add your own setting" box** first, then Step 2, then Step 3 once a second real wrapper exists to prove it against.

---

## Reality check — what the reference (WinlatorMali) actually does

Issue #132 asks for free-form import/rename/delete. The referenced app's screen (`ManageGraphicsDriversFragment`) is **not** that — it's a **fixed-slot updater**: 6 hardcoded slots, each with Info / **Update** (replace with a file of the *exact same name*) / **Remove** (drop the override). Overrides live at `filesDir/graphics_driver/<name>.tzst` and win over the bundled asset. No arbitrary add, no rename. So the requester is describing a capability their reference doesn't have — our Step 1 mirrors what actually exists; Step 2 is the true free-form ask.

**Interop:** their wrapper `.tzst` files are the **same format as ours** (zstd tar → `usr/lib/libvulkan_wrapper.so` + `usr/share/vulkan/icd.d/wrapper_icd.aarch64.json`, extracted into the imagefs root). Their manager validates nothing inside the archive (only external filename + an optional `version.txt` for display). So we can accept their wrapper files directly — caveat: a third-party wrapper may expect runtime env (e.g. `GALLIUM_DRIVER=zink`) we don't set; label imports advanced/experimental.

---

## Technical plan (grounded anchors)

### Shared foundation
- **`WrapperManager.java`** (new, `contents/`) — mirror `AdrenotoolsManager`. Storage `filesDir/graphics_driver/<identifier>.tzst`. Methods: `listSlots()`, `installOverride(name, Uri)`, `removeOverride(name)`, `enumerateImported()`, `readVersionInfo(file)` (adopt WinlatorMali's `version.txt` = `version:` / `notes:` lines via `TarCompressorUtils.readTextFile`). Recommend shipping a `version.txt` inside our own wrapper tzsts so slots show real versions.
- **Extraction precedence** — `XServerDisplayActivity.extractGraphicsDriverFiles()`, insert ~L3265 **before** the `startsWith("wrapper-…")` chain: if `filesDir/graphics_driver/<graphicsDriver>.tzst` exists, extract it (File overload `TarCompressorUtils.java:197`) and skip the bundled chain; else fall through unchanged. Override must be a **self-contained** wrapper (the bundled chain is not 1:1 name→file — bcn/compat reuse the leegao/gamenative base).

### Step 1 — Slot Update (WinlatorMali parity)
- **UI:** clone `AdrenoToolsScreen.kt` → `WrapperManagerScreen.kt` (already has file-picker launcher + install/remove dialogs + list rows). Rows = wrapper slots; buttons Info / Update (SAF `ACTION_OPEN_DOCUMENT`) / Remove override / toolbar Reset All.
- **File filter:** add `WRAPPER = arrayOf("tzst")` in `InAppFilePicker.kt:25`.
- **Validation:** verify the imported tzst contains `usr/lib/libvulkan_wrapper.so` before accepting.
- **Nav (4 files, mirror AdrenoTools):** `Screen.kt` (new `Screen.Wrappers`), `AppNavGraph.kt`, `AppDrawer.kt`, `MainActivity.kt` menu-id→route.
- **No** dropdown / `parseIdentifier` / `Container` / cascade changes. That is the whole point of Step 1.
- Files: `WrapperManager.java` (new), `WrapperManagerScreen.kt` (new), `XServerDisplayActivity.java` (precedence), `InAppFilePicker.kt`, `Screen.kt`, `AppNavGraph.kt`, `AppDrawer.kt`, `MainActivity.kt`, strings.

### Step 2 — Free-form manager
Three net-new pieces:
1. **Dynamic dropdown** — replace the two `getStringArray(R.array.graphics_driver_entries)` reads (`ContainerDetailViewModel.kt:249` **and** `ShortcutsScreen.kt:3739`) with `bundled + WrapperManager.enumerateImported()`. Both must build the identical list or the display↔identifier round-trip resets an imported pick to entry[0].
2. **Identifier collisions** — imported names run through `StringUtils.parseIdentifier` (strips trailing `(...)`, collapses spaces/`+`). Persist a canonical `identifier` in a per-wrapper `meta.json`; reject/suffix collisions with bundled ids (`wrapper-leegao`) or other imports.
3. **Delete/rename cascade (net-new)** — sweep every Container (`graphicsDriver` field → `saveData()`) and Shortcut (`graphicsDriver` extra → `saveData()`) referencing the removed/renamed id; reset to `DEFAULT_GRAPHICS_DRIVER` (delete) or new id (rename). The existing `AdrenotoolsManager.reloadContainers` only rewrites the config `version` key, so this is new (but a close analog to copy).
- Storage migrates to `contents/wrappers/<id>/` (`wrapper.tzst` + `meta.json`). Import package = `.zip` carrying `meta.json` + `wrapper.tzst` (mirrors `AdrenotoolsManager.installDriver`).

### Step 3 — Manifest-driven settings ("menu card")
- Define a **settings schema** (JSON) a wrapper package may include: list of options, each `{ key (env var), type: toggle|slider|dropdown|text, label, hint, default, min/max/step or choices }`.
- **Dynamic settings UI:** render controls from the schema in the graphics-driver config dialog (Compose). Store values as arbitrary keys in `graphicsDriverConfig` (already a `;`-separated k=v string — no storage change needed).
- **Generic env emission:** emit `key=value` env vars from schema+stored values at launch (generalizes today's hardcoded per-var emission in `XServerDisplayActivity`). Unknown env vars are ignored by wrappers → safe.
- **Fallbacks (no card):** (a) a bundled **standard/common** schema shown for any wrapper (Vulkan version, BCn mode, present mode, extension blacklist, …); (b) a raw **"add your own setting = value"** advanced editor; (c) app-bundled cards for popular wrappers.
- Own the format; ship it in our wrappers; invite authors (Charan, GameNative) to adopt it.

### ContentsManager alternative (considered, not chosen)
Adding a `CONTENT_TYPE_WRAPPER` to `ContentsManager`/`ContentProfile` would give enumerate/install/remove for free, but it copies files by manifest to explicit targets (not "extract a `.tzst` into imagefs root at launch") and has no container cascade — so a bespoke `WrapperManager` mirroring `AdrenotoolsManager` is lower-risk and idiom-matching. Keep ContentsManager in mind only if we later want remote-catalog/download parity.

### Risks (ranked)
1. Dynamic-dropdown drift (Step 2) — the two list builders must stay in lock-step.
2. Cascade correctness (Step 2) — a missed reference leaves a container pointing at a deleted wrapper.
3. Interop env contract (Step 1+) — imported third-party wrappers may need env we don't set; label experimental.
4. Extraction precedence subtlety — overrides must be self-contained.

### Key files
`contents/AdrenotoolsManager.java` · `ui/screens/AdrenoToolsScreen.kt` · `res/values/arrays.xml` (`graphics_driver_entries`) · `ui/screens/ContainerDetailViewModel.kt` · `ui/screens/ContainerDetailScreen.kt` · `ui/screens/ShortcutsScreen.kt` · `XServerDisplayActivity.java` (`extractGraphicsDriverFiles`) · `container/Container.java` · `core/StringUtils.java` · `core/TarCompressorUtils.java` · `util/InAppFilePicker.kt` · nav `ui/{Screen.kt,AppNavGraph.kt,AppDrawer.kt}` + `MainActivity.kt` · `assets/graphics_driver/wrapper-*.tzst`.
