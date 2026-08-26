// A minimal ADB client that speaks to a phone over WebUSB.
//
// Why this exists rather than a library: the website is static, has no build
// step, and makes no third-party requests. Pulling in a bundler and an npm
// dependency tree to ship one page would cost all three. What is needed here is
// also much less than a general ADB client — connect, run a few shell commands,
// stream one 3 MB APK — so it is written out.
//
// The protocol is four things:
//
//  1. Packets are a 24-byte header of six little-endian uint32s (command, arg0,
//     arg1, length, checksum, magic) followed by a payload. `magic` is the
//     command XOR 0xffffffff, and the checksum is the sum of the payload bytes.
//  2. The host opens with CNXN. The device answers AUTH, and the host proves
//     itself with an RSA key.
//  3. Everything after that is streams: OPEN a service, then WRTE/OKAY back and
//     forth until CLSE. Every WRTE received must be answered with OKAY or the
//     device stops sending.
//  4. Services are strings. `shell:cmd` runs a command; `exec:cmd` does the same
//     without a pty, which is what you want when the payload is binary.
//
// Nothing here is specific to drawbridge; installer.js is.

const A_CNXN = 0x4e584e43;
const A_AUTH = 0x48545541;
const A_OPEN = 0x4e45504f;
const A_OKAY = 0x59414b4f;
const A_CLSE = 0x45534c43;
const A_WRTE = 0x45545257;

const AUTH_TOKEN = 1;
const AUTH_SIGNATURE = 2;
const AUTH_RSAPUBLICKEY = 3;

const VERSION = 0x01000001;
const MAX_PAYLOAD = 256 * 1024;

// Android's adb interface is identified by class/subclass/protocol rather than
// by vendor id, which is what makes a vendor-agnostic installer possible at all.
export const ADB_INTERFACE = { classCode: 0xff, subclassCode: 0x42, protocol: 1 };

const te = new TextEncoder();
const td = new TextDecoder();

// --- RSA keys, in the shape Android wants -----------------------------------

const MODULUS_BITS = 2048;
const MODULUS_BYTES = MODULUS_BITS / 8;
const MODULUS_WORDS = MODULUS_BYTES / 4;

function bytesToBigIntBE(bytes) {
    let n = 0n;
    for (const b of bytes) n = (n << 8n) | BigInt(b);
    return n;
}

function bigIntToBytesLE(value, length) {
    const out = new Uint8Array(length);
    let v = value;
    for (let i = 0; i < length; i++) {
        out[i] = Number(v & 0xffn);
        v >>= 8n;
    }
    return out;
}

function b64urlToBytes(s) {
    const b64 = s.replace(/-/g, "+").replace(/_/g, "/");
    const bin = atob(b64.padEnd(Math.ceil(b64.length / 4) * 4, "="));
    return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}

