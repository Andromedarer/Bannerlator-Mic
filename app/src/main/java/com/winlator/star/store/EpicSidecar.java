/*
 * Epic Online Services sidecar / deployment-ID + exchange-code fetcher.
 *
 * Credits: Java port of EpicManager.fetchDeploymentId from The GameNative Team
 * (https://github.com/utkarshdalal/GameNative), commit cbea7f7. The
 * fetchExchangeCodeSync method follows the same auth flow used by Legendary /
 * GameNative / Epic Launcher itself: GET account-public-service-prod03
 * .ol.epicgames.com/account/api/oauth/exchange with the user's bearer token
 * and consume the returned short-lived "code" as -AUTH_PASSWORD.
 *
 * Massive thanks to utkarshdalal and the GameNative contributors.
 *
 * Reference: https://github.com/utkarshdalal/GameNative/commit/cbea7f70be46e6f4a99a7e92db13c9b96add9c1c
 */
package com.winlator.star.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Fetches and caches the EOS deployment ID for an Epic game. The deployment ID is
 * required for modern EOS-integrated titles (passed as {@code -epicdeploymentid=<id>}
 * on the Wine launch command). Without it, games like "Deliver At All Costs" fail
 * with "Failed to connect to the Epic Launcher".
 *
 * Java port of GameNative upstream {@code EpicManager.fetchDeploymentId}
 * (commit cbea7f7).
 *
 * Cache lives in {@code bh_epic_prefs} as:
 *   {@code epic_deployment_<appName>}      = deploymentId or "" for negative result
 *   {@code epic_deployment_at_<appName>}   = ms timestamp of cache write
 *
 * Cache TTL = 30 days. Negative results are cached too (most titles don't have a
 * sidecar; we don't want to re-probe every launch).
 */
public final class EpicSidecar {

    private static final String TAG = "BH_EPIC_SIDECAR";
    private static final long   TTL_MS = 30L * 24 * 60 * 60 * 1000;
    private static final String UA =
            "EpicGamesLauncher/14.4.1-22989849+++Portal+Release-Live Windows/10.0.19041.1.256.64bit";
    private static final String LAUNCHER_API_BASE =
            "https://launcher-public-service-prod06.ol.epicgames.com";

    private EpicSidecar() {}

    /**
     * Returns a cached deployment ID synchronously. Empty string if cache is
     * unset, expired, or the game has no sidecar.
     */
    public static String getCachedDeploymentId(Context ctx, String appName) {
        SharedPreferences sp = ctx.getSharedPreferences("bh_epic_prefs", 0);
        long writtenAt = sp.getLong("epic_deployment_at_" + appName, 0L);
        if (writtenAt <= 0) return "";
        long age = System.currentTimeMillis() - writtenAt;
        if (age > TTL_MS) return ""; // stale → caller should refresh
        return sp.getString("epic_deployment_" + appName, "");
    }

    /**
     * Fire-and-forget refresh on a background thread. Writes the result (positive or
     * negative) to {@code bh_epic_prefs} so subsequent launches read from cache.
     */
    public static void refreshAsync(Context ctx, String namespace, String catalogItemId, String appName) {
        if (namespace == null || namespace.isEmpty()
                || catalogItemId == null || catalogItemId.isEmpty()
                || appName == null || appName.isEmpty()) return;
        new Thread(() -> {
            try {
                String token = EpicCredentialStore.getValidAccessToken(ctx);
                if (token == null) {
                    Log.w(TAG, "refresh: no Epic access token, skipping");
                    return;
                }
                String deploymentId = fetch(token, namespace, catalogItemId, appName);
                ctx.getSharedPreferences("bh_epic_prefs", 0).edit()
                        .putString("epic_deployment_" + appName, deploymentId != null ? deploymentId : "")
                        .putLong("epic_deployment_at_" + appName, System.currentTimeMillis())
                        .apply();
                Log.i(TAG, "refresh(" + appName + ") = " + (deploymentId == null || deploymentId.isEmpty()
                        ? "<none>" : deploymentId));
            } catch (Exception e) {
                Log.w(TAG, "refresh failed for " + appName, e);
            }
        }, "bh-epic-deployment-" + appName).start();
    }

    /**
     * Synchronously fetch a fresh Epic exchange code. Required by modern EOS-integrated
     * games for authentication — passed as {@code -AUTH_PASSWORD=<code>} on the launch
     * command. Codes expire in ~5 minutes; do NOT cache.
     *
     * Endpoint: GET https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/exchange
     * with {@code Authorization: bearer <accessToken>}.
     * Response body: {@code {"expiresInSeconds":300,"code":"<exchange_code>","creatingClientId":"..."}}
     *
     * Returns the code on success, null on any failure (caller skips AUTH args).
     */
    public static String fetchExchangeCodeSync(Context ctx) {
        String token = EpicCredentialStore.getValidAccessToken(ctx);
        if (token == null) {
            Log.w(TAG, "fetchExchangeCode: no Epic access token");
            return null;
        }
        HttpURLConnection conn = null;
        try {
            String url = "https://account-public-service-prod03.ol.epicgames.com/account/api/oauth/exchange";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "bearer " + token);
            conn.setRequestProperty("User-Agent", UA);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "fetchExchangeCode: HTTP " + code);
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }

            String exchangeCode = new JSONObject(body.toString()).optString("code", "");
            if (exchangeCode.isEmpty()) {
                Log.w(TAG, "fetchExchangeCode: empty code in response");
                return null;
            }
            Log.i(TAG, "fetchExchangeCode: OK (length=" + exchangeCode.length() + ")");
            return exchangeCode;
        } catch (Exception e) {
            Log.w(TAG, "fetchExchangeCode failed", e);
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * Epic ecommerce host for ownership-verification tokens (Denuvo / EOS Phase 2).
     * Confirmed against GameNative's {@code EpicConstants.ECOMMERCE_HOST}.
     */
    private static final String ECOMMERCE_HOST =
            "ecommerceintegration-public-service-ecomprod02.ol.epicgames.com";

    /**
     * Synchronously mint a signed ownership-verification token for a Denuvo-protected
     * EOS game (Epic EOS Phase 2). The title validates this token to confirm the
     * launching Epic account genuinely owns the game before its Denuvo anti-tamper
     * will run — this is real-Epic ownership, NOT a DRM bypass.
     *
     * Endpoint (confirmed from GameNative {@code EpicAuthClient.getOwnershipToken}):
     *   POST https://{ECOMMERCE_HOST}/ecommerceintegration/api/public/platforms/EPIC/
     *        identities/&lt;accountId&gt;/ownershipToken
     *   Header:  {@code Authorization: Bearer <accessToken>}
     *   Body:    form-urlencoded {@code nsCatalogItemId=<namespace>:<catalogItemId>}
     *   Response body IS the token — Epic returns the raw signed-token bytes directly
     *   (no JSON envelope; GameNative reads {@code response.body.bytes()}). Those exact
     *   bytes are what must land in the {@code .ovt} file passed via {@code -epicovt}.
     *
     * ~8s timeout. Returns the raw token bytes on success, or null on any failure
     * (caller then skips the ownership-token args — the launch is unchanged).
     */
    public static byte[] fetchOwnershipTokenSync(Context ctx, String accountId,
                                                 String namespace, String catalogItemId) {
        if (accountId == null || accountId.isEmpty()
                || namespace == null || namespace.isEmpty()
                || catalogItemId == null || catalogItemId.isEmpty()) {
            Log.w(TAG, "fetchOwnershipToken: missing accountId/namespace/catalogItemId");
            return null;
        }
        String token = EpicCredentialStore.getValidAccessToken(ctx);
        if (token == null) {
            Log.w(TAG, "fetchOwnershipToken: no Epic access token");
            return null;
        }
        HttpURLConnection conn = null;
        try {
            String url = "https://" + ECOMMERCE_HOST
                    + "/ecommerceintegration/api/public/platforms/EPIC/identities/"
                    + accountId + "/ownershipToken";
            String form = "nsCatalogItemId="
                    + URLEncoder.encode(namespace + ":" + catalogItemId, "UTF-8");
            byte[] formBytes = form.getBytes(StandardCharsets.UTF_8);

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("Content-Length", Integer.toString(formBytes.length));
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(formBytes);
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "fetchOwnershipToken: HTTP " + code);
                return null;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (InputStream is = conn.getInputStream()) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = is.read(chunk)) >= 0) bos.write(chunk, 0, n);
            }
            byte[] tokenBytes = bos.toByteArray();
            if (tokenBytes.length == 0) {
                Log.w(TAG, "fetchOwnershipToken: empty token response");
                return null;
            }
            Log.i(TAG, "fetchOwnershipToken: OK (" + tokenBytes.length + " bytes)");
            return tokenBytes;
        } catch (Exception e) {
            Log.w(TAG, "fetchOwnershipToken failed", e);
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * Synchronous fetch from Epic's manifest API. Returns the deploymentId, or empty
     * string if the game has no sidecar. Returns null on transient/network failure
     * (caller can choose to retry).
     */
    private static String fetch(String accessToken, String namespace, String catalogItemId, String appName)
            throws Exception {
        String url = LAUNCHER_API_BASE
                + "/launcher/api/public/assets/v2/platform/Windows/namespace/" + namespace
                + "/catalogItem/" + catalogItemId
                + "/app/" + appName
                + "/label/Live";

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("User-Agent", UA);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "manifest API " + code + " for " + appName);
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) body.append(line);
            }

            JSONObject root = new JSONObject(body.toString());
            JSONArray elements = root.optJSONArray("elements");
            if (elements == null || elements.length() == 0) return "";

            JSONObject sidecar = elements.getJSONObject(0).optJSONObject("sidecar");
            if (sidecar == null) return ""; // negative result — most games

            String configStr = sidecar.optString("config", "");
            if (configStr.isEmpty()) return "";

            try {
                String deploymentId = new JSONObject(configStr).optString("deploymentId", "");
                return deploymentId; // may be empty if the sidecar config has no deploymentId
            } catch (Exception parseE) {
                Log.w(TAG, "sidecar.config parse failed for " + appName + " (likely transient)", parseE);
                return null;
            }
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }
}
