<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Entity\ProactionOutcome;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\ProactionKind;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Message\CancelProactionOccurrenceCommand;
use Maggie\Calendar\Message\DeleteEventCommand;
use Maggie\Calendar\Repository\ProactionRepository;
use Maggie\Core\Entity\User;
use Psr\Clock\ClockInterface;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Validator\ValidatorInterface;

/**
 * Maggie's proactions, as events of her internal agenda (ADR-012, MAG-326).
 *
 * Every method is scoped to a user. `claimDue` and `record` also take none: the
 * agent's executor, holding the service token without naming a user, runs the
 * proactions of everyone.
 */
class Proactions
{
    /** At most this many proactions per claim, so one slow run cannot hold a batch. */
    public const CLAIM_LIMIT = 20;

    /** How far back a check reads the topics it already told the owner about (MAG-886). */
    public const SIGNALED_WINDOW = '-7 days';
    public const SIGNALED_LIMIT = 20;

    /** The oldest occurrence of a series a claim still runs: a missed day is not replayed (MAG-974). */
    public const SERIES_CATCH_UP = '-1 day';
    /** The occurrences of one series a listing unfolds. */
    public const UNFOLD_LIMIT = 100;

    private const INSTANT = '/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/';

    public function __construct(
        private readonly ProactionRepository $repository,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ValidatorInterface $validator,
        private readonly ClockInterface $clock,
        private readonly RecurrenceService $recurrence,
    ) {
    }

    /**
     * When a proaction runs, from what the model sends: an ISO 8601 date and time,
     * read in Paris when it carries no offset — the zone every event defaults to.
     *
     * @throws \DomainException when it is not one, or has already passed
     */
    public function instantOf(string $scheduledAt): \DateTimeImmutable
    {
        $at = $this->parseInstant($scheduledAt);
        if ($at <= $this->clock->now()) {
            throw new \DomainException("scheduled_at {$at->format('c')} has already passed: a proaction runs in the future.");
        }

        return $at;
    }

    /** An occurrence is named as listed, past or not. */
    public function parseInstant(string $scheduledAt): \DateTimeImmutable
    {
        $value = trim($scheduledAt);
        if (1 !== preg_match(self::INSTANT, $value)) {
            throw new \DomainException("Not a date and time: \"{$scheduledAt}\". Use ISO 8601, e.g. 2037-01-15T08:30:00+01:00.");
        }

        try {
            $at = new \DateTimeImmutable($value, new \DateTimeZone(Proaction::FALLBACK_TIME_ZONE));
        } catch (\Exception $e) {
            throw new \DomainException("Not a date and time: \"{$scheduledAt}\". Use ISO 8601, e.g. 2037-01-15T08:30:00+01:00.", 0, $e);
        }

        // `new DateTimeImmutable` rolls `2037-02-30` over to March: what was named has to be what was read.
        if ($at->format('Y-m-d') !== substr($value, 0, 10)) {
            throw new \DomainException("Not a date and time: \"{$scheduledAt}\".");
        }

        return $at;
    }

