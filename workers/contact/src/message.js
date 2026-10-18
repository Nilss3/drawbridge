// The parts of the contact form that are plain logic: reading the form,
// refusing what is malformed, and writing the email. Kept apart from
// index.js, which needs the Workers runtime (`cloudflare:email`), so that
// these can be tested with `node --test` and nothing else.

export const LANGS = ["en", "nl", "fr"];

export const LIMITS = { message: 5000, app: 300, email: 254 };

// Where a form may be posted from, and therefore where the visitor may be sent
// back to afterwards. The redirect target is built from this list and never
// from a header verbatim, so the endpoint cannot be used to bounce people to
// somebody else's site.
const ALLOWED_ORIGINS = [
  /^https:\/\/(www\.)?drawbridge-project\.org$/,
  /^https:\/\/([a-z0-9-]+\.)?drawbridge-project\.pages\.dev$/,
];
export const CANONICAL_ORIGIN = "https://drawbridge-project.org";

export function allowedOrigin(origin) {
  return typeof origin === "string" && ALLOWED_ORIGINS.some((re) => re.test(origin));
}

/** The page to send the visitor to: `/contact/sent/`, `/nl/contact/failed/` and so on. */
export function resultUrl(origin, lang, outcome) {
  const base = allowedOrigin(origin) ? origin : CANONICAL_ORIGIN;
  const prefix = lang === "en" ? "" : `/${lang}`;
  return `${base}${prefix}/contact/${outcome}/`;
}

// Deliberately loose. The address is only ever used as a Reply-To, so the
// check exists to keep out header injection and obvious junk, not to decide
// what a valid mailbox is.
const EMAIL = /^[^\s@<>"(),;:]+@[^\s@<>"(),;:]+\.[^\s@<>"(),;:]+$/;

/** One line, no control characters: what may go into a mail header. */
function headerSafe(value) {
  return value.replace(/[\u0000-\u001f\u007f]+/g, " ").trim();
}

/**
 * Reads and checks a submitted form.
 *
 * Returns `{ ok: true, fields }` or `{ ok: false, reason }`. The reason is for
 * the log only; the visitor sees the same "could not send" page either way,
 * because telling a bot which check it failed is help it does not need.
 */
export function readSubmission(form) {
  const get = (name) => {
    const value = form.get(name);
    return typeof value === "string" ? value : "";
  };

  // A field no person can see. Anything that fills it in is filling in every
  // field it finds, which is what a form-spamming script does.
  if (get("website").trim() !== "") return { ok: false, reason: "honeypot" };

  const lang = LANGS.includes(get("lang")) ? get("lang") : "en";
  const message = get("message").replace(/\r\n?/g, "\n").trim();
  const app = headerSafe(get("app"));
  const email = headerSafe(get("email"));

  if (message === "") return { ok: false, reason: "empty message", lang };
  if (message.length > LIMITS.message) return { ok: false, reason: "message too long", lang };
  if (app.length > LIMITS.app) return { ok: false, reason: "app too long", lang };
  if (email !== "" && (email.length > LIMITS.email || !EMAIL.test(email))) {
    return { ok: false, reason: "bad email", lang };
  }
  return { ok: true, fields: { lang, message, app, email } };
}

function base64Utf8(text) {
  const bytes = new TextEncoder().encode(text);
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

/** RFC 2047, so an app name in any script survives the Subject line. */
function encodedWord(text) {
  return `=?UTF-8?B?${base64Utf8(text)}?=`;
}

/**
 * The raw message handed to Cloudflare's `send_email` binding.
 *
 * Plain text, base64-encoded, so no character in what a visitor typed can be
 * read as MIME structure. The visitor's address goes in Reply-To and never in
 * From: mail is only ever sent *from* this domain, which is what its SPF and
 * DKIM records vouch for.
 */
export function buildRawEmail({ from, to, fields, now = new Date(), id = crypto.randomUUID() }) {
  const subject = fields.app
    ? `drawbridge contact: ${fields.app}`.slice(0, 150)
    : "drawbridge contact form";
  const body = [
    fields.message,
    "",
    "--",
    `App: ${fields.app || "(none given)"}`,
    `Reply to: ${fields.email || "(no address given)"}`,
    `Page language: ${fields.lang}`,
    "Sent with the contact form on drawbridge-project.org.",
  ].join("\n");

  const headers = [
    `From: drawbridge contact form <${from}>`,
    `To: <${to}>`,
    ...(fields.email ? [`Reply-To: <${fields.email}>`] : []),
    `Subject: ${encodedWord(subject)}`,
    `Date: ${now.toUTCString()}`,
    `Message-ID: <${id}@${from.split("@")[1]}>`,
    "MIME-Version: 1.0",
    "Content-Type: text/plain; charset=utf-8",
    "Content-Transfer-Encoding: base64",
  ];
  const encoded = base64Utf8(body).replace(/.{1,76}/g, "$&\r\n");
  return `${headers.join("\r\n")}\r\n\r\n${encoded}`;
}
