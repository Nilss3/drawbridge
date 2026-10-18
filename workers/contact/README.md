# The contact form's Worker

`/contact/` on the site posts here, to `drawbridge-project.org/api/contact`. The
Worker checks the form, asks Cloudflare Turnstile whether a person sent it, and
mails it through Email Routing to an address that appears nowhere public: not
in the page, not in this repository, only in a Worker secret.

It is a Worker and not a Pages Function because Pages Functions cannot hold the
`send_email` binding. It is routed on one path of the same domain, so it sits
beside the Pages site rather than in front of it.

## One-time setup

All of it is in the Cloudflare dashboard or `wrangler`; nothing here can do it
for you.

1. **Email Routing** on `drawbridge-project.org` is enabled, and the address
   that `info@` forwards to is listed under *Destination addresses* as
   **Verified**. That verified address is what `CONTACT_TO` will be. (Email
   Routing was switched on 2026-10-18; its MX, SPF and DKIM records are live.)
2. **Turnstile:** *Turnstile → Add widget*, mode **Managed**, hostnames
   `drawbridge-project.org` and `drawbridge-project.pages.dev`. Copy the
   **site key** into `TURNSTILE_SITE_KEY` in `tools/build-site.py` and rebuild
   the site. The **secret key** is for step 3.
3. **Deploy and set the secrets**, from this folder, logged in to the account
   that owns the domain:

   ```bash
   npx wrangler login
   npx wrangler deploy
   npx wrangler secret put CONTACT_TO        # the verified address from step 1
   npx wrangler secret put TURNSTILE_SECRET  # the secret key from step 2
   ```

   `deploy` creates the route on `/api/contact`. The rest of the domain keeps
   being served by Pages.
4. **Test it:** send a message from `/contact/` on the live site. It should
   arrive from `form@drawbridge-project.org`, with *Reply-To* set to whatever
   address was typed in the form. `npx wrangler tail` shows why a submission
   was refused, if one is.

## What it refuses, and why it says so to nobody

A post from any origin but `drawbridge-project.org` or a `drawbridge-project`
Pages host; a body over 32 KiB; a filled-in honeypot field; an empty message
or one over 5000 characters; an app name over 300; a reply address that is not
one; a failed Turnstile check. The visitor sees the same "could not send" page
for all of them, and the reason goes to the Worker's log only.

Nothing a visitor types reaches a mail header except the reply address, which
must look like an address and cannot contain a line break, and the app name,
which has every control character stripped and is then RFC 2047-encoded. The
message itself travels as base64 text. `test/message.test.js` covers both.

## Tests

```bash
npm test     # node --test, no dependencies
```

They cover `src/message.js`, which is everything but the two calls into the
Workers runtime (`send_email` and Turnstile's `siteverify`).

## If it ever gets abused

Turnstile is the main gate. If something gets through it in volume, add a
rate-limiting rule for `/api/contact` under *Security → WAF* (for example ten
requests per minute per IP), which needs no change here.
