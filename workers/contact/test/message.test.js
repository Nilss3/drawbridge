// npm test, from workers/contact
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  allowedOrigin,
  buildRawEmail,
  readSubmission,
  resultUrl,
} from "../src/message.js";

const form = (fields) => {
  const data = new FormData();
  for (const [k, v] of Object.entries(fields)) data.append(k, v);
  return data;
};

const decodeBody = (raw) => {
  const body = raw.split("\r\n\r\n")[1].replace(/\r\n/g, "");
  return new TextDecoder().decode(Uint8Array.from(atob(body), (c) => c.charCodeAt(0)));
};

test("origins: ours are allowed, lookalikes are not", () => {
  assert.ok(allowedOrigin("https://drawbridge-project.org"));
  assert.ok(allowedOrigin("https://www.drawbridge-project.org"));
  assert.ok(allowedOrigin("https://drawbridge-project.pages.dev"));
  assert.ok(allowedOrigin("https://ccr-65e885dc-b0qwdy.drawbridge-project.pages.dev"));
  assert.ok(!allowedOrigin("https://drawbridge-project.org.evil.example"));
  assert.ok(!allowedOrigin("https://evildrawbridge-project.org"));
  assert.ok(!allowedOrigin("http://drawbridge-project.org"));
  assert.ok(!allowedOrigin(null));
});

test("the result page follows the language and never leaves our sites", () => {
  assert.equal(resultUrl("https://drawbridge-project.org", "en", "sent"),
    "https://drawbridge-project.org/contact/sent/");
  assert.equal(resultUrl("https://drawbridge-project.pages.dev", "nl", "failed"),
    "https://drawbridge-project.pages.dev/nl/contact/failed/");
  assert.equal(resultUrl("https://elsewhere.example", "fr", "sent"),
    "https://drawbridge-project.org/fr/contact/sent/");
});

test("a normal submission is read and trimmed", () => {
  const r = readSubmission(form({
    lang: "nl", message: "  Deze app glipt erdoor.\r\n  ", app: "Foo AI", email: "a@b.be", website: "",
  }));
  assert.deepEqual(r, { ok: true, fields: {
    lang: "nl", message: "Deze app glipt erdoor.", app: "Foo AI", email: "a@b.be",
  } });
});

test("refusals: honeypot, empty, too long, bad address", () => {
  assert.equal(readSubmission(form({ message: "hi", website: "x" })).reason, "honeypot");
  assert.equal(readSubmission(form({ message: "   " })).reason, "empty message");
  assert.equal(readSubmission(form({ message: "x".repeat(5001) })).reason, "message too long");
  assert.equal(readSubmission(form({ message: "hi", app: "a".repeat(301) })).reason, "app too long");
  assert.equal(readSubmission(form({ message: "hi", email: "not an address" })).reason, "bad email");
  assert.equal(readSubmission(form({ message: "hi", lang: "de" })).fields.lang, "en");
});

test("nothing a visitor types can add a mail header", () => {
  const r = readSubmission(form({
    message: "hi\r\nBcc: victim@example.com",
    app: "App\r\nBcc: victim@example.com",
    email: "a@b.be\r\nBcc: victim@example.com",
  }));
  // The address with a line break in it no longer matches an address at all.
  assert.equal(r.reason, "bad email");

  const ok = readSubmission(form({ message: "hi\nBcc: x@y.z", app: "App\r\nBcc: victim@example.com" }));
  const raw = buildRawEmail({ from: "form@drawbridge-project.org", to: "me@example.org", fields: ok.fields, id: "x" });
  const headers = raw.split("\r\n\r\n")[0];
  assert.ok(!/^Bcc:/im.test(headers), headers);
  // The message survives intact, inside the base64 body where it is only text.
  assert.ok(decodeBody(raw).startsWith("hi\nBcc: x@y.z"));
});

test("the email: from our domain, reply to the visitor, UTF-8 throughout", () => {
  const raw = buildRawEmail({
    from: "form@drawbridge-project.org",
    to: "me@example.org",
    fields: { lang: "fr", message: "Bonjour, l'appli « Café IA » passe.", app: "Café IA", email: "a@b.be" },
    now: new Date("2026-10-18T12:00:00Z"),
    id: "abc",
  });
  const headers = raw.split("\r\n\r\n")[0];
  assert.match(headers, /^From: drawbridge contact form <form@drawbridge-project\.org>$/m);
  assert.match(headers, /^To: <me@example\.org>$/m);
  assert.match(headers, /^Reply-To: <a@b\.be>$/m);
  assert.match(headers, /^Subject: =\?UTF-8\?B\?[A-Za-z0-9+/=]+\?=$/m);
  assert.match(headers, /^Message-ID: <abc@drawbridge-project\.org>$/m);
  const body = decodeBody(raw);
  assert.ok(body.includes("« Café IA »"));
  assert.ok(body.includes("App: Café IA"));
  assert.ok(raw.split("\r\n\r\n")[1].split("\r\n").every((line) => line.length <= 76));
});

test("no Reply-To when the visitor gives no address", () => {
  const raw = buildRawEmail({
    from: "form@drawbridge-project.org", to: "me@example.org",
    fields: { lang: "en", message: "hi", app: "", email: "" }, id: "x",
  });
  assert.ok(!/^Reply-To:/m.test(raw));
});
