<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Specification;

use Maggie\Calendar\Entity\Event;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Specification\IsMealRemoved;
use PHPUnit\Framework\TestCase;

class IsMealRemovedTest extends TestCase
{
    public function testAMealAmongTheScheduledDeletionsIsRemoved(): void
    {
        $meal = new Meal();

        self::assertTrue((new IsMealRemoved([new Meal(), $meal]))->isSatisfiedBy($meal));
    }

    public function testAMealNotScheduledForDeletionIsNot(): void
    {
        self::assertFalse((new IsMealRemoved([new Meal()]))->isSatisfiedBy(new Meal()));
        self::assertFalse((new IsMealRemoved([]))->isSatisfiedBy(new Meal()));
    }

    public function testAnEventThatIsNotAMealIsNot(): void
    {
        $event = new Event();

        self::assertFalse((new IsMealRemoved([$event]))->isSatisfiedBy($event));
        self::assertFalse((new IsMealRemoved([new \stdClass()]))->isSatisfiedBy(new \stdClass()));
    }
}
