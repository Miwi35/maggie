<?php

declare(strict_types=1);

namespace Maggie\Calendar\Service;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
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

    private const INSTANT = '/^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$/';

    public function __construct(
        private readonly ProactionRepository $repository,
        private readonly ModuleAgendas $moduleAgendas,
        private readonly EntityManagerInterface $em,
        private readonly MessageBusInterface $bus,
        private readonly ValidatorInterface $validator,
        private readonly ClockInterface $clock,
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

        if ($at <= $this->clock->now()) {
            throw new \DomainException("scheduled_at {$at->format('c')} has already passed: a proaction runs in the future.");
        }

        return $at;
    }

    public function schedule(User $user, string $prompt, \DateTimeImmutable $at, ?string $summary = null): Proaction
    {
        if ('' === trim($prompt)) {
            throw new \DomainException('prompt is required: what Maggie does when the proaction runs.');
        }

        // A planning run again the same day (a restart, the manual trigger) asks for the
        // same one again: it gets the one already there, not a second (MAG-327).
        $twin = $this->repository->findPendingTwin($user, $at, trim($prompt));
        if (null !== $twin) {
            return $twin;
        }

        // Never the user's own agenda named « Maggie »: proactions were never filed in one.
        [$agenda] = $this->moduleAgendas->forUser($user, Proaction::AGENDA_MODULE, Proaction::AGENDA_NAME, Proaction::AGENDA_COLOR, adoptLegacy: false);

        $proaction = new Proaction();
        $proaction->setPrompt(trim($prompt));
        $proaction->setSummary(null !== $summary && '' !== trim($summary) ? Proaction::summaryOf($summary) : Proaction::summaryOf($prompt));
        $proaction->setTimeZone($agenda->getTimeZone());
        $proaction->scheduleAt($at);
        $agenda->addEvent($proaction);

        $violations = $this->validator->validate($proaction);
        if (count($violations) > 0) {
            throw new \DomainException((string) $violations->get(0)->getMessage());
        }

        $this->em->persist($proaction);
        $this->em->flush();

        return $proaction;
    }

    /** Deletes it the way the agenda does, so it leaves the indices and the screens too. */
    public function cancel(User $user, string $id): void
    {
        $proaction = $this->repository->findOneForUser($id, $user)
            ?? throw new \DomainException("Proaction not found: {$id}");

        $this->bus->dispatch(new DeleteEventCommand(eventId: (string) $proaction->getId()));
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
            $ids = $this->repository->lockDueIds($now, $user, max(1, min($limit, self::CLAIM_LIMIT)), null !== $id ? Ulid::fromString($id) : null);
            if ([] === $ids) {
                return [];
            }

            $claimed = [];
            foreach ($ids as $id) {
                $proaction = $this->repository->find($id);
                if (null !== $proaction) {
                    $claimed[] = $proaction->markRunning();
                }
            }
            $this->em->flush();

            return $claimed;
        });
    }

    /** What the run gave: completed with Maggie's answer, or failed with the error. */
    public function record(?User $user, string $id, string $status, ?string $response, ?string $error): Proaction
    {
        $proaction = $this->repository->findOneForUser($id, $user)
            ?? throw new \DomainException("Proaction not found: {$id}");

        $outcome = ProactionStatus::fromFilter($status);
        if (ProactionStatus::Running !== $proaction->getExecutionStatus()) {
            throw new \DomainException("Proaction {$id} is {$proaction->getExecutionStatus()->value}, not running: claim it with claim_due_proactions first.");
        }

        match ($outcome) {
            ProactionStatus::Completed => $proaction->markCompleted($response, $this->clock->now()),
            ProactionStatus::Failed => $proaction->markFailed(null !== $error && '' !== trim($error) ? $error : 'Unknown error.', $this->clock->now()),
            default => throw new \DomainException('status is completed or failed.'),
        };

        $this->em->flush();

        return $proaction;
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
            'status' => $proaction->getExecutionStatus()->value,
            'response' => $proaction->getResponse(),
            'error' => $proaction->getError(),
            'completedAt' => $proaction->getCompletedAt()?->format('c'),
            'userId' => (string) $proaction->getAgenda()->getUser()->getId(),
        ];
    }
}
