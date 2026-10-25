import { alpha } from '@mui/material/styles'

/** The halo the mockups lay on the night background: an ellipse in px, centred at x/y % of the screen, fading out at `fade` %. */
export interface ActivityHalo {
  width: number
  height: number
  x: number
  y: number
  alpha: number
  fade: number
}

/** « Activité Cuisine · Tablette » — the halo of the frame, the one every activity wears unless its mockup says otherwise. */
export const ACTIVITY_HALO: ActivityHalo = { width: 900, height: 640, x: 78, y: 30, alpha: 0.16, fade: 62 }

/** « Revue · Activité » — the Comptes mint, further right and softer. */
export const REVIEW_HALO: ActivityHalo = { width: 900, height: 600, x: 85, y: 30, alpha: 0.13, fade: 60 }

/** The `background-image` of an activity: its [color] as a radial halo (the night colour stays the `background-color`). */
export const activityHalo = (color: string, halo: ActivityHalo = ACTIVITY_HALO): string =>
  `radial-gradient(${halo.width}px ${halo.height}px at ${halo.x}% ${halo.y}%, ${alpha(color, halo.alpha)} 0%, transparent ${halo.fade}%)`
