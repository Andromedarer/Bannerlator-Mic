package com.winlator.star.core

import java.io.File

/**
 * Best-effort identification of a game from its on-disk footprint, given the .exe the
 * user picked in the "+" importer. Produces a [GameIdentity] with a Steam appId (the
 * master key that later drives correct cover-art and dependency detection) and/or a
 * display name, so the shortcut isn't just named after the raw exe filename.
 *
 * Phase 1.1 of Smart Game Import: pure filesystem + PE parsing, no network, never
 * throws. appId→name network resolution and SGDB-by-appid art are the caller's job
 * (Phase 1.2 wiring); this class only reports what the disk already tells us.
 *
 * Signal chain (appId preferred; best name chosen across all hits):
 *  1. steam_appid.txt            → appId            (Goldberg / cracked games)
 *  2. ColdClientLoader / emu ini → appId            (AppId= key)
 *  3. appmanifest_<id>.acf       → appId + name     (real Steam install under steamapps/)
 *  4. goggame-<id>.info          → name (+ gogId)   (GOG installs)
 *  5. PE VS_VERSIONINFO          → name             (ProductName / FileDescription)
 *  6. cleaned folder / exe name  → name             (weak fallback ≈ old behaviour)
 */
object GameIdentifier {

    /** How far up the directory tree to look for marker files (games nest deep, e.g. Game/Binaries/Win64/x.exe). */
    private const val MAX_PARENT_DEPTH = 6

    enum class Source { STEAM_APPID_TXT, STEAM_MANIFEST_ACF, STEAM_EMU_INI, GOG_INFO, PE_VERSION, FILENAME, NONE }
    enum class Confidence { HIGH, MEDIUM, LOW }

    /**
     * @param appId  Steam appId if one was found on disk, else null.
     * @param gogId  GOG product id if a goggame info file was found, else null.
     * @param name   Best display name found locally; may be null when only an appId was
     *               found (caller resolves the name from the appId over the network).
     * @param source Which signal produced the primary identity.
     */
    data class GameIdentity(
        val appId: Int? = null,
        val gogId: String? = null,
        val name: String? = null,
        val source: Source = Source.NONE,
        val confidence: Confidence = Confidence.LOW,
    ) {
        val hasStrongId: Boolean get() = appId != null
    }

    /** Identify the game owning [exeFile]. Never throws; returns an empty identity if nothing matched. */
    fun identify(exeFile: File): GameIdentity {
        val dirs = ancestorDirs(exeFile)

        // Steam appId from a bare steam_appid.txt (in the exe dir, a steam_settings/ subdir, or an ancestor).
        val steamAppId = dirs.firstNotNullOfOrNull { readSteamAppIdTxt(it) }
        // AppId= out of a ColdClientLoader.ini / steam_emu.ini next to the game.
        val emuAppId = dirs.firstNotNullOfOrNull { readEmuIniAppId(it) }
        // Real Steam install: appmanifest_<id>.acf up under steamapps/ — carries both appId and name.
        val acf = resolveSteamManifest(exeFile, dirs)
        // GOG: goggame-<id>.info JSON with the real title.
        val gog = dirs.firstNotNullOfOrNull { readGogInfo(it) }
        // PE ProductName / FileDescription.
        val peName = PeVersionInfo.bestName(PeVersionInfo.read(exeFile))

        val appId = acf?.appId ?: steamAppId ?: emuAppId

        // Best name, most-trustworthy first.
        val nameHit: Pair<String, Source>? = when {
            acf?.name != null -> acf.name to Source.STEAM_MANIFEST_ACF
            gog?.name != null -> gog.name to Source.GOG_INFO
            peName != null -> peName to Source.PE_VERSION
            else -> cleanedFallbackName(exeFile)?.let { it to Source.FILENAME }
        }

        // Primary source = whatever gave us the appId (strongest), else the name source.
        val primary: Source = when {
            acf?.appId != null -> Source.STEAM_MANIFEST_ACF
            steamAppId != null -> Source.STEAM_APPID_TXT
            emuAppId != null -> Source.STEAM_EMU_INI
            else -> nameHit?.second ?: Source.NONE
        }

        val confidence = when {
            appId != null -> Confidence.HIGH
            nameHit?.second == Source.GOG_INFO -> Confidence.HIGH
            nameHit?.second == Source.PE_VERSION -> Confidence.MEDIUM
            else -> Confidence.LOW
        }

        return GameIdentity(
            appId = appId,
            gogId = gog?.id,
            name = nameHit?.first,
            source = primary,
            confidence = confidence,
        )
    }

    // ── directory walk ──

    /** The exe's directory plus its ancestors, nearest-first, bounded by [MAX_PARENT_DEPTH]. */
    private fun ancestorDirs(exeFile: File): List<File> {
        val out = ArrayList<File>(MAX_PARENT_DEPTH + 1)
        var d: File? = exeFile.absoluteFile.parentFile
        var depth = 0
        while (d != null && depth <= MAX_PARENT_DEPTH) {
            out.add(d)
            d = d.parentFile
            depth++
        }
        return out
    }

    // ── signal readers (all best-effort; return null on anything unexpected) ──

    /** steam_appid.txt directly in [dir] or in a steam_settings/ subdir. */
    private fun readSteamAppIdTxt(dir: File): Int? {
        for (candidate in arrayOf(File(dir, "steam_appid.txt"), File(dir, "steam_settings/steam_appid.txt"))) {
            val id = readSmallTextFile(candidate)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()
            if (id != null && id > 0) return id
        }
        return null
    }

