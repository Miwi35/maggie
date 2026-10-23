const RELOAD_KEY = 'maggie:chunk-reload-at'
// A reload that did not fix the load within this window is a real outage, not a deploy.
const RETRY_WINDOW_MS = 60_000

type ReloadStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

interface Options {
  storage?: ReloadStorage
  reload?: () => void
  now?: () => number
}

// Site data can be blocked (reading `sessionStorage` itself throws): without it
// the loop guard cannot work, so the load must neither fail nor reload blind.
function openStorage(): ReloadStorage | null {
  try {
    return window.sessionStorage
  } catch {
    return null
  }
}

// A tab opened before a deploy still points at the previous build's hashed
// chunks, which the new image no longer ships: the import fails (nginx answers
// 404, or index.html outside /admin/assets/). Reloading fetches the current index.html.
export async function loadWithReload<T>(load: () => Promise<T>, options: Options = {}): Promise<T> {
  const storage = options.storage ?? openStorage()
  const reload = options.reload ?? (() => window.location.reload())
  const now = options.now ?? Date.now

  let module: T
  try {
    module = await load()
  } catch (error) {
    const message = error instanceof Error ? error.message : ''
    const isStaleChunk =
      message.includes('dynamically imported module') || message.includes('Importing a module script failed')
    if (!isStaleChunk || !storage) throw error

    try {
      const lastReload = storage.getItem(RELOAD_KEY)
      if (lastReload !== null && now() - Number(lastReload) < RETRY_WINDOW_MS) throw error
      storage.setItem(RELOAD_KEY, String(now()))
    } catch {
      throw error
    }
    reload()
    return new Promise<T>(() => {})
  }

  try {
    storage?.removeItem(RELOAD_KEY)
  } catch {
    // Best effort: the module is loaded, the app must start.
  }
  return module
}
