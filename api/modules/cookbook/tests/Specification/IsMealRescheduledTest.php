<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Specification;

use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Specification\IsMealRescheduled;
use PHPUnit\Framework\TestCase;

class IsMealRescheduledTest extends TestCase
{
    public function testAStartThatMovedIsARescheduling(): void
    {
        $changeSet = ['date' => [new \DateTimeImmutable('2026-10-12'), new \DateTimeImmutable('2026-10-15')]];

        self::assertTrue((new IsMealRescheduled($changeSet))->isSatisfiedBy(new Meal()));
    }

    public function testTheSameDayWrittenAgainIsNot(): void
    {
        $changeSet = ['date' => [new \DateTimeImmutable('2026-10-12'), new \DateTimeImmutable('2026-10-12')]];

        self::assertFalse((new IsMealRescheduled($changeSet))->isSatisfiedBy(new Meal()));
    }

    public function testPlanningAMealIsNotMovingIt(): void
    {
        $changeSet = ['date' => [null, new \DateTimeImmutable('2026-10-12')]];

        self::assertFalse((new IsMealRescheduled($changeSet))->isSatisfiedBy(new Meal()));
    }

    public function testAnotherFieldChangingIsNot(): void
    {
        // A meal's instants stay null (MAG-382): only its day says where it is.
        self::assertFalse((new IsMealRescheduled(['startAt' => [new \DateTimeImmutable('2026-10-12 12:00'), new \DateTimeImmutable('2026-10-15 12:00')]]))->isSatisfiedBy(new Meal()));
        self::assertFalse((new IsMealRescheduled(['summary' => ['Dîner', 'Poisson']]))->isSatisfiedBy(new Meal()));
        self::assertFalse((new IsMealRescheduled([]))->isSatisfiedBy(new Meal()));
    }
}
