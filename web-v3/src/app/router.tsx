import { createBrowserRouter } from 'react-router-dom'

import { HomeRoute } from '@/routes/HomeRoute'
import { MatchEvidenceRoute } from '@/routes/MatchEvidenceRoute'
import { OpsDiffsRoute } from '@/routes/OpsDiffsRoute'
import { OpsFeedsRoute } from '@/routes/OpsFeedsRoute'
import { OpsStreamsRoute } from '@/routes/OpsStreamsRoute'
import { ReviewRoute } from '@/routes/ReviewRoute'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <HomeRoute />,
  },
  {
    path: '/ops/feeds',
    element: <OpsFeedsRoute />,
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
    path: '/matches/:id/evidence',
    element: <MatchEvidenceRoute />,
  },
], {
  basename: import.meta.env.BASE_URL,
})
