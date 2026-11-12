<?php

declare(strict_types=1);

namespace Maggie\Health\Tests\Service;

use Maggie\Core\Entity\User;
use Maggie\Health\Entity\Workout;
use Maggie\Health\Entity\WorkoutMakeup;
use Maggie\Health\Repository\WorkoutMakeupRepository;
use Maggie\Health\Service\WorkoutMakeupPushData;
use PHPUnit\Framework\TestCase;

final class WorkoutMakeupPushDataTest extends TestCase
{
    public function testTheMinutesOfTheButtonsComeFromTheMakeup(): void
    {
        $makeup = new WorkoutMakeup(new User(), (new Workout())->setStartedAt(new \DateTimeImmutable('2026-11-10 18:30')), 45, 20, 3);
        $repository = $this->createMock(WorkoutMakeupRepository::class);
        $repository->method('find')->with('01JMAKEUP')->willReturn($makeup);

        self::assertSame(
            ['makeupId' => '01JMAKEUP', 'softerMinutes' => '45', 'shorterMinutes' => '20'],
            (new WorkoutMakeupPushData($repository))->dataFor('/api/workout-makeups/01JMAKEUP'),
        );
    }

    public function testAnotherEntityOrAVanishedMakeupGivesNothing(): void
    {
        $repository = $this->createStub(WorkoutMakeupRepository::class);
        $repository->method('find')->willReturn(null);
        $provider = new WorkoutMakeupPushData($repository);

        self::assertSame([], $provider->dataFor('/api/tasks/01JTASK'));
        self::assertSame([], $provider->dataFor('/api/workout-makeups/01JGONE'));
    }
}
