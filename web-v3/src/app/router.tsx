import { createBrowserRouter } from 'react-router-dom'

import { HomeRoute } from '@/routes/HomeRoute'
import { LiveBoardRoute } from '@/routes/LiveBoardRoute'
import { MatchDetailRoute } from '@/routes/MatchDetailRoute'
import { MlQualityRoute } from '@/routes/MlQualityRoute'
import { OpsConsoleRoute } from '@/routes/OpsConsoleRoute'
import { OpsDiffsRoute } from '@/routes/OpsDiffsRoute'
import { OpsFeedsRoute } from '@/routes/OpsFeedsRoute'
import { OpsIngestRoute } from '@/routes/OpsIngestRoute'
import { OpsStreamsRoute } from '@/routes/OpsStreamsRoute'
import { ReviewRoute } from '@/routes/ReviewRoute'
import { ScrapeRoute } from '@/routes/ScrapeRoute'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <HomeRoute />,
  },
  {
    path: '/live-board',
    element: <LiveBoardRoute />,
  },
  {
    path: '/ops',
    element: <OpsConsoleRoute />,
  },
  {
    path: '/ops/feeds',
    element: <OpsFeedsRoute />,
  },
  {
    path: '/ops/ingest',
    element: <OpsIngestRoute />,
  },
  {
    path: '/ops/feeds/streams',
    element: <OpsStreamsRoute />,
  },
  {
    path: '/ops/diffs',
    element: <OpsDiffsRoute />,
  },
  {
    path: '/review',
    element: <ReviewRoute />,
  },
  {
    path: '/ops/scrape',
    element: <ScrapeRoute />,
  },
  {
    path: '/matches/:id',
    element: <MatchDetailRoute />,
  },
  {
    path: '/matches/:id/:tab',
    element: <MatchDetailRoute />,
  },
  {
    path: '/ml/quality',
    element: <MlQualityRoute />,
  },
], {
  basename: import.meta.env.BASE_URL,
})
