/*
 * Epic Online Services launch-argument injection for Bannerlator.
 *
 * Credits: Java port of the EOS launch-args work by The GameNative Team
 * (https://github.com/utkarshdalal/GameNative). Based on PR #1286 / commit
 * cbea7f7 ("Feat/eos overlay utkarsh"), which introduced -EpicPortal,
 * -epicusername, -epicuserid, -epicsandboxid, -epiclocale, -epicdeploymentid
 * plus the -AUTH_LOGIN / -AUTH_PASSWORD / -AUTH_TYPE exchange-code triple.
 * Massive thanks to utkarshdalal and the GameNative contributors.
 *
 * This file ships Phase 1 (online auth handshake). Phase 2 (ownership token
 * -epicovt) and Phase 3 (in-game EOS overlay UI for friends / notifications /
 * achievements) are still pending.
 *
 * Reference: https://github.com/utkarshdalal/GameNative/commit/cbea7f70be46e6f4a99a7e92db13c9b96add9c1c
 */
package com.winlator.star.store;

import android.content.Context;
import android.util.Log;

import com.winlator.star.container.Shortcut;

/**
 * Builds the Epic Online Services launch-argument string that is appended to the
 * Wine command line for games installed via Bannerlator's Epic store integration.
 *
 * Phase 1 of EOS support: enables online auth so EOS-integrated games can
 * connect to the real Epic Online Services (multiplayer, friends, achievements)
 * against the game's own EOSSDK and the user's logged-in Epic account. This is
 * NOT an emulator — the args hand the title a real, freshly-minted exchange code.
 *
 * Per-game scoped via the shortcut's [Extra Data] block:
 *   {@code storeSource=epic}, {@code epicAppName}, {@code epicSandboxId},
 *   {@code epicCatalogId}, {@code epicEos} (1/0 toggle).
 *
 * Lookup chain:
 *   1. Read appName / sandboxId (namespace) / catalogId from the shortcut extras.
 *   2. Read displayName + accountId from {@link EpicCredentialStore}.
 *   3. Read cached deploymentId via {@link EpicSidecar#getCachedDeploymentId}.
 *   4. If the deploymentId cache is empty/stale, fire an async refresh — next launch will have it.
 *   5. Fetch a FRESH exchange code synchronously and emit the AUTH triple.
 *
 * If any required lookup fails (missing extras, no login, etc.) the method
 * silently returns "" — a no-op that leaves the launch command unchanged.
 * Pre-existing Epic installs (no shortcut extras stamped) therefore keep their
 * existing behaviour.
 */
public final class EpicLaunchArgs {

    private static final String TAG = "BH_EPIC_LAUNCH";

    private EpicLaunchArgs() {}

    /**
     * Builds a space-joined Epic launch-argument string for {@code shortcut}, or
     * "" if this is not an EOS-enabled Epic shortcut / any lookup fails.
     *
     * Runs synchronous network fetches (exchange code, ~8s timeout); callers MUST
     * invoke this off the main thread — it is called from the background launch
     * worker in XServerDisplayActivity.
     */
    public static String buildArgString(Context ctx, Shortcut shortcut) {
        if (ctx == null || shortcut == null) return "";
        try {
            String appName   = shortcut.getExtra("epicAppName", "");
            String namespace = shortcut.getExtra("epicSandboxId", "");
            String catalogId = shortcut.getExtra("epicCatalogId", "");

            // Backfill guard: pre-existing Epic installs won't have these extras
            // stamped on the shortcut. Silent no-op rather than crash the launch.
            if (appName.isEmpty() || namespace.isEmpty()) {
                Log.w(TAG, "missing epicAppName/epicSandboxId on shortcut; skipping Epic launch args");
                return "";
            }

            // Identity from EpicCredentialStore (same store the Epic UI uses).
            EpicCredentialStore.Credentials creds = EpicCredentialStore.load(ctx);
            String displayName = (creds != null && creds.displayName != null && !creds.displayName.isEmpty())
                    ? creds.displayName : "EpicUser";
            String accountId   = (creds != null && creds.accountId != null && !creds.accountId.isEmpty())
                    ? creds.accountId : "0";

            // deploymentId from cache; lazy async refresh on miss (affects NEXT launch).
            String deploymentId = EpicSidecar.getCachedDeploymentId(ctx, appName);
            if (deploymentId.isEmpty()) {
                EpicSidecar.refreshAsync(ctx, namespace, catalogId, appName);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("-EpicPortal");
            // -epicusername may contain spaces → quote it. Other IDs are ASCII-safe.
            sb.append(" -epicusername=\"").append(sanitize(displayName)).append("\"");
            sb.append(" -epicuserid=").append(sanitize(accountId));
            sb.append(" -epicsandboxid=").append(sanitize(namespace));
            sb.append(" -epiclocale=en");
            if (!deploymentId.isEmpty()) {
                sb.append(" -epicdeploymentid=").append(sanitize(deploymentId));
            }

            // Fetch a FRESH exchange code (expires ~5min) and append the AUTH triple.
            // Without this, modern EOS-integrated games show "No exchange code was
            // found, please launch from the Epic Games Launcher".
            String exchangeCode = EpicSidecar.fetchExchangeCodeSync(ctx);
            if (exchangeCode != null && !exchangeCode.isEmpty()) {
                sb.append(" -AUTH_LOGIN=unused");
                sb.append(" -AUTH_PASSWORD=").append(sanitize(exchangeCode));
                sb.append(" -AUTH_TYPE=exchangecode");
            }

            Log.i(TAG, "built Epic launch args for " + appName
                    + " (deploymentId=" + (deploymentId.isEmpty() ? "<none>" : deploymentId)
                    + ", exchangeCode=" + (exchangeCode == null || exchangeCode.isEmpty() ? "<missing>" : "<present>")
                    + ")");
            return sb.toString();
        } catch (Throwable t) {
            // Defensive: never let a bug here break game launches.
            Log.w(TAG, "buildArgString failed", t);
            return "";
        }
    }

    /**
     * Strip newlines / null bytes that could corrupt the Wine command line. Epic
     * IDs are plain ASCII; display names may contain spaces (handled by quoting
     * -epicusername) but must not contain control characters.
     */
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\n", "").replace("\r", "").replace("\0", "");
    }
}
