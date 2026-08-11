// The drawbridge-specific half of the browser installer: the same sequence
// tools/provision-adb.sh runs, driven over WebUSB instead of a shell.
//
// It must keep the one promise that script makes. `verifier_verify_adb_installs`
// is switched off only for the length of the APK push, and it is put back on
// every exit path — success, failure, and a device unplugged mid-install. A page
// that leaves a phone with install verification disabled would be worse than a
// page that does nothing.

import { Adb, AdbError, loadOrCreateKey } from "./adb.js";

const ADMIN = "app.drawbridge.dpc/app.drawbridge.dpc.admin.DrawbridgeDeviceAdminReceiver";
const PACKAGE = "app.drawbridge.dpc";

const ui = {};
let adb = null;
let originalVerifier = null;

function el(id) {
    return document.getElementById(id);
}

function log(message, kind = "") {
    const line = document.createElement("div");
    line.className = "log-line" + (kind ? " log-line--" + kind : "");
    line.textContent = message;
    ui.log.appendChild(line);
    ui.log.scrollTop = ui.log.scrollHeight;
}

function setStep(n, state) {
    const step = el("step-" + n);
    if (step) step.dataset.state = state;
}

function fail(message) {
    log(message, "error");
    ui.status.textContent = "Stopped.";
    ui.status.className = "installer-status installer-status--error";
}

