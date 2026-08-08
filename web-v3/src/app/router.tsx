import { lazy, Suspense, type ComponentType, type LazyExoticComponent } from 'react'
import { createBrowserRouter } from 'react-router-dom'

import { RoleGatewayRoute } from '@/routes/RoleGatewayRoute'

const LiveBoardRoute = lazy(() => import('@/routes/LiveBoardRoute').then((module) => ({ default: module.LiveBoardRoute })))
const MatchDetailRoute = lazy(() => import('@/routes/MatchDetailRoute').then((module) => ({ default: module.MatchDetailRoute })))
const ModelCallTrackingRoute = lazy(() => import('@/routes/ModelCallTrackingRoute').then((module) => ({ default: module.ModelCallTrackingRoute })))
const ModelPipelineRoute = lazy(() => import('@/routes/ModelPipelineRoute').then((module) => ({ default: module.ModelPipelineRoute })))
const MlQualityRoute = lazy(() => import('@/routes/MlQualityRoute').then((module) => ({ default: module.MlQualityRoute })))
const AdminHubRoute = lazy(() => import('@/routes/AdminHubRoute').then((module) => ({ default: module.AdminHubRoute })))
const OpsConsoleRoute = lazy(() => import('@/routes/OpsConsoleRoute').then((module) => ({ default: module.OpsConsoleRoute })))
const OpsDiffsRoute = lazy(() => import('@/routes/OpsDiffsRoute').then((module) => ({ default: module.OpsDiffsRoute })))
const OpsFeedsRoute = lazy(() => import('@/routes/OpsFeedsRoute').then((module) => ({ default: module.OpsFeedsRoute })))
const OpsIngestRoute = lazy(() => import('@/routes/OpsIngestRoute').then((module) => ({ default: module.OpsIngestRoute })))
const OpsStreamsRoute = lazy(() => import('@/routes/OpsStreamsRoute').then((module) => ({ default: module.OpsStreamsRoute })))
const ReviewRoute = lazy(() => import('@/routes/ReviewRoute').then((module) => ({ default: module.ReviewRoute })))
const ScrapeRoute = lazy(() => import('@/routes/ScrapeRoute').then((module) => ({ default: module.ScrapeRoute })))

function lazyRoute(Route: LazyExoticComponent<ComponentType>) {
  return (
    <Suspense
      fallback={(
        <main
          aria-busy="true"
          aria-live="polite"
          className="grid min-h-screen place-items-center bg-[var(--canvas)] text-sm text-[var(--muted)]"
        >
          Loading workspace…
        </main>
      )}
    >
      <Route />
    </Suspense>
  )
}

export const router = createBrowserRouter([
  {
    path: '/',
    element: <RoleGatewayRoute />,
  },
  {
    path: '/user',
    element: lazyRoute(LiveBoardRoute),
  },
  {
    path: '/user/matches/:id',
    element: lazyRoute(MatchDetailRoute),
  },
  {
    path: '/user/matches/:id/:tab',
    element: lazyRoute(MatchDetailRoute),
  },
  {
    path: '/user/tracking/:callId',
    element: lazyRoute(ModelCallTrackingRoute),
  },
  {
    path: '/admin',
    element: lazyRoute(AdminHubRoute),
  },
  {
    path: '/admin/model-quality',
    element: lazyRoute(MlQualityRoute),
  },
  {
    path: '/admin/pipeline',
    element: lazyRoute(ModelPipelineRoute),
  },
  {
    path: '/admin/pipeline/:callId',
    element: lazyRoute(ModelCallTrackingRoute),
  },
  {
    path: '/admin/ops',
    element: lazyRoute(OpsConsoleRoute),
  },
  {
    path: '/admin/feeds',
    element: lazyRoute(OpsFeedsRoute),
  },
  {
    path: '/admin/ingest',
    element: lazyRoute(OpsIngestRoute),
  },
  {
    path: '/admin/streams',
    element: lazyRoute(OpsStreamsRoute),
  },
  {
    path: '/admin/diffs',
    element: lazyRoute(OpsDiffsRoute),
  },
  {
    path: '/admin/review',
    element: lazyRoute(ReviewRoute),
  },
  {
    path: '/admin/scrape',
    element: lazyRoute(ScrapeRoute),
  },
  {
    path: '/live-board',
    element: lazyRoute(LiveBoardRoute),
  },
  {
    path: '/ops',
    element: lazyRoute(OpsConsoleRoute),
  },
  {
    path: '/ops/feeds',
    element: lazyRoute(OpsFeedsRoute),
  },
  {
    path: '/ops/ingest',
    element: lazyRoute(OpsIngestRoute),
  },
  {
    path: '/ops/feeds/streams',
    element: lazyRoute(OpsStreamsRoute),
  },
  {
    path: '/ops/diffs',
    element: lazyRoute(OpsDiffsRoute),
  },
  {
    path: '/review',
    element: lazyRoute(ReviewRoute),
  },
  {
    path: '/ops/scrape',
    element: lazyRoute(ScrapeRoute),
  },
  {
    path: '/matches/:id',
    element: lazyRoute(MatchDetailRoute),
  },
  {
    path: '/matches/:id/:tab',
    element: lazyRoute(MatchDetailRoute),
  },
  {
    path: '/ml/quality',
    element: lazyRoute(MlQualityRoute),
  },
], {
  basename: import.meta.env.BASE_URL,
})
