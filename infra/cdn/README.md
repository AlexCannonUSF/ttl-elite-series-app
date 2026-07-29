# v3 CDN + caching policy

Phase 07 item 7 — Cache headers for the v3 static bundle.

## Policy

| Path | Cache-Control | Why |
| --- | --- | --- |
| `/v3/assets/**` | `max-age=31536000, public, immutable` | Vite writes content-hashed filenames (`index-DYAmK6kq.js`, `index-Cda6hqjb.css`, etc.). The URL is the cache key; any change ships a new URL. Safe to pin in the browser cache and at the edge for one year. |
| `/v3/index.html`, SPA fallback (`/v3/`, `/v3/<anything-without-an-extension>`) | `no-cache, must-revalidate, public, stale-while-revalidate=60` | The shell links to the latest fingerprinted bundle. Browsers and CDNs revalidate on every navigation, but during a deploy edges may serve a 60-second-stale shell while they fetch the new one in the background. |
| `/api/**` | (handled by the API controllers themselves) | Spring's API responses set their own `Cache-Control`. The CDN must not cache `/api/**` by default. |

The headers are emitted by the Spring `WebConfig` resource handlers (`IMMUTABLE_ASSET_CACHE_CONTROL`, `SPA_SHELL_CACHE_CONTROL`) and are end-to-end: an honest CDN that respects origin headers will inherit them without further configuration.

## CDN behaviour (CloudFront-style)

The behaviours below assume a single origin pointing at the Spring service. Order matters — match the most specific path first.

1. **`/v3/assets/*`**
   - Origin-request: forward `Accept-Encoding` only.
   - Cache key: URL path only (asset filenames are already content-hashed; query strings would just split the cache).
   - TTL: `max-age=31536000` (inherit from origin).
   - Compress objects automatically: yes (Vite ships uncompressed, the edge does br/gzip).
2. **`/v3/*`** (catches `/v3/`, `/v3/index.html`, and every SPA route)
   - Origin-request: forward `Accept-Encoding`, `Accept-Language` (if i18n later).
   - Cache key: URL path only.
   - TTL: `max-age=0`, stale-while-revalidate honoured.
   - Compress objects automatically: yes.
3. **`/api/*`** — cache disabled. Forward all viewer headers and cookies.
4. **`/actuator/*`** — cache disabled and IP-restricted to the ops VPC.

## Sample nginx config (for self-hosted edges)

`nginx.v3.conf` in this folder is a drop-in `location` block that mirrors the policy and adds the `Vary: Accept-Encoding` header that browsers need to negotiate gzip/br correctly.

## Stale-while-revalidate, in plain English

The SPA shell tells the browser and CDN: *"You can serve this for zero seconds. After that, revalidate. But if revalidation is slow, you may serve the stale copy for up to 60 more seconds while you fetch a fresh one in the background."*

In practice this means a v3 deploy never blocks a navigation: the user gets the previous shell instantly while the CDN warms up the new one. Once the new shell lands at the edge, the next navigation gets the new fingerprints, which resolve to brand-new immutable asset URLs.

## Invalidation

The only thing that ever needs CDN invalidation is `/v3/index.html` (and `/v3/` which is just an alias). Fingerprinted assets never need invalidation because their URLs change. After a deploy:

```bash
aws cloudfront create-invalidation \
  --distribution-id "$DISTRIBUTION_ID" \
  --paths "/v3/" "/v3/index.html"
```

If you skip the invalidation, the stale-while-revalidate header takes over: the edge will serve the prior shell for up to 60 s and then quietly upgrade.

## Verification

```bash
# from the deployed origin
curl -sI https://example.com/v3/assets/index-DYAmK6kq.js | grep -i cache-control
# -> Cache-Control: public, max-age=31536000, immutable

curl -sI https://example.com/v3/ | grep -i cache-control
# -> Cache-Control: public, max-age=0, must-revalidate, stale-while-revalidate=60
```

The Java unit test `WebConfigTests#v3AssetsAreImmutableForOneYear` /
`v3ShellUsesStaleWhileRevalidate` lock the header values to the policy table
above, so a regression at the origin fails CI before it reaches a CDN.
