<?php

declare(strict_types=1);

namespace Maggie\Health\Doctrine;

use Doctrine\Bundle\DoctrineBundle\Attribute\AsDoctrineListener;
use Doctrine\ORM\Event\OnFlushEventArgs;
use Doctrine\ORM\Events;
use Maggie\Core\Entity\User;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\DailyActivity;
use Maggie\Health\Entity\FoodIntake;
use Maggie\Health\Entity\HealthProfile;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Event\HealthDaySourceChanged;
use Maggie\Health\Event\HealthJournalStale;
use Maggie\Health\Service\DailyEnergyReader;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Raises HealthDaySourceChanged for each user and local day whose weighings,
 * sessions, intakes or Health Connect's figures a flush stores, changes or
 * removes (MAG-750), whichever door saved them — the API, Maggie, the Health
 * Connect sync, a console command. A row moved to another day raises both
 * days: the one it left must forget it. A late figure (yesterday's steps
 * synced today) thus refreshes its own day.
 *
 * A weighing also moves the weight known on the days after it, and the
 * profile every day's basal metabolism: HealthJournalStale names the first
 * day concerned (none: all of them).
 *
 * Noted during onFlush, sent once per day after the flush and after the
 * projection collector's postFlush.
 */
#[AsDoctrineListener(event: Events::preFlush)]
#[AsDoctrineListener(event: Events::onFlush)]
#[AsDoctrineListener(event: Events::postFlush, priority: -100)]
final class HealthDaySourcesListener
{
    /** The field that places each source on its day. */
    private const DAY_FIELDS = [
        BodyMeasurement::class => 'measuredAt',
        Workout::class => 'startedAt',
        FoodIntake::class => 'date',
        DailyActivity::class => 'date',
    ];

    /** @var array<string, array{string, string}> [userId, date] by "userId date" */
    private array $days = [];

    /** @var array<string, string|null> the first stale day by user id, null for every day */
    private array $stale = [];

    public function __construct(
        #[Autowire(service: 'event.bus')]
        private readonly MessageBusInterface $eventBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    /** A flush that failed after onFlush never reached postFlush: what it noted must not outlive it. */
    public function preFlush(): void
    {
        $this->days = [];
        $this->stale = [];
    }

    public function onFlush(OnFlushEventArgs $args): void
    {
        $uow = $args->getObjectManager()->getUnitOfWork();

        $removedUsers = [];
        foreach ($uow->getScheduledEntityDeletions() as $entity) {
            if ($entity instanceof User) {
                $removedUsers[(string) $entity->getId()] = true;
            }
        }

        foreach ([
            ...$uow->getScheduledEntityInsertions(),
            ...$uow->getScheduledEntityUpdates(),
            ...$uow->getScheduledEntityDeletions(),
        ] as $entity) {
            if ($entity instanceof HealthProfile) {
                $userId = (string) $entity->getUser()->getId();
                if (!isset($removedUsers[$userId])) {
                    $this->stale[$userId] = null;
                }
                continue;
            }

            $field = self::DAY_FIELDS[$entity::class] ?? null;
            if (null === $field) {
                continue;
            }
            \assert($entity instanceof BodyMeasurement || $entity instanceof Workout || $entity instanceof FoodIntake || $entity instanceof DailyActivity);

            $userId = (string) $entity->getUser()->getId();
            if (isset($removedUsers[$userId])) {
                continue;
            }

            $current = match (true) {
                $entity instanceof BodyMeasurement => $entity->getMeasuredAt(),
                $entity instanceof Workout => $entity->getStartedAt(),
                default => $entity->getDate(),
            };
            $isInstant = 'date' !== $field;
            $dates = [$this->note($userId, $current, $isInstant)];

            $before = $uow->getEntityChangeSet($entity)[$field][0] ?? null;
            if ($before instanceof \DateTimeInterface) {
                $dates[] = $this->note($userId, $before, $isInstant);
            }

            if ($entity instanceof BodyMeasurement) {
                $this->staleFrom($userId, min(array_filter($dates)));
            }
        }
    }

    public function postFlush(): void
    {
        // A handler may flush again: forget what is being dispatched before it does.
        $days = $this->days;
        $stale = $this->stale;
        $this->days = [];
        $this->stale = [];

        $events = [];
        foreach ($days as [$userId, $date]) {
            $events[] = new HealthDaySourceChanged($userId, $date);
        }
        foreach ($stale as $userId => $from) {
            $events[] = new HealthJournalStale((string) $userId, $from);
        }

        foreach ($events as $event) {
            // The sources are stored: a failing journal must not turn their save into an error.
            try {
                $this->eventBus->dispatch($event);
            } catch (\Throwable $e) {
                $this->logger->error('Health journal of {user}: {event} failed: {error}', [
                    'user' => $event->userId,
                    'event' => $event::class,
                    'error' => $e->getMessage(),
                    'exception' => $e,
                ]);
            }
        }
    }

    /**
     * A date column is already the local day; an instant is read in Paris, like every day the user gives.
     *
     * @return string|null the day noted
     */
    private function note(string $userId, ?\DateTimeInterface $when, bool $isInstant): ?string
    {
        if (null === $when) {
            return null;
        }
        $date = $isInstant
            ? \DateTimeImmutable::createFromInterface($when)->setTimezone(new \DateTimeZone(DailyEnergyReader::TIME_ZONE))->format('Y-m-d')
            : $when->format('Y-m-d');
        $this->days[$userId.' '.$date] = [$userId, $date];

        return $date;
    }

    private function staleFrom(string $userId, ?string $date): void
    {
        if (null === $date || (\array_key_exists($userId, $this->stale) && (null === $this->stale[$userId] || $this->stale[$userId] <= $date))) {
            return;
        }
        $this->stale[$userId] = $date;
    }
}
