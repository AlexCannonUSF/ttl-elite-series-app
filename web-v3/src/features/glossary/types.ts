export type MetricDefinition = {
  key: string
  category: string
  userLabel: string
  adminLabel: string
  summary: string
  formula: string
  directionality: string
  unit: string
  minimumUsefulSample: string
  caveats: string[]
  relatedKeys: string[]
  definitionVersion: string
}
