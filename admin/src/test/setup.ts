import '@testing-library/jest-dom'
import { configure } from '@testing-library/react'

// jsdom doesn't implement scrollIntoView
Element.prototype.scrollIntoView = () => {}

// findBy* and waitFor give up after 1 s by default, which a first CalendarView
// or MUI dialog render under coverage on a shared runner overruns (MAG-248). They
// return as soon as the condition holds, so the wider bound only costs on a real
// failure, and stays below the 30 s testTimeout of vite.config.ts.
configure({ asyncUtilTimeout: 10_000 })
