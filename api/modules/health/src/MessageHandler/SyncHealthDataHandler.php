<?php

declare(strict_types=1);

namespace Maggie\Health\MessageHandler;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Repository\UserRepository;
use Maggie\Health\Dto\HealthSyncResult;
use Maggie\Health\Entity\BodyMeasurement;
use Maggie\Health\Entity\DailyActivity;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Enum\DailyActivitySource;
use Maggie\Health\Enum\WorkoutType;
use Maggie\Health\Message\CreateBodyMeasurementCommand;
use Maggie\Health\Message\CreateWorkoutCommand;
use Maggie\Health\Message\SyncHealthDataCommand;
use Maggie\Health\Message\UpdateBodyMeasurementCommand;
use Maggie\Health\Message\UpdateWorkoutCommand;
use Maggie\Health\Repository\BodyMeasurementRepository;
use Maggie\Health\Repository\DailyActivityRepository;
use Maggie\Health\Repository\WorkoutRepository;
use Symfony\Component\Messenger\Attribute\AsMessageHandler;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Upserts a Health Connect batch (MAG-708). A workout or a measurement goes
 * through its own Create/Update command — the same rules, the same projection
 * as from any other door — and only when something differs: the same batch
 * replayed dispatches nothing and publishes nothing.
 *
 * The batch was validated whole by the controller: what reaches here holds
 * the commands' rules, so nothing is refused half-way.
 *
 * @phpstan-import-type SyncedWorkoutData from SyncHealthDataCommand
 * @phpstan-import-type SyncedMeasurementData from SyncHealthDataCommand
 */
#[AsMessageHandler]
class SyncHealthDataHandler
{
    public function __construct(
        private readonly MessageBusInterface $bus,
        private readonly UserRepository $userRepository,
        private readonly WorkoutRepository $workoutRepository,
        private readonly BodyMeasurementRepository $measurementRepository,
        private readonly DailyActivityRepository $dailyActivityRepository,
        private readonly EntityManagerInterface $em,
    ) {
    }

    public function __invoke(SyncHealthDataCommand $command): HealthSyncResult
    {
        $user = $this->userRepository->find($command->userId)
            ?? throw new \DomainException('User not found.');
        $result = new HealthSyncResult();

        foreach ($command->workouts as $item) {
            $result->count('workouts', $this->syncWorkout($command->userId, $item));
        }

        foreach ($command->measurements as $item) {
            $result->count('measurements', $this->syncMeasurement($command->userId, $item));
        }

        foreach ($command->days as $item) {
            $date = new \DateTimeImmutable($item['date']);
            $day = $this->dailyActivityRepository->findOneByDate($user, $date);
            if (null !== $day && $day->getSteps() === $item['steps'] && $day->getActiveKcal() === $item['activeKcal']) {
                $result->count('days', 'unchanged');
                continue;
            }

            $result->count('days', null === $day ? 'created' : 'updated');
            $day ??= (new DailyActivity())->setUser($user)->setDate($date);
            $day->setSteps($item['steps'])
                ->setActiveKcal($item['activeKcal'])
                ->setSource(DailyActivitySource::HealthConnect);
            $this->em->persist($day);
        }
        $this->em->flush();

        return $result;
    }