export function bytesToBase64(bytes) {
    let s = "";
    for (let i = 0; i < bytes.length; i += 0x8000) {
        s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(s);
}

function modInverse(a, m) {
    // Extended Euclid. Only ever called with m = 2^32, but written generally
    // because a subtly wrong inverse here produces a key the device rejects
    // with no diagnostic at all.
    let [old_r, r] = [a % m, m];
    let [old_s, s] = [1n, 0n];
    while (r !== 0n) {
        const q = old_r / r;
        [old_r, r] = [r, old_r - q * r];
        [old_s, s] = [s, old_s - q * s];
    }
    return ((old_s % m) + m) % m;
}

/**
 * Encodes an RSA public key as adb's `RSAPublicKey` struct, base64'd.
 *
 * 524 bytes: modulus length in words, n0inv, the modulus, R^2 mod n, and the
 * exponent — with both bignums as little-endian bytes. `n0inv` and `rr` are
 * Montgomery precomputation that the device's own decoder mostly ignores, but
 * they are part of the wire format and are cheap to get right.
 */
export function encodeAndroidPublicKey(modulus, exponent) {
    const buf = new ArrayBuffer(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4);
    const view = new DataView(buf);
    const bytes = new Uint8Array(buf);

    view.setUint32(0, MODULUS_WORDS, true);

    const r32 = 1n << 32n;
    const n0inv = r32 - modInverse(modulus % r32, r32);
    view.setUint32(4, Number(n0inv), true);

    bytes.set(bigIntToBytesLE(modulus, MODULUS_BYTES), 8);

    const r = 1n << BigInt(MODULUS_BITS);
    const rr = (r * r) % modulus;
    bytes.set(bigIntToBytesLE(rr, MODULUS_BYTES), 8 + MODULUS_BYTES);

    view.setUint32(8 + 2 * MODULUS_BYTES, Number(exponent), true);
    return bytesToBase64(bytes);
}

/**
 * Signs adb's 20-byte auth token.
 *
 * The token is signed *as though it were already a SHA-1 digest*, so WebCrypto's
 * RSASSA-PKCS1-v1_5 cannot be used — it would hash the token first. What is left
 * is doing the PKCS#1 v1.5 padding by hand and one modular exponentiation.
 */
export function signToken(token, d, n) {
    const SHA1_DIGEST_INFO = [
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
        0x05, 0x00, 0x04, 0x14,
    ];
    const tail = [...SHA1_DIGEST_INFO, ...token];
    const padLength = MODULUS_BYTES - tail.length - 3;
    const block = [0x00, 0x01, ...new Array(padLength).fill(0xff), 0x00, ...tail];
    const sig = modPow(bytesToBigIntBE(Uint8Array.from(block)), d, n);
    // Big-endian on the wire, which is the opposite of the public key blob.
    return bigIntToBytesLE(sig, MODULUS_BYTES).reverse();
}

function modPow(base, exp, mod) {
    let result = 1n;
    let b = base % mod;
    let e = exp;
    while (e > 0n) {
        if (e & 1n) result = (result * b) % mod;
        b = (b * b) % mod;
        e >>= 1n;
    }
    return result;
}

/**
 * A keypair for this browser, kept so the phone's "always allow" actually holds.
 *
 * Without persistence every visit is a new identity and the user is prompted
 * again, which is survivable — but it also means a phone that has been told to
 * trust this computer stops being reachable the moment the tab is reloaded
 * mid-install.
 */
export async function loadOrCreateKey(storage) {
    const stored = storage && storage.getItem("drawbridge-adb-key");
    if (stored) {
        try {
            return jwkToKey(JSON.parse(stored));
        } catch {
            /* fall through and mint a new one */
        }
    }
    const pair = await crypto.subtle.generateKey(
        {
            name: "RSASSA-PKCS1-v1_5",
            modulusLength: MODULUS_BITS,
            publicExponent: new Uint8Array([0x01, 0x00, 0x01]),
            hash: "SHA-1",
        },
        true,
        ["sign", "verify"],
    );
    const jwk = await crypto.subtle.exportKey("jwk", pair.privateKey);
    if (storage) {
        try {
            storage.setItem("drawbridge-adb-key", JSON.stringify(jwk));
        } catch {
            /* private browsing; a fresh key each time still works */
        }
    }
    return jwkToKey(jwk);
}

function jwkToKey(jwk) {
    const n = bytesToBigIntBE(b64urlToBytes(jwk.n));
    const e = bytesToBigIntBE(b64urlToBytes(jwk.e));
    const d = bytesToBigIntBE(b64urlToBytes(jwk.d));
    return { n, e, d, publicKeyBlob: encodeAndroidPublicKey(n, e) };
}

// --- packets ----------------------------------------------------------------

function checksum(payload) {
    let sum = 0;
    for (const b of payload) sum = (sum + b) >>> 0;
    return sum;
}

function encodePacket(command, arg0, arg1, payload) {
    const header = new ArrayBuffer(24);
    const view = new DataView(header);
    view.setUint32(0, command, true);
    view.setUint32(4, arg0, true);
    view.setUint32(8, arg1, true);
    view.setUint32(12, payload.length, true);
    view.setUint32(16, checksum(payload), true);
    view.setUint32(20, (command ^ 0xffffffff) >>> 0, true);
    return new Uint8Array(header);
}

export function parseHeader(bytes) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return {
        command: view.getUint32(0, true),
        arg0: view.getUint32(4, true),
        arg1: view.getUint32(8, true),
        length: view.getUint32(12, true),
        checksum: view.getUint32(16, true),
        magic: view.getUint32(20, true),
    };
}

export const _internals = { encodePacket, checksum, A_CNXN, A_OPEN, A_WRTE, A_OKAY, A_CLSE, A_AUTH };

// --- the connection ---------------------------------------------------------

