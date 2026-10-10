<?php

namespace Maggie\Calendar\Tests\Repository;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Core\Entity\User;
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

    private function user(): User
    {
        return $this->getFixture('test_user');
    }

    public function testFindByDateRangeReturnsEventsInRange(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findByDateRange(
            $this->user(),
            new \DateTimeImmutable('2026-03-01 00:00'),
            new \DateTimeImmutable('2026-03-31 23:59'),
        );

        $summaries = array_map(fn (Event $e) => $e->getSummary(), $results);
        self::assertContains('In range', $summaries);
        self::assertNotContains('Out of range', $summaries);
    }

    public function testFindByDateRangeExcludesCancelledEvents(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findByDateRange(
            $this->user(),
            new \DateTimeImmutable('2026-03-01 00:00'),
            new \DateTimeImmutable('2026-03-31 23:59'),
        );

        $summaries = array_map(fn (Event $e) => $e->getSummary(), $results);
        self::assertContains('In range', $summaries);
        self::assertNotContains('Cancelled', $summaries);
    }

    public function testFindByDateRangeIncludesRecurringMasters(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        // Use a month with no fixture event so only the recurring master is
        // returned. The "tomorrow" / "+60 days" fixtures shift with the clock,
        // so February — before today's date — stays empty regardless of when.
        $results = $this->repository->findByDateRange(
            $this->user(),
            new \DateTimeImmutable('2026-02-01 00:00'),
            new \DateTimeImmutable('2026-02-28 23:59'),
        );

        self::assertCount(1, $results);
        self::assertSame('Weekly meeting', $results[0]->getSummary());
    }

    public function testFindUpcomingReturnsEventsWithinDays(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');

        $results = $this->repository->findUpcoming($this->user(), 7);

        $summaries = array_map(fn (Event $e) => $e->getSummary(), $results);
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

    /**
     * The owner's report, in the reads Maggie answers from: a birthday on the
     * 1st is in the day of the 1st and not in the day of the 2nd (MAG-382).
     * The days are read in Paris, whatever offset the bounds carry.
     */
    public function testAnAllDayEventIsInTheDaysItCoversOnly(): void
    {
        $this->loadFixtures('EventRepositoryTest.yaml');
        $summaries = fn (string $from, string $to) => array_map(
            fn (Event $e) => $e->getSummary(),
            array_filter(
                $this->repository->findByDateRange($this->user(), new \DateTimeImmutable($from), new \DateTimeImmutable($to)),
                fn (Event $e) => !$e->isRecurring(),
            ),
        );

        self::assertContains('Anniversaire', $summaries('2037-01-01T00:00:00+01:00', '2037-01-02T00:00:00+01:00'));
        self::assertNotContains('Anniversaire', $summaries('2037-01-02T00:00:00+01:00', '2037-01-03T00:00:00+01:00'));
        self::assertNotContains('Anniversaire', $summaries('2037-01-01T23:00:00Z', '2037-01-02T23:00:00Z'));
        self::assertNotContains('Anniversaire', $summaries('2036-12-31T00:00:00+01:00', '2037-01-01T00:00:00+01:00'));

        $onTheFirst = $this->repository->findByDate($this->user(), new \DateTimeImmutable('2037-01-01', new \DateTimeZone('Europe/Paris')));
        self::assertContains('Anniversaire', array_map(fn (Event $e) => $e->getSummary(), $onTheFirst));
    }
}
