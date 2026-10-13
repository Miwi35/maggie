<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetEventsByDateTool;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** MAG-323: Maggie reads meals through the meals tool; an event tool must not list them a second time. */
class EventToolsLeaveMealsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('MealsAmongEvents.yaml');
        $this->loginFixtureUser();
    }

    public function testUpcomingEventsLeaveOutTheMeals(): void
    {
        $data = json_decode(self::getContainer()->get(GetUpcomingEventsTool::class)(7), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(['Tomorrow meeting'], array_column($data['events'], 'summary'));
        self::assertSame(1, $data['count']);
    }

    public function testEventsOfADayLeaveOutTheMeals(): void
    {
        $tomorrow = (new \DateTimeImmutable('tomorrow'))->format('Y-m-d');

        $data = json_decode(self::getContainer()->get(GetEventsByDateTool::class)($tomorrow), true, 512, JSON_THROW_ON_ERROR);

        self::assertSame(['Tomorrow meeting'], array_column($data['events'], 'summary'));
        self::assertSame(1, $data['count']);
    }
}
