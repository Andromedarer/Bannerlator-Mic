# Wrapper Version Manager — Implementation Plan

Tracking issue: [#132](https://github.com/The412Banner/Bannerlator/issues/132)
Status: **planned, not started.** Reference studied: GunaCharanTeja/WinlatorMali (`bionic-mali-1.1`).

---

## Plain-language summary

Let users bring their own graphics **wrappers** (the layer that makes Windows games talk to the phone's GPU) instead of only the ones we bundle — and have each wrapper's settings appear automatically.

**Three steps, safe → ambitious:**

1. **Update the wrappers we already ship** *(simple, safe)* — an **Update** button per built-in wrapper (swap in a newer file) + **Reset** (revert to ours). Nothing else changes, so nothing existing can break. Delivers the core ask: newer wrappers **without an app update**.
2. **Add brand-new wrappers** *(medium)* — import / name / delete arbitrary wrappers, which then appear in the driver menu. Care needed: the menu grows/shrinks, and deleting a wrapper a game uses must auto-reset that game.
3. **Each wrapper's settings appear automatically** *(adaptable)* — the app **detects what settings a wrapper supports by scanning the wrapper file itself** (no cooperation from the wrapper author, because none of them ship a "menu card") and matches those against **a dictionary we maintain** to build proper toggles/sliders. Import a wrapper → its real settings appear.

**How it degrades (no menu card — which is every wrapper today):**
1. It runs on its own defaults (nothing breaks).
2. **Auto-detect** finds the setting *names* inside the wrapper file; **our dictionary** turns the common ones into proper controls with labels/ranges — zero author cooperation.
3. Names we don't recognize → a plain **"set your own value"** field.
4. For popular wrappers we can bundle a full settings definition ourselves.
5. A wrapper-shipped "menu card" is used if one ever exists — bonus, never required.
So the settings appear from what the wrapper *actually supports*, not from what it's *named* (the current name-gating weakness).

**Effort / risk:** Step 1 ≈ a day, low risk. Step 2 ≈ a few days, medium. Step 3 = the biggest piece (auto-detect scan + the dictionary + generic settings UI/emission); worth it once imported wrappers are in real use.

**Testing catch:** we can build/verify the app parts (import, menu, settings) ourselves; **"does an imported wrapper actually run a game" needs a Mali/Exynos community tester** (we have no such device).

**Suggested order:** Step 1 + Step 2 are **shipped** (import/update/delete, dynamic dropdown, cascade), plus an interim patch so imported wrappers show the integrated-wrapper option superset. Step 3 (auto-detect + dictionary) replaces that superset with a precise per-wrapper list — do it once imported wrappers are in real use.

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

### Step 3 — Per-wrapper settings via AUTO-DETECT + our own DICTIONARY (menu card optional)

**Reframe (user, 2026-07-19):** DON'T build this around a wrapper-shipped "menu card" — **no wrapper has ever shipped one**, and most never will. So the primary engine must need zero cooperation from wrapper authors. The card, if one ever appears, is a bonus on top — never a dependency.

**Why this is needed:** wrapper settings are gated by the driver *identifier* (e.g. `isGamenative = graphicsDriver == "wrapper-gamenative"` in `GraphicsDriverConfigDialog`). A user-imported wrapper has a *custom* name, so name-gating can't know its capabilities. (Interim patch already shipped: an imported wrapper is treated as the integrated-wrapper superset — `isImported` → show all gamenative-style options. Step 3 replaces that superset guess with a precise, per-wrapper list.)

**The engine (in priority order):**
1. **AUTO-DETECT the setting names from the wrapper binary.** On import, scan `usr/lib/libvulkan_wrapper.so` (and the bcn/compat `.so`s) for readable env-var-name strings matching the family vocabulary (`WRAPPER_*`, `ENABLE_*`, `BCN_*`, `COMPAT_*`, `MESA_VK_*`, `GALLIUM_*`, …). This is the **proven `strings`-on-binary trick** (used this session to find GameNative's `WRAPPER_DRIVER_ID`/`WRAPPER_SAFE_CREATE_DEVICE`). Store the detected key list in the wrapper's `.meta` at import time (so it's read once, off the launch path). Works on EVERY wrapper, no author involvement. Caveat: detection yields NAMES only, not types/ranges/labels — and may include a few false positives (label the panel "detected — advanced").
2. **MATCH detected names against a settings DICTIONARY we maintain.** A built-in table `key → { type: toggle|slider|dropdown|text, label, hint, default, min/max/step or choices }` covering the common Winlator-family vocabulary (`WRAPPER_VK_VERSION` = version string, `WRAPPER_EMULATE_BCN` = 0..3, `WRAPPER_BCN_ASTC` = toggle, present mode, extension blacklist, `COMPAT_*`, …). A matched key renders a **proper control**; the dictionary is OURS to grow (one line per new common setting → every wrapper that uses it gets a nice control). This is what turns raw detected names into a polished UI with zero author cooperation.
3. **UNKNOWN detected names →** a plain "set your own value" text field (still usable by power users).
4. **App-bundled cards for popular wrappers (optional polish):** for GameNative / Charan's Mali etc., WE can hand-write a full schema bundled in the app, keyed to the wrapper, to override/augment the auto-detected list for a perfect UI. No author needed.
5. **Wrapper-shipped card (optional bonus):** if a wrapper ever includes a settings schema (e.g. `settings.json` in the `.tzst`), use it verbatim. Never required.

**Plumbing (unchanged from before):**
- **Dynamic settings UI:** render controls from the resolved option list (auto-detect ∪ dictionary ∪ bundled-card) in `GraphicsDriverConfigDialog`. Store values as arbitrary keys in `graphicsDriverConfig` (already a `;`-separated k=v string — no storage change).
- **Generic env emission:** emit `key=value` from the resolved settings at launch (generalizes today's hardcoded per-var emission in `XServerDisplayActivity`). Unknown env vars are ignored by wrappers → safe.
- **For bundled wrappers:** keep today's precise curated gates (we already know their capabilities); auto-detect + dictionary is primarily for imports. (Could later migrate bundled ones to the dictionary too, to delete the hardcoded `isGamenative`/`isBcnLayer` gates.)

**Effort:** the biggest step. The dictionary + auto-detect scan + dynamic-UI/generic-emission refactor. Worth doing once imported wrappers are actually in use; the interim `isImported` superset covers the common case until then.

### ContentsManager alternative (considered, not chosen)
Adding a `CONTENT_TYPE_WRAPPER` to `ContentsManager`/`ContentProfile` would give enumerate/install/remove for free, but it copies files by manifest to explicit targets (not "extract a `.tzst` into imagefs root at launch") and has no container cascade — so a bespoke `WrapperManager` mirroring `AdrenotoolsManager` is lower-risk and idiom-matching. Keep ContentsManager in mind only if we later want remote-catalog/download parity.

### Risks (ranked)
1. Dynamic-dropdown drift (Step 2) — the two list builders must stay in lock-step.
2. Cascade correctness (Step 2) — a missed reference leaves a container pointing at a deleted wrapper.
3. Interop env contract (Step 1+) — imported third-party wrappers may need env we don't set; label experimental.
4. Extraction precedence subtlety — overrides must be self-contained.

### Key files
`contents/AdrenotoolsManager.java` · `ui/screens/AdrenoToolsScreen.kt` · `res/values/arrays.xml` (`graphics_driver_entries`) · `ui/screens/ContainerDetailViewModel.kt` · `ui/screens/ContainerDetailScreen.kt` · `ui/screens/ShortcutsScreen.kt` · `XServerDisplayActivity.java` (`extractGraphicsDriverFiles`) · `container/Container.java` · `core/StringUtils.java` · `core/TarCompressorUtils.java` · `util/InAppFilePicker.kt` · nav `ui/{Screen.kt,AppNavGraph.kt,AppDrawer.kt}` + `MainActivity.kt` · `assets/graphics_driver/wrapper-*.tzst`.