    public function schedule(User $user, string $prompt, \DateTimeImmutable $at, ?string $summary = null, ProactionKind $kind = ProactionKind::Reminder, ?string $rrule = null): Proaction
    {
        if ('' === trim($prompt)) {
            throw new \DomainException('prompt is required: what Maggie does when the proaction runs.');
        }

        // A planning run again the same day (a restart, the manual trigger) asks for the
        // same one again: it gets the one already there, not a second (MAG-327).
        $twin = $this->repository->findPendingTwin($user, $at, trim($prompt), $rrule);
        if (null !== $twin) {
            return $twin;
        }

        // Never the user's own agenda named « Maggie »: proactions were never filed in one.
        [$agenda] = $this->moduleAgendas->forUser($user, Proaction::AGENDA_MODULE, Proaction::AGENDA_NAME, Proaction::AGENDA_COLOR, adoptLegacy: false);

        $proaction = new Proaction();
        $proaction->setPrompt(trim($prompt));
        $proaction->setKind($kind);
        $proaction->setSummary(null !== $summary && '' !== trim($summary) ? Proaction::summaryOf($summary) : Proaction::summaryOf($prompt));
        $proaction->setTimeZone($agenda->getTimeZone());
        $proaction->scheduleAt($at);
        $proaction->setRrule($rrule);
        $agenda->addEvent($proaction);

        $violations = $this->validator->validate($proaction);
        if (count($violations) > 0) {
            throw new \DomainException((string) $violations->get(0)->getMessage());
        }
        if (null !== $rrule) {
            $this->assertOnTheRule($proaction, $at, $rrule);
        }

        $this->em->persist($proaction);
        $this->em->flush();

        return $proaction;
    }

    private function assertOnTheRule(Proaction $series, \DateTimeImmutable $at, string $rrule): void
    {
        $first = $this->recurrence->expandOccurrences($series, $at, $at->modify('+1 year'))[0] ?? null;
        if (null === $first || $first->getStartInstant()->getTimestamp() !== $at->getTimestamp()) {
            $from = null !== $first ? "its first one from then is {$first->getStartInstant()->format('c')} ({$first->getStartInstant()->format('l')})" : 'it has no occurrence from then';
            throw new \DomainException("scheduled_at {$at->format('c')} is not an occurrence of rrule {$rrule}: {$from}.");
        }
    }

    /**
     * Deletes it the way the agenda does, so it leaves the indices and the screens too.
     * With `$occurrence`, one occurrence of a series is detached and cancelled instead.
     */
    public function cancel(User $user, string $id, ?string $occurrence = null): void
    {
        $proaction = $this->repository->findOneForUser($id, $user)
            ?? throw new \DomainException("Proaction not found: {$id}");

        if (null !== $occurrence && '' !== trim($occurrence)) {
            $this->bus->dispatch(new CancelProactionOccurrenceCommand((string) $user->getId(), (string) $proaction->getId(), $this->parseInstant($occurrence)));

            return;
        }

        $this->bus->dispatch(new DeleteEventCommand(eventId: (string) $proaction->getId()));
    }

    public function cancelOccurrence(User $user, string $seriesId, \DateTimeImmutable $occurrence): void
    {
        $series = $this->repository->findOneForUser($seriesId, $user)
            ?? throw new \DomainException("Proaction not found: {$seriesId}");

        $this->detach($user, $series, $occurrence)->setStatus(EventStatus::Cancelled);
        $this->em->flush();
    }

    /**
     * One occurrence of a series as a pending exception instance, whose own status and
     * answer are its alone: the other occurrences are not touched.
     *
     * @throws \DomainException when it is not an occurrence of the rule, or already has an instance
     */
    public function detach(User $user, Proaction $series, \DateTimeImmutable $occurrence): Proaction
    {
        if (!$series->isRecurring() || $series->getAgenda()->getUser()->getId() != $user->getId()) {
            throw new \DomainException("Proaction {$series->getId()} is not a series: an occurrence belongs to a series.");
        }

        $existing = $this->repository->findInstanceAt($series, $occurrence);
        if (null !== $existing) {
            throw new \DomainException("The occurrence {$occurrence->format('c')} already has its own proaction: {$existing->getId()}.");
        }
        if (null === $this->virtualAt($series, $occurrence)) {
            throw new \DomainException("{$occurrence->format('c')} is not an occurrence of rrule {$series->getRrule()}.");
        }

        $instance = $this->instanceOf($series, $occurrence);
        $this->em->persist($instance);

        return $instance;
    }

