/**
 * The subscribe URL of the Mercure hub (1.0+).
 *
 * The hub refuses the 0.x `topic` parameter with a 400. A topic is subscribed as
 * `match` when it is exact, and as `match_urlpattern` when it carries a `{id}`
 * placeholder (an API resource pattern such as `/users/<id>/api/recipes/{id}`),
 * which URL Patterns spell `:id`.
 */
export function mercureUrl(base: string, topics: string[]): URL {
  // `base` may be relative in prod ('/.well-known/mercure'); `new URL()` throws on it without an origin.
  const url = new URL(base, window.location.origin)
  for (const topic of topics) {
    if (topic.includes('{')) {
      url.searchParams.append('match_urlpattern', topic.replace(/\{(\w+)\}/g, ':$1'))
    } else {
      url.searchParams.append('match', topic)
    }
  }
  return url
}
