import { useEffect, useMemo, useRef, useState } from 'react'
import { ListBase, ListContextProvider, ListView, useListContext } from 'react-admin'
import type { ListProps } from 'react-admin'
import { useMercure } from '../../hooks/useMercure'
import { applyEvent, applyOverlay } from '../../hooks/liveList'
import type { LiveConfig, Overlay, Row } from '../../hooks/liveList'

interface Tracked {
  /** The query the events were applied to: another page, sort or filter starts over. */
  query: string
  overlay: Overlay
}

const LiveOverlay = ({ live, children }: { live: LiveConfig; children: React.ReactNode }) => {
  const list = useListContext()
  const query = JSON.stringify([list.resource, list.sort, list.filterValues, list.page, list.perPage])

  const [tracked, setTracked] = useState<Tracked>({ query, overlay: {} })
  const trackedRef = useRef(tracked)
  const queryRef = useRef(query)
  useEffect(() => {
    trackedRef.current = tracked
    queryRef.current = query
  })

  useMercure(live.topics, (data) => {
    let payload: unknown
    try {
      payload = JSON.parse(data ?? '')
    } catch {
      return
    }

    const current = trackedRef.current.query === queryRef.current ? trackedRef.current.overlay : {}
    const overlay = applyEvent(current, payload, live)
    if (overlay === current) return

    trackedRef.current = { query: queryRef.current, overlay }
    setTracked(trackedRef.current)
  })

  const overlay = tracked.query === query ? tracked.overlay : null
  const filtered = Object.keys(list.filterValues ?? {}).length > 0

  const value = useMemo(() => {
    const listed = list.data as Row[] | undefined
    if (!overlay || !listed || list.isPending) return list

    const { data, added, removed } = applyOverlay(listed, overlay, live, {
      sort: list.sort,
      page: list.page,
      perPage: list.perPage,
      filtered,
    })
    if (data === listed) return list

    return { ...list, data, total: (list.total ?? listed.length) + added - removed }
  }, [list, overlay, live, filtered])

  return <ListContextProvider value={value}>{children}</ListContextProvider>
}

/**
 * A `List` that stays in step with Mercure by applying the events, never by
 * reading the collection again: the collection is served from Elasticsearch,
 * indexed after the event left (MAG-347).
 */
export const LiveList = ({
  live,
  debounce,
  disableAuthentication,
  disableSyncWithLocation,
  exporter,
  filter,
  filterDefaultValues,
  perPage,
  queryOptions,
  resource,
  sort,
  storeKey,
  ...view
}: ListProps & { live: LiveConfig }) => (
  <ListBase
    debounce={debounce}
    disableAuthentication={disableAuthentication}
    disableSyncWithLocation={disableSyncWithLocation}
    exporter={exporter}
    filter={filter}
    filterDefaultValues={filterDefaultValues}
    perPage={perPage}
    queryOptions={queryOptions}
    resource={resource}
    sort={sort}
    storeKey={storeKey}
  >
    <LiveOverlay live={live}>
      <ListView {...view} />
    </LiveOverlay>
  </ListBase>
)
