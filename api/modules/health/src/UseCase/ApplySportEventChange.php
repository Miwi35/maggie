<?php

declare(strict_types=1);

namespace Maggie\Health\UseCase;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Event;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Repository\WorkoutRepository;
use Symfony\Component\Uid\Ulid;

/**
 * A planned session follows its event in the agenda (MAG-777): moved or
 * resized, it takes the event's start and length; deleted, the session goes.
 *
 * Only a session still planned: one marked as done keeps what was done.
 * Writes only what differs, so the session's own change, filed back into the
 * agenda, finds nothing left to do.
 */
class ApplySportEventChange
{
    public function __construct(
        private readonly WorkoutRepository $workoutRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    /** @return Workout|null the session moved, null when nothing had to change */
    public function moved(string $userId, string $eventId): ?Workout
    {
        if (!Ulid::isValid($eventId)) {
            return null;
        }
        $workout = $this->workoutRepository->findOneByEvent($userId, Ulid::fromString($eventId));
        $event = $workout?->getEvent();
        // An event turned into a whole day has no hour to give the session: it keeps its own.
        if (null === $workout || !$workout->isPlanned() || null === $event || null === $event->getStartAt() || null === $event->getEndAt()) {
            return null;
        }

        $start = $event->getStartAt()->setTimezone(new \DateTimeZone($event->getTimeZone()));
        $minutes = (int) round(($event->getEndAt()->getTimestamp() - $start->getTimestamp()) / 60);
        $minutes = max(1, min(Workout::MAX_DURATION_MINUTES, $minutes));

        if ($workout->getStartedAt()->getTimestamp() === $start->getTimestamp() && $workout->getDurationMinutes() === $minutes) {
            return null;
        }

        $workout->setStartedAt($start)->setDurationMinutes($minutes);
        $this->em->flush();

        return $workout;
    }

    /** Deletes the planned session of a deleted event; always null, the session is gone. */
    public function removed(string $userId, ?string $workoutId): ?Workout
    {
        $workout = null === $workoutId ? null : $this->workoutRepository->findOneOfUser($userId, $workoutId);
        if (null === $workout || !$workout->isPlanned()) {
            return null;
        }

        $this->em->remove($workout);
        $this->em->flush();

        return null;
    }
}
