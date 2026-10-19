<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Maggie\Calendar\Entity\Reward;
use Maggie\Calendar\Message\GrantPalierCommand;
use Maggie\Core\Entity\User;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The way in for the rewards that are not a task ticked (MAG-509). A palier is
 * a big moment — the Sagas lot calls it at the end of a chapter: a reward of
 * level `palier` with no star, which the constellation does not count, and a
 * message of the user's tone. One per subject: granting it twice returns the
 * first. It goes through the bus so the reward is published on Mercure like
 * any other, whichever door the caller comes from.
 */
class RewardGranter
{
    public function __construct(
        private readonly MessageBusInterface $bus,
    ) {
    }

    /** @throws \InvalidArgumentException on a subject type that is not a Reward's */
    public function grantPalier(User $user, string $subjectType, string $subjectId, string $title): Reward
    {
        if (!\in_array($subjectType, Reward::SUBJECTS, true)) {
            throw new \InvalidArgumentException(\sprintf('subjectType must be one of %s, not "%s".', implode(', ', Reward::SUBJECTS), $subjectType));
        }

        $reward = $this->bus->dispatch(new GrantPalierCommand((string) $user->getId(), $subjectType, $subjectId, $title))
            ->last(HandledStamp::class)?->getResult();
        if (!$reward instanceof Reward) {
            throw new \LogicException('The palier was not laid down.');
        }

        return $reward;
    }
}