    /**
     * @param SyncedWorkoutData $item
     *
     * @return 'created'|'updated'|'unchanged'
     */
    private function syncWorkout(string $userId, array $item): string
    {
        $startedAt = self::toSecond($item['startedAt']);
        $existing = $this->workoutRepository->findOneByExternalId($userId, $item['externalId']);

        if (null === $existing) {
            $this->bus->dispatch(new CreateWorkoutCommand(
                userId: $userId,
                type: $item['type'],
                // A watch session has no title: its type's, as the forms propose it.
                title: $item['title'] ?? WorkoutType::from($item['type'])->label(),
                startedAt: $startedAt,
                durationMinutes: $item['durationMinutes'],
                distanceMeters: $item['distanceMeters'],
                kcal: $item['kcal'],
                avgHeartRate: $item['avgHeartRate'],
                source: $item['source'],
                externalId: $item['externalId'],
            ));

            return 'created';
        }

        // What the user set in Maggie stays: the type, a title renamed, a
        // figure the batch does not carry. The batch only overwrites what it
        // gives, and never clears a field.
        $item = [
            'type' => $existing->getType()->value,
            'title' => $item['title'] ?? $existing->getTitle(),
            'startedAt' => $startedAt,
            'distanceMeters' => $item['distanceMeters'] ?? $existing->getDistanceMeters(),
            'kcal' => $item['kcal'] ?? $existing->getKcal(),
            'avgHeartRate' => $item['avgHeartRate'] ?? $existing->getAvgHeartRate(),
        ] + $item;
        if (self::sameWorkout($existing, $item)) {
            return 'unchanged';
        }

        $this->bus->dispatch(new UpdateWorkoutCommand(
            userId: $userId,
            workoutId: (string) $existing->getId(),
            title: $item['title'],
            startedAt: $startedAt,
            durationMinutes: $item['durationMinutes'],
            distanceMeters: $item['distanceMeters'],
            kcal: $item['kcal'],
            avgHeartRate: $item['avgHeartRate'],
            source: $item['source'],
        ));

        return 'updated';
    }

    /**
     * @param SyncedMeasurementData $item
     *
     * @return 'created'|'updated'|'unchanged'
     */
    private function syncMeasurement(string $userId, array $item): string
    {
        $measuredAt = self::toSecond($item['measuredAt']);
        $existing = $this->measurementRepository->findOneByExternalId($userId, $item['externalId']);

        if (null === $existing) {
            $this->bus->dispatch(new CreateBodyMeasurementCommand(
                userId: $userId,
                measuredAt: $measuredAt,
                weightKg: $item['weightKg'],
                bodyFatPercent: $item['bodyFatPercent'],
                source: $item['source'],
                externalId: $item['externalId'],
            ));

            return 'created';
        }

        // A weighing and a body fat reading are two records in Health Connect:
        // the one not given is kept, never cleared.
        $item['weightKg'] ??= $existing->getWeightKg();
        $item['bodyFatPercent'] ??= $existing->getBodyFatPercent();
        if (self::sameMeasurement($existing, $item, $measuredAt)) {
            return 'unchanged';
        }

        // The waist size and « écarter » are the user's: a batch never touches them.
        $this->bus->dispatch(new UpdateBodyMeasurementCommand(
            userId: $userId,
            measurementId: (string) $existing->getId(),
            measuredAt: $measuredAt,
            weightKg: $item['weightKg'],
            bodyFatPercent: $item['bodyFatPercent'],
            source: $item['source'],
        ));

        return 'updated';
    }

    /** @param SyncedWorkoutData $item */
    private static function sameWorkout(Workout $workout, array $item): bool
    {
        return $workout->getTitle() === $item['title']
            && $workout->getStartedAt()->getTimestamp() === (new \DateTimeImmutable($item['startedAt']))->getTimestamp()
            && $workout->getDurationMinutes() === $item['durationMinutes']
            && $workout->getDistanceMeters() === $item['distanceMeters']
            && $workout->getKcal() === $item['kcal']
            && $workout->getAvgHeartRate() === $item['avgHeartRate']
            && $workout->getSource()->value === $item['source'];
    }

    /** @param SyncedMeasurementData $item */
    private static function sameMeasurement(BodyMeasurement $measurement, array $item, string $measuredAt): bool
    {
        return $measurement->getMeasuredAt()->getTimestamp() === (new \DateTimeImmutable($measuredAt))->getTimestamp()
            && self::sameFigure($measurement->getWeightKg(), $item['weightKg'], 2)
            && self::sameFigure($measurement->getBodyFatPercent(), $item['bodyFatPercent'], 1)
            && $measurement->getSource()->value === $item['source'];
    }

    /** Compared as stored, rounded to the column's scale. */
    private static function sameFigure(?float $stored, ?float $sent, int $scale): bool
    {
        return null === $stored || null === $sent ? $stored === $sent : round($stored, $scale) === round($sent, $scale);
    }

    /**
     * The instant to the second, in its own offset: the columns keep no
     * fraction, and a fraction kept here would make every replay an update.
     */
    private static function toSecond(string $instant): string
    {
        return (new \DateTimeImmutable($instant))->format('Y-m-d\TH:i:sP');
    }
}
