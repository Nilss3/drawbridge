# The /why-blocked/ page (archived 2026-10-28)

The site's "The blocklist and the why" page, taken off the live sites on
2026-10-28. It was too much work to keep current, and a page of lists
suggests the blocking is list-shaped when it is rule-shaped (store-rating
rule, category rules). The research it cited stays valid; the page is kept
here so it can be reused.

Nothing here is built or deployed.

- `block-list.md`: the page source (the text and the research references).
- `convert_blocklist.py`: turned the blocklists into the page's tables.
- `why-blocked.en.html`: the last rendered English page, as served.

The page renderer (the WHY_* strings, `render_why_blocked`,
`render_why_summary`, the generated tables) lived in `tools/build-site.py`.
To get it back, read it from the commit before the removal:
`git show <removal commit>^:tools/build-site.py` (the commit is titled
"site: the /why-blocked/ page and its button are gone").
