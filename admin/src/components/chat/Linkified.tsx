import Link from '@mui/material/Link'
import { splitLinks } from '../../linkify'

/** A message's text with its web addresses as links that open in a new tab. */
export const Linkified = ({ text }: { text: string }) => (
  <>
    {splitLinks(text).map((part, index) =>
      part.url ? (
        <Link key={index} href={part.url} target="_blank" rel="noopener noreferrer">
          {part.text}
        </Link>
      ) : (
        part.text
      ),
    )}
  </>
)
