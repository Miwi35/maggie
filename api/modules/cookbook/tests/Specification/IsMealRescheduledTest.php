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
        $changeSet = ['startAt' => [new \DateTimeImmutable('2026-10-12 12:00'), new \DateTimeImmutable('2026-10-15 12:00')]];

        self::assertTrue((new IsMealRescheduled($changeSet))->isSatisfiedBy(new Meal()));
    }

    public function testTheSameInstantWrittenAgainIsNot(): void
    {
        $changeSet = ['startAt' => [new \DateTimeImmutable('2026-10-12 12:00'), new \DateTimeImmutable('2026-10-12 12:00')]];

        self::assertFalse((new IsMealRescheduled($changeSet))->isSatisfiedBy(new Meal()));
    }

    public function testPlanningAMealIsNotMovingIt(): void
    {
        $changeSet = ['startAt' => [null, new \DateTimeImmutable('2026-10-12 12:00')]];

        self::assertFalse((new IsMealRescheduled($changeSet))->isSatisfiedBy(new Meal()));
    }

    public function testAnotherFieldChangingIsNot(): void
    {
        self::assertFalse((new IsMealRescheduled(['summary' => ['Dîner', 'Poisson']]))->isSatisfiedBy(new Meal()));
        self::assertFalse((new IsMealRescheduled([]))->isSatisfiedBy(new Meal()));
    }
}