    /** First `AppId=<n>` in a ColdClientLoader.ini / steam_emu.ini / hlm.ini next to the game. */
    private fun readEmuIniAppId(dir: File): Int? {
        val re = Regex("""(?im)^\s*appid\s*=\s*(\d+)""")
        for (name in arrayOf("ColdClientLoader.ini", "steam_emu.ini", "hlm.ini", "valve.ini", "cream_api.ini")) {
            val text = readSmallTextFile(File(dir, name)) ?: continue
            val id = re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (id != null && id > 0) return id
        }
        return null
    }

    private data class Acf(val appId: Int?, val name: String?)

    /**
     * Resolve the Steam appmanifest for this game: find the `common` ancestor on the exe's
     * path, read the sibling `steamapps/appmanifest_*.acf`, and match by installdir == the
     * game folder under common. Falls back to the sole manifest when there's exactly one.
     */
    private fun resolveSteamManifest(exeFile: File, dirs: List<File>): Acf? {
        val commonIdx = dirs.indexOfFirst { it.name.equals("common", ignoreCase = true) }
        if (commonIdx < 0) return null
        val commonDir = dirs[commonIdx]
        val steamapps = commonDir.parentFile ?: return null
        val manifests = steamapps.listFiles { f ->
            f.isFile && f.name.startsWith("appmanifest_") && f.name.endsWith(".acf")
        } ?: return null
        if (manifests.isEmpty()) return null

        // The game's install folder is the child of `common` on our descent path.
        val gameFolder = if (commonIdx > 0) dirs[commonIdx - 1].name else null

        val match = manifests.firstOrNull { m ->
            val text = readSmallTextFile(m) ?: return@firstOrNull false
            val installdir = vdfValue(text, "installdir")
            gameFolder != null && installdir != null && installdir.equals(gameFolder, ignoreCase = true)
        } ?: manifests.singleOrNull() ?: return null

        val text = readSmallTextFile(match) ?: return null
        val appId = vdfValue(text, "appid")?.toIntOrNull()?.takeIf { it > 0 }
        val name = vdfValue(text, "name")?.takeIf { it.isNotBlank() }
        return if (appId == null && name == null) null else Acf(appId, name)
    }

    private data class Gog(val id: String?, val name: String?)

    /** goggame-<id>.info JSON: pull `name` (and the id from the filename). */
    private fun readGogInfo(dir: File): Gog? {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("goggame-") && f.name.endsWith(".info")
        } ?: return null
        for (f in files) {
            val text = readSmallTextFile(f) ?: continue
            val name = jsonStringValue(text, "name")?.takeIf { it.isNotBlank() } ?: continue
            val id = f.name.removePrefix("goggame-").removeSuffix(".info").takeIf { it.isNotBlank() }
            return Gog(id, name)
        }
        return null
    }

    // ── filename fallback ──

    private val REPACK_TOKENS = setOf(
        "fitgirl", "dodi", "codex", "plaza", "skidrow", "rune", "empress", "flt", "tenoke",
        "reloaded", "elamigos", "razor1911", "hoodlum", "gog", "repack", "multi", "goldberg",
    )
    private val EXE_SUFFIX_RE = Regex("""(?i)[-_. ]*(win64|win32|shipping|wingdk|client|launcher|game|x64|x86|64bit|32bit)""")
    private val VERSION_TOKEN_RE = Regex("""(?i)\b(v?\d+([._]\d+)+|build[-_ ]?\d+|update[-_ ]?\d+)\b""")

    /**
     * Prefer the game's install-folder name (usually the real title) over the exe basename
     * when the exe looks like a UE-style shipping binary; then strip repack/suffix noise.
     */
    private fun cleanedFallbackName(exeFile: File): String? {
        val exeBase = exeFile.nameWithoutExtension
        val looksLikeEngineBinary = EXE_SUFFIX_RE.containsMatchIn(exeBase) ||
            exeBase.equals("game", true) || exeBase.equals("launcher", true) || exeBase.equals("start", true)
        val folder = exeFile.absoluteFile.parentFile?.name
        val raw = if (looksLikeEngineBinary && !folder.isNullOrBlank()) folder else exeBase
        return cleanName(raw)
    }

    /** Strip bracketed tags, repack-group names, engine/arch suffixes and version tokens; tidy separators. */
    private fun cleanName(raw: String): String? {
        var s = raw
        s = s.replace(Regex("""[\[(][^\])]*[\])]"""), " ") // (…) […]
        s = VERSION_TOKEN_RE.replace(s, " ")
        s = EXE_SUFFIX_RE.replace(s, " ")
        s = s.replace(Regex("""[._]+"""), " ")
        // Drop standalone repack-group words.
        s = s.split(Regex("""[\s-]+"""))
            .filter { it.isNotBlank() && it.lowercase() !in REPACK_TOKENS }
            .joinToString(" ")
        s = s.trim()
        return s.ifBlank { null }
    }

    // ── tiny text/format helpers (regex-based → no Android/org.json dependency, unit-testable) ──

    /** Read a small text file (guards against accidentally slurping a huge file). */
    private fun readSmallTextFile(f: File, maxBytes: Long = 1L * 1024 * 1024): String? = try {
        if (f.isFile && f.length() in 1..maxBytes) f.readText(Charsets.UTF_8) else null
    } catch (_: Throwable) {
        null
    }

    /** Value of a Valve KeyValues (.acf/.vdf) `"key"  "value"` pair, first match, case-insensitive key. */
    private fun vdfValue(text: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*"([^"]*)"""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()

    /** Value of a JSON `"key": "value"` pair, first match. Sufficient for the flat goggame .info files. */
    private fun jsonStringValue(text: String, key: String): String? =
        Regex(""""${Regex.escape(key)}"\s*:\s*"([^"\\]*(?:\\.[^"\\]*)*)"""").find(text)
            ?.groupValues?.getOrNull(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")?.trim()
}
