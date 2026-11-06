<?php

declare(strict_types=1);

namespace Maggie\Calendar\Reminder;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Trip;
use Maggie\Calendar\Message\ReplanTripsCommand;
use Maggie\Calendar\Repository\TripRepository;
use Maggie\Calendar\Service\TripRecheck;
use Maggie\Calendar\Service\TripRecheckOutcome;
use Maggie\Core\Reminder\ReminderReport;
use Maggie\Core\Reminder\ReminderSourceInterface;
use Psr\Log\LoggerInterface;
use Symfony\Component\DependencyInjection\Attribute\AsTaggedItem;
use Symfony\Component\DependencyInjection\Attribute\Autowire;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * The checks before a departure (MAG-730), without the model: at `departAt` − 30 and − 10 minutes the
 * trip is computed again by {@see TripRecheck}. Only a trip that needs a choice wakes Maggie, through the
 * « Replanifier les trajets » proaction.
 *
 * A check is owed once per checkpoint: the trip is checked when the latest checkpoint passed is
 * later than its `lastCheckedAt`. A `departAt` that moved takes its checkpoints with it.
 */
#[AsTaggedItem(priority: 20)]
final class TripCheckSource implements ReminderSourceInterface
{
    /** Minutes before `departAt` at which the trip is checked. */
    public const array CHECK_OFFSETS = [30, 10];

    public function __construct(
        private readonly TripRepository $tripRepository,
        #[Autowire(lazy: true)]
        private readonly TripRecheck $recheck,
        private readonly EntityManagerInterface $em,
        #[Autowire(service: 'messenger.bus.default')]
        private readonly MessageBusInterface $commandBus,
        private readonly LoggerInterface $logger,
    ) {
    }

    public function remind(\DateTimeImmutable $now): ReminderReport
    {
        $created = 0;
        $lines = [];
        $warnings = [];

        $window = new \DateInterval('PT'.max(self::CHECK_OFFSETS).'M');
        foreach ($this->tripRepository->findLeavingWithin($now, $window) as $trip) {
            if (!$this->isDue($trip, $now)) {
                continue;
            }

            $summary = $trip->getEvent()->getSummary();
            try {
                $trip->markChecked($now);
                $this->em->flush();

                $outcome = $this->recheck->recheck($trip);
                if (TripRecheckOutcome::NeedsChoice === $outcome) {
                    $this->commandBus->dispatch(new ReplanTripsCommand(
                        (string) $trip->getEvent()->getAgenda()->getUser()->getId(),
                        ReplanTripsCommand::DEPARTURE_CHECK,
                    ));
                }
                ++$created;
                $lines[] = \sprintf('  Checked trip to "%s": %s', $summary, $outcome->value);
            } catch (\Throwable $e) {
                $this->logger->error('Trip check failed for "{event}": {error}', ['event' => $summary, 'error' => $e->getMessage(), 'exception' => $e]);
                $warnings[] = \sprintf('Trip check failed for "%s": %s', $summary, $e->getMessage());
            }
        }

        return new ReminderReport($created, $lines, $warnings);
    }

    private function isDue(Trip $trip, \DateTimeImmutable $now): bool
    {
        $lastChecked = $trip->getLastCheckedAt();
        foreach (self::CHECK_OFFSETS as $offset) {
            $checkpoint = $trip->getDepartAt()->modify(\sprintf('-%d minutes', $offset));
            if ($checkpoint <= $now && (null === $lastChecked || $lastChecked < $checkpoint)) {
                return true;
            }
        }

        return false;
    }
}