export class AdbError extends Error {}

export class Adb {
    constructor(device, iface, inEndpoint, outEndpoint) {
        this.device = device;
        this.iface = iface;
        this.inEndpoint = inEndpoint;
        this.outEndpoint = outEndpoint;
        this.maxPayload = MAX_PAYLOAD;
        this.nextLocalId = 1;
        this.banner = "";
    }

    static async requestDevice() {
        const device = await navigator.usb.requestDevice({ filters: [ADB_INTERFACE] });
        return Adb.open(device);
    }

    static async open(device) {
        await device.open();
        if (device.configuration === null) await device.selectConfiguration(1);

        let match = null;
        for (const config of device.configurations) {
            for (const iface of config.interfaces) {
                for (const alt of iface.alternates) {
                    if (
                        alt.interfaceClass === ADB_INTERFACE.classCode &&
                        alt.interfaceSubclass === ADB_INTERFACE.subclassCode &&
                        alt.interfaceProtocol === ADB_INTERFACE.protocol
                    ) {
                        match = { iface, alt };
                    }
                }
            }
        }
        if (!match) {
            throw new AdbError(
                "This device is not offering an ADB interface. Turn on USB debugging, " +
                    "and if the cable is charge-only try another one.",
            );
        }

        // Only one program at a time can hold the phone's debug interface, and
        // on any machine with Android tooling that program is usually adb. The
        // raw DOMException here says "Unable to claim interface", which is true
        // and tells nobody what to do — and `adb kill-server` on its own often
        // does not fix it, because a running emulator re-registers with the
        // server and respawns it within seconds.
        try {
            await device.claimInterface(match.iface.interfaceNumber);
        } catch (cause) {
            throw new AdbError(
                "Something else on this computer is already using the phone's debug " +
                    "connection, almost always adb.\n\n" +
                    "Close any running emulator first, then run: adb kill-server\n\n" +
                    "An emulator left running will restart the adb server by itself, so " +
                    "killing the server without closing the emulator does not help. " +
                    "Unplug and replug the phone, then try again.",
                { cause },
            );
        }
        let inEndpoint = null;
        let outEndpoint = null;
        for (const ep of match.alt.endpoints) {
            if (ep.type !== "bulk") continue;
            if (ep.direction === "in") inEndpoint = ep.endpointNumber;
            else outEndpoint = ep.endpointNumber;
        }
        return new Adb(device, match.iface.interfaceNumber, inEndpoint, outEndpoint);
    }

    async send(command, arg0, arg1, payload = new Uint8Array(0)) {
        await this.device.transferOut(this.outEndpoint, encodePacket(command, arg0, arg1, payload));
        if (payload.length) await this.device.transferOut(this.outEndpoint, payload);
    }

    async receive() {
        const head = await this.device.transferIn(this.inEndpoint, 24);
        if (head.status !== "ok") throw new AdbError(`USB read failed: ${head.status}`);
        const header = parseHeader(new Uint8Array(head.data.buffer));
        let payload = new Uint8Array(0);
        if (header.length > 0) {
            const chunks = [];
            let received = 0;
            while (received < header.length) {
                const part = await this.device.transferIn(
                    this.inEndpoint,
                    Math.min(header.length - received, this.maxPayload),
                );
                if (part.status !== "ok") throw new AdbError(`USB read failed: ${part.status}`);
                const chunk = new Uint8Array(part.data.buffer);
                chunks.push(chunk);
                received += chunk.length;
            }
            payload = new Uint8Array(received);
            let offset = 0;
            for (const chunk of chunks) {
                payload.set(chunk, offset);
                offset += chunk.length;
            }
        }
        return { ...header, payload };
    }

