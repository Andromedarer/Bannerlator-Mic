package com.winlator.star.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// ─────────────────────────────────────────────────────────────────────────────
// Container glossary — a plain-language "what is all this?" compendium for the
// container editor. Newcomer-facing: short, friendly definitions of the terms a
// first-time user meets on the setup screen (wrappers, DXVK/VKD3D, Mali/Adreno,
// BCn, colours, glibc/bionic, …). Searchable. Self-contained so the shortcut
// editor (or a future setup-wizard tutorial) can reuse it verbatim.
// ─────────────────────────────────────────────────────────────────────────────

internal data class GlossaryEntry(val term: String, val definition: String)
internal data class GlossarySection(val title: String, val entries: List<GlossaryEntry>)

internal val CONTAINER_GLOSSARY: List<GlossarySection> = listOf(
    GlossarySection("Graphics translation (DirectX → Vulkan)", listOf(
        GlossaryEntry("DXVK",
            "Translates a game's DirectX 9/10/11 graphics into Vulkan — the language your phone's GPU actually speaks. It's the default and usually the fastest option."),
        GlossaryEntry("VKD3D",
            "The same idea as DXVK, but for DirectX 12 games. It needs a newer Vulkan setup, so not every wrapper or GPU can use it."),
        GlossaryEntry("WineD3D",
            "An older, slower translator that turns DirectX into OpenGL instead of Vulkan. Keep it as a fallback for games that misbehave on DXVK."),
        GlossaryEntry("VEGAS",
            "An alternative DXVK-style translator tuned for certain devices (Adreno-focused). Worth trying if DXVK gives you trouble on a specific game."),
        GlossaryEntry("DirectX (D3D9–12)",
            "The Windows graphics API games are built on — D3D9/10/11 are older, D3D12 is the newest. The wrappers above translate it into Vulkan for your phone."),
        GlossaryEntry("OpenGL",
            "An older cross-platform graphics standard. It's the 'Renderer' fallback, and what WineD3D and Zink target when Vulkan isn't used directly."),
        GlossaryEntry("Zink",
            "Translates OpenGL into Vulkan, so OpenGL-based games and tools still run on a phone GPU that only speaks Vulkan."),
    )),
    GlossarySection("The Windows layer", listOf(
        GlossaryEntry("Wine",
            "Runs Windows programs on Android without Windows itself — it translates a game's Windows system calls into Android/Linux ones on the fly."),
        GlossaryEntry("Proton",
            "Valve's gaming-focused version of Wine, with extra patches that make many games run better. Bannerlator ships Proton builds."),
        GlossaryEntry("Container",
            "A self-contained virtual Windows environment for a game — its own C: drive, settings and installed components. Think of it as one sandbox per game or per configuration."),
        GlossaryEntry("Wrapper (Graphics Driver)",
            "The Vulkan driver layer a container uses to talk to your GPU. Different wrappers suit different GPUs — this is where the Mali-vs-Adreno choice matters."),
    )),
    GlossarySection("Running x86 games on ARM", listOf(
        GlossaryEntry("FEXCore",
            "Translates a game's x86/x64 (Intel/AMD) instructions into ARM so they run on your phone's processor. Bannerlator's primary translator (arm64ec-focused)."),
        GlossaryEntry("box64",
            "The classic x86/x64-to-ARM instruction translator — an alternative engine to FEXCore, picked per container."),
        GlossaryEntry("arm64ec",
            "A build format that lets native ARM code and translated x86 code run together efficiently. Modern Proton builds here use it."),
    )),
    GlossarySection("GPUs & how they draw", listOf(
        GlossaryEntry("Vulkan",
            "The modern, low-level graphics standard your phone's GPU speaks. Every translator above ultimately outputs Vulkan."),
        GlossaryEntry("Mali vs Adreno",
            "The two main phone-GPU families. Adreno (Snapdragon) has the best support here. Mali (many MediaTek/Exynos phones) works, but needs extra help with compressed textures — that's what the BCn / wrapper settings are for."),
        GlossaryEntry("Turnip",
            "The open-source Vulkan driver for Adreno GPUs that Bannerlator uses for best performance and compatibility."),
        GlossaryEntry("BCn emulation",
            "PC games store textures in a compressed format (BCn / DXT / S3TC) that Adreno reads natively but Mali cannot. BCn emulation converts them on the fly so they display correctly. If it's off or incomplete on a Mali device, you get black textures in some areas."),
        GlossaryEntry("ASTC / ETC2",
            "Compressed-texture formats that Mali GPUs CAN read natively. BCn emulation converts a game's textures into one of these so a Mali phone can display them."),
        GlossaryEntry("Adrenotools",
            "A loader that lets Bannerlator use a custom GPU driver (like Turnip) instead of your phone's built-in one — usually for better speed and compatibility on Adreno."),
    )),
    GlossarySection("Picture settings", listOf(
        GlossaryEntry("Colors: RGBA vs BGRA",
            "The order of the red and blue colour channels. BGRA (the default) is correct for almost everything — only switch to RGBA if a game's colours look swapped (blue where red should be)."),
        GlossaryEntry("Render scale / supersampling",
            "Renders the game at a higher resolution and shrinks it for a sharper image (costs performance), or at a lower resolution for more FPS."),
        GlossaryEntry("Frame generation",
            "Inserts AI-generated in-between frames to make motion look smoother. It can add a little input latency and occasional artifacts, so it's optional."),
        GlossaryEntry("DXVK HUD / overlay",
            "An on-screen readout (FPS, frame times, GPU load, driver info) for diagnosing performance. Purely informational — it doesn't change the game."),
        GlossaryEntry("SurfaceFlinger (renderer)",
            "An experimental renderer that hands frames straight to Android's display system. Sometimes faster, but it can misbehave (or even reboot) some devices — Vulkan is the safe default."),
        GlossaryEntry("Shader stutter & cache",
            "The first time you play, the game compiles its shaders, which can cause brief stutters. DXVK's shader (state) cache saves them so later sessions run smoothly."),
    )),
    GlossarySection("Under the hood", listOf(
        GlossaryEntry("glibc vs bionic",
            "Two different C libraries. Android uses 'bionic'; desktop Linux uses 'glibc'. Bannerlator's Wine and drivers are built for bionic — a glibc build simply won't load, no matter how good it looks."),
        GlossaryEntry("Environment variables",
            "Advanced per-container switches (like DXVK_FRAME_RATE=60 or BCN_TRANSCODE_TO_ASTC=1). Most people never need them, but they let power users fine-tune a game."),
        GlossaryEntry("ESYNC / FSYNC",
            "Wine speed-ups that let a game's threads coordinate more efficiently. On by default — leave them on unless a specific game misbehaves."),
        GlossaryEntry("DLL overrides",
            "Tell Wine whether to use a game's own copy of a system file (native) or Wine's built-in version. A common fix for games that ship their own DLLs."),
        GlossaryEntry("Components",
            "Extra Windows runtimes some games need (Visual C++ redistributables, .NET, DirectX, etc.). Install them from a container's Components list if a game complains one is missing."),
    )),
    GlossarySection("Controls & audio", listOf(
        GlossaryEntry("XInput vs DInput",
            "The two ways Windows games read controllers. XInput is the modern Xbox-style standard; DInput is the older one. Match whichever a game expects."),
        GlossaryEntry("Exclusive input",
            "Hands your controller to one game exclusively so Android and the game don't fight over it — helps when inputs feel doubled or ignored."),
        GlossaryEntry("MIDI SoundFont",
            "A sound bank used to play a game's MIDI music. Only matters for older games that use MIDI — pick a SoundFont if their music is silent."),
    )),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerGlossarySheet(onDismiss: () -> Unit, initialQuery: String = "") {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cs = MaterialTheme.colorScheme
    // A per-field help icon opens the sheet pre-filtered to that term (initialQuery); the general
    // "What is all this?" button opens it unfiltered (initialQuery == "").
    var query by remember { mutableStateOf(initialQuery) }

    // Flatten + filter. A section is shown only if it has any matching entries.
    val q = query.trim()
    val sections = remember(q) {
        if (q.isEmpty()) CONTAINER_GLOSSARY
        else CONTAINER_GLOSSARY.mapNotNull { section ->
            val hits = section.entries.filter {
                it.term.contains(q, ignoreCase = true) || it.definition.contains(q, ignoreCase = true)
            }
            if (hits.isEmpty()) null else section.copy(entries = hits)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "What is all this?",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Plain-language explanations of the setup options. Tap a term to read what it does.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search terms") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.padding(top = 8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sections.forEach { section ->
                    item(key = "sec:${section.title}") {
                        Text(
                            section.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = cs.primary,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                        )
                    }
                    items(section.entries, key = { "${section.title}:${it.term}" }) { entry ->
                        Surface(
                            color = cs.surfaceVariant.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(entry.term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    entry.definition,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = cs.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 3.dp)
                                )
                            }
                        }
                    }
                }
                if (sections.isEmpty()) {
                    item {
                        Text(
                            "No terms match \"$q\".",
                            style = MaterialTheme.typography.bodySmall,
                            color = cs.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                item { Spacer(Modifier.padding(bottom = 16.dp)) }
            }
        }
    }
}
