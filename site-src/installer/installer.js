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

async function preflight() {
    setStep(2, "active");
    log("Checking the phone…");

    // The same line tools/provision-adb.sh reads, and for the same reason: it is
    // the one that was checked against real output on hardware. Counting
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
        return false;
    }
    log("No accounts on the phone.", "ok");

    const policy = await adb.shell("dumpsys device_policy | grep 'Device Owner:'");
    if (policy.includes("Device Owner:")) {
        setStep(2, "error");
        fail("This phone already has a device owner. Remove it from inside drawbridge first.");
        return false;
    }
    log("No device owner yet.", "ok");

    const model = clean(await adb.shell("getprop ro.product.model"));
    const release = clean(await adb.shell("getprop ro.build.version.release"));
    log(`${model}, Android ${release}.`);
    setStep(2, "done");
    return true;
}

async function fetchApk() {
    setStep(3, "active");
    log(`Downloading drawbridge (${ui.apkName})…`);
    const response = await fetch(ui.apkUrl);
    if (!response.ok) throw new AdbError(`Could not download the app: HTTP ${response.status}`);
    const buffer = await response.arrayBuffer();

    const digest = await sha256Hex(buffer);
    if (digest !== ui.apkSha256) {
        setStep(3, "error");
        throw new AdbError(
            "The downloaded app does not match its published checksum, so it will not be " +
                "installed. Expected " + ui.apkSha256 + ", got " + digest + ".",
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

        if (!(await preflight())) return;
        const apk = await fetchApk();
        if (!(await installApk(apk))) return;
        if (!(await setDeviceOwner())) return;

        ui.status.textContent = "Done.";
        ui.status.className = "installer-status installer-status--ok";
        ui.done.hidden = false;
        log("Finished. Follow the steps below on the phone itself.", "ok");
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
    ui.unsupported = el("installer-unsupported");
    ui.apkUrl = config.apkUrl;
    ui.apkSha256 = config.apkSha256;
    ui.apkName = config.apkName;

    if (!("usb" in navigator)) {
        ui.unsupported.hidden = false;
        ui.button.disabled = true;
        return;
    }
    ui.button.addEventListener("click", run);
}
