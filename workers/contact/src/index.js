// The site's contact form, as a Cloudflare Worker on
// drawbridge-project.org/api/contact.
//
// It exists so that people can report an app or site the lists missed without
// the project's address appearing anywhere a scraper could find it. The
// recipient is the CONTACT_TO secret: not in the page, not in this repository.
// Setup is in ../README.md.

import { EmailMessage } from "cloudflare:email";
import { allowedOrigin, buildRawEmail, readSubmission, resultUrl } from "./message.js";

// Generous for a message of 5000 characters, small enough that nobody can
// make this Worker parse something large.
const MAX_BODY_BYTES = 32 * 1024;

const SITEVERIFY = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

export default {
  async fetch(request, env) {
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405, headers: { Allow: "POST" } });
    }

    const origin = request.headers.get("Origin");
    // Browsers send Origin on every form POST. A request without one, or from
    // a page that is not ours, did not come from the form.
    if (!allowedOrigin(origin)) return new Response("Forbidden", { status: 403 });

    const length = Number(request.headers.get("Content-Length") || "0");
    if (length > MAX_BODY_BYTES) return redirect(origin, "en", "failed");

    let form;
    try {
      form = await request.formData();
    } catch {
      return redirect(origin, "en", "failed");
    }

    const submission = readSubmission(form);
    if (!submission.ok) {
      console.log(`contact: refused (${submission.reason})`);
      return redirect(origin, submission.lang || "en", "failed");
    }
    const { fields } = submission;

    const human = await passedTurnstile(
      env.TURNSTILE_SECRET,
      form.get("cf-turnstile-response"),
      request.headers.get("CF-Connecting-IP"),
    );
    if (!human) {
      console.log("contact: refused (turnstile)");
      return redirect(origin, fields.lang, "failed");
    }

    try {
      const raw = buildRawEmail({ from: env.CONTACT_FROM, to: env.CONTACT_TO, fields });
      await env.MAILER.send(new EmailMessage(env.CONTACT_FROM, env.CONTACT_TO, raw));
    } catch (error) {
      // An unverified destination, Email Routing switched off, a typo in a
      // secret: all of them land here, and all of them are ours to fix.
      console.log(`contact: send failed: ${error}`);
      return redirect(origin, fields.lang, "failed");
    }
    return redirect(origin, fields.lang, "sent");
  },
};

function redirect(origin, lang, outcome) {
  // 303 so the browser follows with a GET, and a reload of the result page
  // does not post the form a second time.
  return Response.redirect(resultUrl(origin, lang, outcome), 303);
}

async function passedTurnstile(secret, token, ip) {
  if (!secret || typeof token !== "string" || token === "") return false;
  const body = new FormData();
  body.append("secret", secret);
  body.append("response", token);
  if (ip) body.append("remoteip", ip);
  try {
    const answer = await fetch(SITEVERIFY, { method: "POST", body });
    const outcome = await answer.json();
    return outcome.success === true;
  } catch {
    return false;
  }
}
