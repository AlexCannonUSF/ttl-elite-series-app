# TTL Elite Series Web (Epoch 2 Start)

React + TypeScript + Vite frontend for TTLElite Series 2.0.

## Implemented

- React + TypeScript + Vite project setup
- Route-based app shell and responsive navigation
- Material UI design system + custom theme tokens
- Dashboard page (scrape status, quick player search, top win-rate opportunities)
- Players page (sortable + paginated)
- Player detail page (recent match timeline + rating history chart)
- Matchup analysis page (head-to-head, probability, confidence interval, feature contributions)
- Admin page (trigger scrape run, backfill structured results, view alias mappings)
- React Query + Axios API layer
- PWA setup (`vite-plugin-pwa`) with service worker + manifest
- ESLint + Prettier + git pre-commit hook (`web/.githooks/pre-commit`)

## Run

From repository root:

```bash
# backend (H2 local file DB + scraper disabled by default for manual control)
cd /Users/alexcannon/Downloads/TTLEliteSeries

SPRING_DATASOURCE_URL='jdbc:h2:file:./data/ttl;MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE' \
SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.h2.Driver \
SPRING_DATASOURCE_USERNAME=sa \
SPRING_DATASOURCE_PASSWORD= \
SPRING_JPA_PROPERTIES_HIBERNATE_DIALECT=org.hibernate.dialect.H2Dialect \
SPRING_JPA_HIBERNATE_DDL_AUTO=update \
SCRAPE_AUTO=false \
SERVER_PORT=8080 \
./mvnw spring-boot:run
```

From `web/`:

```bash
cd /Users/alexcannon/Downloads/TTLEliteSeries/web
npm install --cache /tmp/ttl-npm-cache
VITE_API_BASE_URL=http://localhost:8080 npm run dev
```

Frontend default URL: `http://localhost:5173`
Backend default URL: `http://localhost:8080`

## Environment

Set `VITE_API_BASE_URL` if backend is not on localhost:8080:

```bash
VITE_API_BASE_URL=http://localhost:8080 npm run dev
```

If backend says `Port 8080 was already in use`, either:

```bash
lsof -nP -iTCP:8080 | grep LISTEN
kill -9 <PID>
```

or run backend on a different port:

```bash
SERVER_PORT=8081 ./mvnw spring-boot:run
```

then run frontend with:

```bash
VITE_API_BASE_URL=http://localhost:8081 npm run dev
```

## Scraper config

Default scraper target is now:

- `TTL_BASE_URL=https://www.tt-series.com`
- `TTL_LIST_PATH=/category/turnieje`

Override when needed:

```bash
TTL_BASE_URL='https://your-real-host' TTL_LIST_PATH='/your-list-path' ./mvnw spring-boot:run
```

Run a multi-page scrape without restart:

```bash
curl -X POST "http://localhost:8080/api/scrape/range?fromPage=1&toPage=3"
curl "http://localhost:8080/api/scrape/status"
curl "http://localhost:8080/api/scrape/runs?limit=10"
```
