# Publishing this page

One HTML file, no build step, no dependencies, no external requests — the
fonts are the ones already on the visitor's device and the favicon is inline.
Whatever is in this folder is the whole website.

**The site is at <https://ontheway-consulting.pages.dev>.** Written down because
it is not derivable from anything in this folder, and a neighbouring name is
somebody else's project — check the `<title>` before believing a URL is ours.

## Deploying it (Direct Upload — no Git involved)

**The upload box takes a folder or a `.zip`, never a single file.** Dropping
`index.html` on its own is rejected, and the rejection does not explain itself,
which reads as "Pages won't host a one-page site" when it means "give me the
folder it lives in". This is also why the dashboard seems to push you towards
making a Worker instead. You do not need one.

There is a zip ready to go: build it with

```bash
cd business-site && zip ../on-the-way-site.zip index.html _headers
```

`index.html` must sit at the *root* of that zip, not inside a folder within it,
or every path 404s. Note that `DEPLOY.md` is deliberately left out — everything
in the upload is served publicly, and these notes are nobody else's business.

1. Cloudflare dashboard → **Workers & Pages** → **Create**. The page has
   separate **Workers** and **Pages** tabs; take **Pages** → **Upload assets**.
2. Name the project. That name becomes the address, `<project-name>.pages.dev`,
   and is awkward to change later.
3. Drop in the zip, or the whole `business-site` folder. Either way its
   *contents* land at the site root, so `index.html` becomes the homepage.
4. **Deploy site.**

Nothing builds on Cloudflare's side. There is no framework preset to pick and
no build command to leave empty — that dialogue belongs to the Connect-to-Git
flow, which this deliberately is not.

## Updating it later

Same project → **Create deployment** → drop in the new zip or folder. Each
upload is its own deployment with its own preview URL, and production moves to
it. Rolling back is promoting an older deployment.

From a terminal instead, which skips the zip entirely — Wrangler takes a
directory and *only* a directory:

```bash
npx wrangler pages deploy ./business-site --project-name=<project-name>
```

## The Worker route, and why not to bother

Cloudflare now steers new static sites toward Workers with static assets, and
that genuinely works: a `wrangler.toml` with an assets directory, `wrangler
deploy`, done. It is the better answer when a site eventually needs redirects,
auth, an API or any edge logic.

This page needs none of that, and the Worker route costs a config file and a
CLI where Pages costs a drag. If that ever changes, migrating is
`npx wrangler pages download config` territory rather than a rewrite — the HTML
does not change at all.

## A real domain

Pages project → **Custom domains** → **Set up a domain**. If the domain's DNS
is already on Cloudflare, this is two clicks and the certificate is automatic.
If it is registered elsewhere, Cloudflare gives you a CNAME to add at the
registrar; certificates still take care of themselves, within the hour.

## Editing

Edit `index.html` directly — it is the whole site, content and styling in one
file. Three things live in more than one place and have to be changed in all of
them, so they are worth knowing about:

- **The email address** appears twice: the "Get in touch" button and the
  contact list. It is deliberately *not* in the JSON-LD block — see below.
- **The VAT number and address** appear in the contact list, the footer, and
  the JSON-LD block.
- **The description** appears in `<meta name="description">`, the `og:description`
  and the JSON-LD block.

The JSON-LD block is the machine-readable copy of the business details, which is
what search engines read to show the company rather than just the page. It is
easy to forget, being invisible on the page, and wrong structured data is worse
than none — so change it whenever the visible details change.

It carries no email address on purpose. Cloudflare's Scrape Shield can obfuscate
addresses in the visible HTML at the edge, but it treats JSON as opt-out, so an
address left in this block would be the one plaintext copy every harvester gets
for free. The name, address and VAT number still identify the company to a
search engine without it.

To see the result before it is public:

```bash
python3 -m http.server 8811 --directory business-site
```

then <http://localhost:8811>. Opening `index.html` as a `file://` URL works too
— there are no absolute paths in it — but the local server is closer to what
Cloudflare will serve.
