<?php

declare(strict_types=1);

namespace Maggie\Core\Message;

use Maggie\Core\Enum\Activity;

/**
 * Creates or replaces the user's status of an activity in progress (MAG-613).
 * Checked by the controller: the origin kind belongs to the activity, the
 * state is a JSON object of at most ActivityStatus::MAX_STATE_BYTES.
 */
final readonly class SaveActivityStatusCommand
{
    /**
     * @param string $state a JSON object
     */
    public function __construct(
        public string $userId,
        public Activity $activity,
        public string $originKind,
        public string $originId,
        public string $state,
    ) {
    }
}