async function sha256Hex(buffer) {
    const digest = await crypto.subtle.digest("SHA-256", buffer);
    return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Reads a single-line property, tolerating the trailing \r adb shell adds. */
function clean(text) {
    return text.replace(/\r/g, "").trim();
}

async function readVerifier() {
    const value = clean(await adb.shell("settings get global verifier_verify_adb_installs"));
    return value === "" ? "null" : value;
}

/**
 * Puts the verifier back exactly as it was found.
 *
 * "null" means the row was never written and the platform default applies, so
 * it is restored by deleting rather than by writing a value — writing 1 would
 * leave the phone in a state it was not in before.
 */
async function restoreVerifier() {
    if (originalVerifier === null) return true;
    if (originalVerifier === "null") {
        await adb.shell("settings delete global verifier_verify_adb_installs");
    } else {
        await adb.shell(`settings put global verifier_verify_adb_installs ${originalVerifier}`);
    }
    const now = await readVerifier();
    if (now !== originalVerifier) {
        fail(
            `Could not restore install verification — it reads "${now}" and was ` +
                `"${originalVerifier}". Do not hand this phone over until it is put back:\n` +
                `adb shell settings put global verifier_verify_adb_installs 1`,
        );
        return false;
    }
    log(`Install verification restored (${originalVerifier}).`, "ok");
    originalVerifier = null;
    return true;
}

/**
 * Works out whether this is a first provision or an update, and refuses
 * anything else.
 *
 * The two are mutually exclusive on the device itself — drawbridge owns the
 * phone or nothing does — so asking the person which one they meant would only
 * be a chance to get it wrong. What the page owes them instead is saying which
 * it picked, before it changes anything.
 *
 * Returns "provision", "update", or null if the run cannot go ahead.
 */
async function preflight() {
    setStep(2, "active");
    log("Checking the phone…");

    const model = clean(await adb.shell("getprop ro.product.model"));
    const release = clean(await adb.shell("getprop ro.build.version.release"));
    log(`${model}, Android ${release}.`);

    const owner = await adb.shell("dumpsys device_policy | grep -A1 'Device Owner:'");
    if (owner.includes("Device Owner:")) {
        if (!owner.includes(PACKAGE)) {
            setStep(2, "error");
            fail(
                "This phone is already managed by something other than drawbridge, so it " +
                    "cannot be provisioned or updated from here.",
            );
            return null;
        }
        const installed = clean(
            (await adb.shell(`dumpsys package ${PACKAGE} | grep -m1 versionName`)),
        ).replace(/^versionName=/, "");
        log(ui.text.updateDetected.replace("{version}", installed || "?"), "prompt");
        setStep(2, "done");
        setStep(5, "skipped");
        return "update";
    }

    // Only provisioning cares about accounts: it is `dpm set-device-owner` that
    // Android refuses while any account is present, not the install. An update
    // runs on a phone that already has whatever account its owner chose.
    //
    // This reads the same line tools/provision-adb.sh does, and for the same
    // reason: it is the one checked against real output on hardware. Counting
    // "Account {" entries instead would be one dumpsys format change away from
    // refusing a phone that is actually clean.
    const accounts = clean(await adb.shell("dumpsys account | grep -m1 'Accounts: '"));
    const count = parseInt((accounts.match(/Accounts:\s*(\d+)/) || [])[1], 10);
    if (Number.isFinite(count) && count > 0) {
        setStep(2, "error");
        fail(
            `This phone has ${count} account${count === 1 ? "" : "s"} on it, so Android will not ` +
                "hand over device ownership. Remove every account in Settings → Passwords, " +
                "passkeys & accounts, then run this again. You sign back in afterwards, and " +
                "nothing else is erased.",
        );
        return null;
    }
    log("No accounts on the phone, and no device owner yet.", "ok");
    setStep(2, "done");
    return "provision";
}

async function fetchApk() {
    setStep(3, "active");
    log(`Downloading drawbridge (${ui.apkName})…`);
    // `cache: "no-cache"` revalidates rather than trusting whatever is stored:
    // this page and the APK are separate URLs, and the checksum below only means
    // anything if they came from the same release.
    const response = await fetch(ui.apkUrl, { cache: "no-cache" });
    if (!response.ok) {
        setStep(3, "error");
        // The APK is named after its own hash, so a 404 means this page is older
        // than the release it is pointing at — not that anything is broken.
        throw new AdbError(
            response.status === 404
                ? "This page is out of date: the version it was built for has been " +
                      "replaced. Reload the page and try again."
                : `Could not download the app: HTTP ${response.status}`,
        );
    }
    const buffer = await response.arrayBuffer();

    const digest = await sha256Hex(buffer);
    if (digest !== ui.apkSha256) {
        setStep(3, "error");
        throw new AdbError(
            "The downloaded app does not match the checksum this page expects, so it will " +
                "not be installed.\n\nReload the page and try again — if this page was open " +
                "before a new version was published, that is the cause.\n\nExpected " +
                ui.apkSha256 +
                "\nGot      " +
                digest,
        );
    }
    log(`Checksum verified (${(buffer.byteLength / 1048576).toFixed(1)} MB).`, "ok");
    setStep(3, "done");
    return new Uint8Array(buffer);
}

async function installApk(apk) {
    setStep(4, "active");

    originalVerifier = await readVerifier();
    log(`Pausing install verification (was: ${originalVerifier}).`);
    await adb.shell("settings put global verifier_verify_adb_installs 0");

    log("Installing…");
    const stream = await adb.openStream(`exec:cmd package install -S ${apk.length}`);
    await stream.write(apk);
    const result = await stream.readAll();

    if (!/Success/i.test(result)) {
        setStep(4, "error");
        await restoreVerifier();
        throw new AdbError("The install failed: " + clean(result));
    }
    log("drawbridge installed.", "ok");

    if (!(await restoreVerifier())) {
        setStep(4, "error");
        return false;
    }
    setStep(4, "done");
    return true;
}

async function setDeviceOwner() {
    setStep(5, "active");
    log("Making drawbridge the owner of the phone…");
    const result = await adb.shell(`dpm set-device-owner ${ADMIN}`);
    if (!/Success/i.test(result)) {
        setStep(5, "error");
        throw new AdbError("Could not grant device ownership: " + clean(result));
    }
    const check = await adb.shell("dumpsys device_policy | grep 'Device Owner:'");
    if (!check.includes("Device Owner:")) {
        setStep(5, "error");
        throw new AdbError("The phone did not report a device owner afterwards.");
    }
    log("drawbridge is now the device owner.", "ok");
    setStep(5, "done");
    return true;
}

async function run() {
    ui.button.disabled = true;
    ui.log.hidden = false;
    ui.status.textContent = "Working…";
    ui.status.className = "installer-status";

    try {
        setStep(1, "active");
        log("Asking for the phone…");
        try {
            adb = await Adb.requestDevice();
        } catch (error) {
            if (error instanceof AdbError) setStep(1, "error");
            throw error;
        }
        const key = await loadOrCreateKey(window.localStorage);
        await adb.authenticate(key, () =>
            log("Accept the “Allow USB debugging” prompt on the phone.", "prompt"),
        );
        log("Connected.", "ok");
        setStep(1, "done");

        const mode = await preflight();
        if (!mode) return;
        const apk = await fetchApk();
        if (!(await installApk(apk))) return;
        if (mode === "provision" && !(await setDeviceOwner())) return;

        const installed = clean(
            await adb.shell(`dumpsys package ${PACKAGE} | grep -m1 versionName`),
        ).replace(/^versionName=/, "");

        ui.status.textContent = "Done.";
        ui.status.className = "installer-status installer-status--ok";
        (mode === "update" ? ui.doneUpdate : ui.done).hidden = false;
        log(`drawbridge on this phone is now ${installed || "installed"}.`, "ok");
        log(
            mode === "update"
                ? "Finished. Lock drawbridge again on the phone."
                : "Finished. Follow the steps below on the phone itself.",
            "ok",
        );
    } catch (error) {
        if (error && error.name === "NotFoundError") {
            log("No phone was chosen.");
            ui.status.textContent = "";
            setStep(1, "");
        } else {
            // Whatever went wrong, the verifier must not be left off.
            try {
                if (adb && originalVerifier !== null) await restoreVerifier();
            } catch {
                fail(
                    "The phone disconnected before install verification could be restored. " +
                        "Reconnect it and run: adb shell settings put global " +
                        "verifier_verify_adb_installs 1",
                );
            }
            fail(error && error.message ? error.message : String(error));
        }
    } finally {
        if (adb) {
            await adb.close();
            adb = null;
        }
        ui.button.disabled = false;
    }
}

export function init(config) {
    ui.button = el("installer-start");
    ui.log = el("installer-log");
    ui.status = el("installer-status");
    ui.done = el("installer-done");
    ui.doneUpdate = el("installer-done-update");
    ui.unsupported = el("installer-unsupported");
    ui.apkUrl = config.apkUrl;
    ui.apkSha256 = config.apkSha256;
    ui.apkName = config.apkName;
    ui.text = config.text;

    if (!("usb" in navigator)) {
        ui.unsupported.hidden = false;
        ui.button.disabled = true;
        return;
    }
    ui.button.addEventListener("click", run);
}
