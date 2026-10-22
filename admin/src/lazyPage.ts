import { lazy, type ComponentType } from 'react'
import { loadWithReload } from './loadWithReload'

/**
 * A page loaded when its route is first opened, instead of with the first screen.
 *
 * react-admin renders every route inside a `Suspense` boundary, so the page needs
 * nothing around it. The import goes through `loadWithReload`: a tab opened before a
 * deploy points at chunks the new image no longer ships.
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- a page takes any props: the type is the page's own
export function lazyPage<Name extends string, Page extends ComponentType<any>>(
  load: () => Promise<Record<Name, Page>>,
  name: Name,
) {
  return lazy(() => loadWithReload(load).then((module) => ({ default: module[name] })))
}