    private function virtualAt(Proaction $series, \DateTimeImmutable $occurrence): ?Proaction
    {
        foreach ($this->recurrence->expandOccurrences($series, $occurrence, $occurrence->modify('+1 second')) as $candidate) {
            if ($candidate instanceof Proaction && $candidate->getId() == $series->getId() && $candidate->getStartInstant()->getTimestamp() === $occurrence->getTimestamp()) {
                return $candidate;
            }
        }

        return null;
    }

    private function instanceOf(Proaction $series, \DateTimeImmutable $occurrence): Proaction
    {
        $instance = new Proaction();
        $instance->setPrompt($series->getPrompt());
        $instance->setKind($series->getKind());
        $instance->setSummary($series->getSummary());
        $instance->setTimeZone($series->getTimeZone());
        $instance->scheduleAt($occurrence);
        $instance->setRecurringEvent($series);
        $instance->setOriginalStartAt($occurrence);
        $series->getAgenda()->addEvent($instance);

        return $instance;
    }

    /**
     * The coming occurrences of a series in a window that no instance replaces yet.
     *
     * @return list<array<string, mixed>>
     */
    public function occurrencesOf(Proaction $series, \DateTimeImmutable $from, \DateTimeImmutable $to): array
    {
        $now = $this->clock->now();
        $zone = new \DateTimeZone($series->getTimeZone());
        $listed = [];
        foreach ($this->recurrence->expandOccurrences($series, max($from, $now), $to) as $candidate) {
            if (!$candidate instanceof Proaction || $candidate->getId() != $series->getId()) {
                continue;
            }
            $at = $candidate->getStartInstant();
            if ($at <= $now) {
                continue;
            }
            $listed[] = [
                'seriesId' => (string) $series->getId(),
                'occurrence' => $at->setTimezone($zone)->format('c'),
                'scheduledAt' => $at->setTimezone($zone)->format('c'),
                'prompt' => $series->getPrompt(),
                'summary' => $series->getSummary(),
                'kind' => $series->getKind()->value,
            ];
            if (count($listed) >= self::UNFOLD_LIMIT) {
                break;
            }
        }

        return $listed;
    }

    /**
     * The pending proactions whose start has come, marked running in the same
     * transaction that locked them: two executors never get the same one.
     *
     * With `$id`, that pending proaction alone, whether its start has come or
     * not: the manual trigger runs one now (MAG-249, MAG-327).
     *
     * @return Proaction[]
     */
    public function claimDue(?User $user, int $limit = self::CLAIM_LIMIT, ?string $id = null): array
    {
        $now = $this->clock->now();
        if (null !== $id && !Ulid::isValid($id)) {
            return [];
        }

        return $this->em->wrapInTransaction(function () use ($now, $user, $limit, $id): array {
            $limit = max(1, min($limit, self::CLAIM_LIMIT));
            $ids = $this->repository->lockDueIds($now, $user, $limit, null !== $id ? Ulid::fromString($id) : null);

            $claimed = [];
            foreach ($ids as $dueId) {
                $proaction = $this->repository->find($dueId);
                if (null !== $proaction) {
                    $claimed[] = $proaction->markRunning();
                }
            }

            // A series is never claimed itself: the lock on it is what makes one pass
            // of it one instance, however many executors ask at once.
            if (null === $id) {
                foreach ($this->repository->lockDueSeriesIds($now, $user) as $seriesId) {
                    if (count($claimed) >= $limit) {
                        break;
                    }
                    $series = $this->repository->find($seriesId);
                    $instance = null !== $series ? $this->materialize($series, $now) : null;
                    if (null !== $instance) {
                        $claimed[] = $instance;
                    }
                }
            }

            $this->em->flush();

            return $claimed;
        });
    }

