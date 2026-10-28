import type { RaRecord } from 'react-admin'

/** REST serialises `isCushion()` as `cushion`, Mercure publishes `isCushion`: an account carries one or the other. */
export const isCushionAccount = (account: RaRecord): boolean => account.cushion === true || account.isCushion === true
