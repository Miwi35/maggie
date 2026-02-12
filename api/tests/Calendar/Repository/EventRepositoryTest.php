<?php

namespace App\Tests\Calendar\Repository;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\EventRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class EventRepositoryTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private EventRepository $repository;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->repository = self::getContainer()->get('doctrine.orm.entity_manager')
            ->getRepository(Event::class);
    }

    public function testFindByDateRangeReturnsEventsInRange(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findByDateRange(
            new \DateTimeImmutable('2026-03-01 00:00'),
            new \DateTimeImmutable('2026-03-31 23:59'),
        );

        $summaries = array_map(fn(Event $e) => $e->getSummary(), $results);
        self::assertContains('In range', $summaries);
        self::assertNotContains('Out of range', $summaries);
    }

    public function testFindByDateRangeExcludesCancelledEvents(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findByDateRange(
            new \DateTimeImmutable('2026-03-01 00:00'),
            new \DateTimeImmutable('2026-03-31 23:59'),
        );

        $summaries = array_map(fn(Event $e) => $e->getSummary(), $results);
        self::assertContains('In range', $summaries);
        self::assertNotContains('Cancelled', $summaries);
    }

    public function testFindByDateRangeIncludesRecurringMasters(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findByDateRange(
            new \DateTimeImmutable('2026-06-01 00:00'),
            new \DateTimeImmutable('2026-06-30 23:59'),
        );

        self::assertCount(1, $results);
        self::assertSame('Weekly meeting', $results[0]->getSummary());
    }

    public function testFindUpcomingReturnsEventsWithinDays(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findUpcoming(7);

        $summaries = array_map(fn(Event $e) => $e->getSummary(), $results);
        self::assertContains('Soon', $summaries);
        self::assertNotContains('Far away', $summaries);
    }

    public function testFindExceptionsForRecurringEvent(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        /** @var Event $parent */
        $parent = $this->getFixture('event_recurring');

        $exceptions = $this->repository->findExceptionsForRecurringEvent($parent);

        self::assertCount(1, $exceptions);
        self::assertSame('Modified meeting', $exceptions[0]->getSummary());
    }
}
