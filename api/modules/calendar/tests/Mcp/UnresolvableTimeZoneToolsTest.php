<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetEventsByDateTool;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** A row that already holds a time zone nobody can resolve must not take the reads down (MAG-256). */
class UnresolvableTimeZoneToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('UnresolvableTimeZoneToolsTest.yaml');
        $this->loginFixtureUser();
    }

    public function testGetUpcomingEventsStillAnswers(): void
    {
        $result = self::getContainer()->get(GetUpcomingEventsTool::class)(7);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('error', $data);
        self::assertSame(3, $data['count']);
        self::assertSame('Stand-up on Mars', $data['events'][0]['summary']);
    }

    public function testGetEventsByDateStillAnswers(): void
    {
        $tomorrow = (new \DateTimeImmutable('tomorrow'))->format('Y-m-d');

        $result = self::getContainer()->get(GetEventsByDateTool::class)($tomorrow);

        $data = json_decode($result, true, 512, JSON_THROW_ON_ERROR);

        self::assertArrayNotHasKey('error', $data);
        self::assertSame(1, $data['count']);
        self::assertSame('Stand-up on Mars', $data['events'][0]['summary']);
    }
}
