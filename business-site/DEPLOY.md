# Publishing this page

One HTML file, no build step, no dependencies, no external requests — the
fonts are the ones already on the visitor's device and the favicon is inline.
Whatever is in this folder is the whole website.

## Deploying it (Direct Upload — no Git involved)

1. Cloudflare dashboard → **Workers & Pages** → **Create** → **Pages** →
   **Upload assets**.
2. Give the project a name. That name becomes the address:
   `<project-name>.pages.dev`, so pick it deliberately — it is awkward to
   change later.
3. Drag this `business-site` folder onto the upload box (the folder's
   *contents* end up at the site root: `index.html` becomes the homepage).
4. **Deploy site.**

Nothing on Cloudflare's side builds anything. There is no framework preset to
pick and no build command to leave empty — that dialogue belongs to the
Connect-to-Git flow, which this deliberately is not.

## Updating it later

Same project → **Create deployment** → drag the folder in again. Each upload
is a new deployment with its own preview URL, and the production URL moves to
it. Rolling back is picking an older deployment and promoting it.

If the clicking gets tedious, the same thing from a terminal:

```bash
npx wrangler pages deploy ./business-site --project-name=<project-name>
```

## A real domain

Pages project → **Custom domains** → **Set up a domain**. If the domain's DNS
is already on Cloudflare, this is two clicks and the certificate is automatic.
If it is registered elsewhere, Cloudflare gives you a CNAME to add at the
registrar; certificates still take care of themselves, within the hour.

## Editing

Edit `index.html` directly. Everything meant to be changed is written as
`[[ A PLACEHOLDER IN DOUBLE BRACKETS ]]`; `grep -n '\[\[' index.html` lists
every one that is still unfilled, which is the check to run before uploading.

To see it before it is public:

```bash
python3 -m http.server 8811 --directory business-site
```

then <http://localhost:8811>. Opening `index.html` as a `file://` URL works
too here — there are no absolute paths in it — but the local server is closer
to what Cloudflare will serve.
