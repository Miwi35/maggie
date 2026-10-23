<?php

declare(strict_types=1);

namespace Maggie\Core\E2e\User;

/** The data a journey user starts with. */
enum E2eUserProfile: string
{
    /** An account and its preferences, nothing else. */
    case Empty = 'empty';

    /** What the seed gives the journeys' account today, and the neighbour that proves isolation. */
    case Standard = 'standard';

    /** The seed's stock account: a Livret jeune, a mortgage and a project, no neighbour. */
    case Stock = 'stock';
}
