<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

/**
 * How the agenda of an event came to be chosen (MAG-150). Reported next to the created
 * event so the sentence Maggie says — « je l'ai rangé dans Concerts » — is grounded in
 * what actually happened rather than in what she assumes happened.
 */
enum AgendaChoiceKind: string
{
    /** The user named the agenda and it resolved exactly (MAG-230). */
    case Named = 'named';

    /** Nobody named it; the request and the user's own history pointed at one agenda. */
    case Deduced = 'deduced';

    /** Nothing pointed anywhere, so the user's default agenda took it (MAG-149). */
    case Fallback = 'default';

    /** Several agendas fit as well as each other: ask, and create nothing. */
    case Ambiguous = 'ambiguous';

    /** Nothing fits and there is no default agenda: ask. */
    case Ask = 'ask';
}