    /**
     * CNXN, then whatever authentication the device asks for.
     *
     * Signature first, public key second: a device that already trusts this
     * browser's key accepts the signature silently, and only a device seeing it
     * for the first time gets as far as prompting. Doing it the other way round
     * would re-prompt on every single connection.
     */
    async connect(onPrompt) {
        const key = this.key;
        await this.send(A_CNXN, VERSION, MAX_PAYLOAD, te.encode("host::features=cmd,shell_v2\0"));

        let offeredPublicKey = false;
        for (;;) {
            const packet = await this.receive();
            if (packet.command === A_CNXN) {
                this.banner = td.decode(packet.payload);
                this.maxPayload = Math.min(packet.arg1 || MAX_PAYLOAD, MAX_PAYLOAD);
                return;
            }
            if (packet.command !== A_AUTH || packet.arg0 !== AUTH_TOKEN) {
                throw new AdbError(`Unexpected packet during connect: 0x${packet.command.toString(16)}`);
            }
            if (!offeredPublicKey && key.signed) {
                await this.send(A_AUTH, AUTH_SIGNATURE, 0, signToken(packet.payload, key.d, key.n));
                key.signed = false;
            } else {
                if (onPrompt) onPrompt();
                offeredPublicKey = true;
                await this.send(
                    A_AUTH,
                    AUTH_RSAPUBLICKEY,
                    0,
                    te.encode(key.publicKeyBlob + " drawbridge-installer\0"),
                );
            }
        }
    }

    async authenticate(key, onPrompt) {
        this.key = { ...key, signed: true };
        await this.connect(onPrompt);
    }

    /**
     * Opens a service and returns a stream.
     *
     * `onData` is called with each chunk as it arrives. The OKAY that has to
     * follow every WRTE is sent here rather than by callers, because forgetting
     * it does not error — the device simply stops talking, which reads as a hang.
     */
    async openStream(service) {
        const localId = this.nextLocalId++;
        await this.send(A_OPEN, localId, 0, te.encode(service + "\0"));
        for (;;) {
            const packet = await this.receive();
            if (packet.command === A_OKAY && packet.arg1 === localId) {
                return new AdbStream(this, localId, packet.arg0);
            }
            if (packet.command === A_CLSE && packet.arg1 === localId) {
                throw new AdbError(`Device refused to open "${service}"`);
            }
        }
    }

    /** Runs a command and resolves with everything it printed. */
    async shell(command) {
        const stream = await this.openStream("shell:" + command);
        return stream.readAll();
    }

    async close() {
        try {
            await this.device.releaseInterface(this.iface);
        } catch {
            /* the device may already be gone */
        }
        try {
            await this.device.close();
        } catch {
            /* likewise */
        }
    }
}

export class AdbStream {
    constructor(adb, localId, remoteId) {
        this.adb = adb;
        this.localId = localId;
        this.remoteId = remoteId;
        this.closed = false;
        // Initialised here rather than in readAll: the device can print to the
        // stream while we are still writing to it — which is exactly what a
        // failing `pm install` does — and anything buffered before the first
        // read would otherwise be dropped, turning a real error message into a
        // silent failure.
        this.pending = [];
    }

    async write(data) {
        for (let offset = 0; offset < data.length; offset += this.adb.maxPayload) {
            const chunk = data.subarray(offset, offset + this.adb.maxPayload);
            await this.adb.send(A_WRTE, this.localId, this.remoteId, chunk);
            // Wait for the device to acknowledge before sending more, or a large
            // payload overruns its buffer and the stream dies mid-transfer.
            for (;;) {
                const packet = await this.adb.receive();
                if (packet.command === A_OKAY && packet.arg1 === this.localId) break;
                if (packet.command === A_CLSE && packet.arg1 === this.localId) {
                    this.closed = true;
                    throw new AdbError("Device closed the stream during write");
                }
                if (packet.command === A_WRTE && packet.arg1 === this.localId) {
                    await this.adb.send(A_OKAY, this.localId, this.remoteId);
                    if (this.pending) this.pending.push(packet.payload);
                }
            }
        }
    }

    /** Reads until the device closes the stream. */
    async readAll(onChunk) {
        const chunks = this.pending || [];
        this.pending = chunks;
        for (;;) {
            if (this.closed) break;
            const packet = await this.adb.receive();
            if (packet.command === A_WRTE && packet.arg1 === this.localId) {
                await this.adb.send(A_OKAY, this.localId, this.remoteId);
                chunks.push(packet.payload);
                if (onChunk) onChunk(td.decode(packet.payload));
            } else if (packet.command === A_CLSE && packet.arg1 === this.localId) {
                await this.adb.send(A_CLSE, this.localId, this.remoteId);
                this.closed = true;
                break;
            }
        }
        let total = 0;
        for (const c of chunks) total += c.length;
        const out = new Uint8Array(total);
        let offset = 0;
        for (const c of chunks) {
            out.set(c, offset);
            offset += c.length;
        }
        return td.decode(out);
    }
}
