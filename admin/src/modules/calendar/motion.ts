/** An animation, off by its setting and by « reduce motion » of the system. */
export const motion = (animate: boolean, animation: string) =>
  animate ? { animation, '@media (prefers-reduced-motion: reduce)': { animation: 'none' } } : {}
