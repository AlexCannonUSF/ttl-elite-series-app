# TTLElite Series

TTLElite Series is a Spring Boot + React application for scraping TT Series results, building player/rating intelligence, pricing live and prematch markets, and running a paper-trading workflow through the `Live Studio` UI.

This repository now has three documentation lanes:

- **Codebase Atlas**: how the code is organized, where responsibilities live, and where to start for a given task.
- **2.0 Program Docs**: the shipped 2.0 product/architecture roadmap, release gate, and bug-closure plans.
- **3.0 Program Docs**: the next major release plan, phase files, and implementation checklist.

## Start Here

- Codebase navigation hub: [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/codebase-atlas/README.md`](docs/codebase-atlas/README.md)
- 2.0 program docs: [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0/README.md`](docs/ttlelite-series-2.0/README.md)
- 3.0 program docs: [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-3.0/README.md`](docs/ttlelite-series-3.0/README.md)
- Documentation index: [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/README.md`](docs/README.md)

## Repository Map

- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis`](src/main/java/com/ttl/tabletennis): backend application code
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/test/java`](src/test/java): backend regression and unit tests
- [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/resources`](src/main/resources): runtime configuration
- [`/Users/alexcannon/Downloads/TTLEliteSeries/web-v3`](web-v3): canonical 3.0 React frontend
- [`/Users/alexcannon/Downloads/TTLEliteSeries/web`](web): retired 2.0 MUI frontend retained for reference during Phase 07
- [`/Users/alexcannon/Downloads/TTLEliteSeries/ttl-predict-py`](ttl-predict-py): Phase 00 Python prediction-service skeleton
- [`/Users/alexcannon/Downloads/TTLEliteSeries/infra/monitoring`](infra/monitoring): Prometheus, Grafana, and monitoring provisioning for 3.0
- [`/Users/alexcannon/Downloads/TTLEliteSeries/scripts`](scripts): release-gate and runtime smoke/watch scripts
- [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/codebase-atlas`](docs/codebase-atlas): source-organization and navigation docs
- [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-2.0`](docs/ttlelite-series-2.0): 2.0 product/rollout documentation
- [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/ttlelite-series-3.0`](docs/ttlelite-series-3.0): 3.0 planning, phase, and release-gate documentation

## Fast Navigation

If you know the type of work you are doing, start here:

- Live settlement / score continuity: [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PaperTradingService.java`](src/main/java/com/ttl/tabletennis/service/PaperTradingService.java)
- Live board / value engine: [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java`](src/main/java/com/ttl/tabletennis/service/OddsValueEngineService.java)
- Scraping and upstream connectors: [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/scrape`](src/main/java/com/ttl/tabletennis/scrape)
- Ratings, features, and model training: [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/FeatureService.java`](src/main/java/com/ttl/tabletennis/service/FeatureService.java), [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/service/PredictionModelService.java`](src/main/java/com/ttl/tabletennis/service/PredictionModelService.java)
- Backend API surface: [`/Users/alexcannon/Downloads/TTLEliteSeries/src/main/java/com/ttl/tabletennis/controller`](src/main/java/com/ttl/tabletennis/controller)
- Frontend routing and product surfaces: [`/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/app/router.tsx`](web-v3/src/app/router.tsx), [`/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/components/layout/V3Shell.tsx`](web-v3/src/components/layout/V3Shell.tsx), [`/Users/alexcannon/Downloads/TTLEliteSeries/web-v3/src/routes`](web-v3/src/routes)

## Runbook

IntelliJ (recommended):

1. Select **TTL App - One Click** in the Run configuration menu.
2. Press the green Run button once.
3. The backend owns `http://localhost:8080`, the React UI starts at
   `http://127.0.0.1:5174/v3/`, and the browser opens automatically.
4. Press IntelliJ Stop before starting the application anywhere else. The
   backend and the UI are managed as one run session.

Backend:

```bash
./mvnw spring-boot:run
```

Frontend:

```bash
cd web-v3
npm install
npm run dev
```

Monitoring + predict stub:

```bash
docker compose -f /Users/alexcannon/Downloads/TTLEliteSeries/infra/monitoring/compose.yaml up --build
```

One-command release gate:

```bash
./scripts/release_gate.sh
```

## Keeping The Atlas Fresh

The file/package indexes in [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/codebase-atlas/generated`](docs/codebase-atlas/generated) are generated. Rebuild them with:

```bash
python3 ./scripts/generate_codebase_atlas.py
```

When you add a new package, page, endpoint, or script, update:

1. The source code
2. The appropriate hand-written atlas page in [`/Users/alexcannon/Downloads/TTLEliteSeries/docs/codebase-atlas`](docs/codebase-atlas)
3. The generated indexes via `python3 ./scripts/generate_codebase_atlas.py`