    /** The latest occurrence of the series that has come, if no instance holds it or a later one yet. */
    private function materialize(Proaction $series, \DateTimeImmutable $now): ?Proaction
    {
        $due = null;
        foreach ($this->recurrence->expandOccurrences($series, $now->modify(self::SERIES_CATCH_UP), $now->modify('+1 second')) as $candidate) {
            if ($candidate instanceof Proaction && $candidate->getId() == $series->getId() && $candidate->getStartInstant() <= $now) {
                $due = $candidate->getStartInstant();
            }
        }
        if (null === $due) {
            return null;
        }

        // The latest passage is already an instance (run, moved or cancelled): an older one is not replayed.
        foreach ($this->repository->findInstancesOf($series) as $instance) {
            if (null !== $instance->getOriginalStartAt() && $instance->getOriginalStartAt() >= $due) {
                return null;
            }
        }

        $instance = $this->instanceOf($series, $due)->markRunning();
        $this->em->persist($instance);

        return $instance;
    }

    /**
     * What the run gave: completed with Maggie's answer and how it reached the
     * owner (MAG-886), or failed with the error.
     */
    public function record(?User $user, string $id, string $status, ProactionOutcome $outcome, ?string $error): Proaction
    {
        $proaction = $this->repository->findOneForUser($id, $user)
            ?? throw new \DomainException("Proaction not found: {$id}");

        $finished = ProactionStatus::fromFilter($status);
        if (ProactionStatus::Running !== $proaction->getExecutionStatus()) {
            throw new \DomainException("Proaction {$id} is {$proaction->getExecutionStatus()->value}, not running: claim it with claim_due_proactions first.");
        }

        match ($finished) {
            ProactionStatus::Completed => $proaction->markCompleted($outcome, $this->clock->now()),
            ProactionStatus::Failed => $proaction->markFailed(null !== $error && '' !== trim($error) ? $error : 'Unknown error.', $this->clock->now()),
            default => throw new \DomainException('status is completed or failed.'),
        };

        $this->em->flush();

        return $proaction;
    }

    /**
     * The topics a check already told its owner about these last days, so it does
     * not say the same thing twice (MAG-886). A reminder gets none: it is never
     * triaged.
     *
     * @return list<array{topic: string, at: string, delivery: string, notice: ?string}>|null
     */
    public function signaledTopics(Proaction $proaction): ?array
    {
        if (ProactionKind::Check !== $proaction->getKind()) {
            return null;
        }

        $since = $this->clock->now()->modify(self::SIGNALED_WINDOW);

        return array_map(static fn (Proaction $told): array => [
            'topic' => (string) $told->getTopicKey(),
            'at' => (string) $told->getCompletedAt()?->format('c'),
            'delivery' => (string) $told->getDelivery()?->value,
            'notice' => $told->getNotice(),
        ], $this->repository->findSignaledTopics($proaction->getAgenda()->getUser(), $since, self::SIGNALED_LIMIT));
    }

    /**
     * What Maggie reads of one.
     *
     * @return array<string, mixed>
     */
    public static function describe(Proaction $proaction): array
    {
        return [
            'id' => (string) $proaction->getId(),
            'prompt' => $proaction->getPrompt(),
            'summary' => $proaction->getSummary(),
            'scheduledAt' => $proaction->getStartAt()?->setTimezone(new \DateTimeZone($proaction->getTimeZone()))->format('c'),
            'kind' => $proaction->getKind()->value,
            'status' => $proaction->getExecutionStatus()->value,
            'response' => $proaction->getResponse(),
            'delivery' => $proaction->getDelivery()?->value,
            'topicKey' => $proaction->getTopicKey(),
            'error' => $proaction->getError(),
            'completedAt' => $proaction->getCompletedAt()?->format('c'),
            'rrule' => $proaction->getRrule(),
            'seriesId' => null !== $proaction->getRecurringEvent() ? (string) $proaction->getRecurringEvent()->getId() : null,
            'occurrence' => $proaction->getOriginalStartAt()?->setTimezone(new \DateTimeZone($proaction->getTimeZone()))->format('c'),
            'userId' => (string) $proaction->getAgenda()->getUser()->getId(),
        ];
    }
}
