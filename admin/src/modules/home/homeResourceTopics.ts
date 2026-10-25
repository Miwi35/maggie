import { ROUTINE_TOPICS } from '../calendar/routinesApi'

/**
 * Every card of the Accueil that reads business resources listens on this one array, so the browser opens
 * one stream for all of them: over HTTP/1.1 it keeps six connections per origin and each EventSource holds
 * one for good, so a stream per card leaves the page unable to send a request (MAG-473). A card that moves
 * to the resources adds its topics here instead of declaring its own.
 */
export const HOME_RESOURCE_TOPICS = ['/api/tasks/{id}', ...ROUTINE_TOPICS, '/api/events/{id}', '/api/agendas/{id}']
