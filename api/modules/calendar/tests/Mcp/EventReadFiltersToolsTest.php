<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Mcp\Tool\GetEventsByDateTool;
use Maggie\Calendar\Mcp\Tool\GetUpcomingEventsTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * get_upcoming_events and get_events_by_date narrow to some agendas and find an event by its
 * title, however far away it is (MAG-325).
 */
class EventReadFiltersToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('EventReadFiltersToolsTest.yaml');
    }

    /** @return array<string, mixed> */
    private function upcoming(?int $days = null, ?array $agendas = null, ?string $query = null): array
    {
        return json_decode(
            (self::getContainer()->get(GetUpcomingEventsTool::class))($days, $agendas, $query),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    /** @return array<string, mixed> */
    private function byDate(string $date, ?array $agendas = null, ?string $query = null): array
    {
        return json_decode(
            (self::getContainer()->get(GetEventsByDateTool::class))($date, $agendas, $query),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    private function tomorrow(): string
    {
        return (new \DateTimeImmutable('tomorrow', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return list<string>
     */
    private function summaries(array $data): array
    {
        $summaries = array_map(static fn (array $event) => $event['summary'], $data['events']);
        sort($summaries);

        return $summaries;
    }

    public function testWithoutAgendasNothingChanges(): void
    {
        $this->loginFixtureUser();

        $data = $this->upcoming(7);

        self::assertSame(['Dentiste', 'Récupérer Ael à l\'école', 'Squash'], $this->summaries($data));
    }

    public function testUpcomingOnlyReturnsTheNamedAgendas(): void
    {
        $this->loginFixtureUser();

        $data = $this->upcoming(7, ['défaut', 'garde ael']);

        self::assertSame(['Dentiste', 'Récupérer Ael à l\'école'], $this->summaries($data));
        self::assertSame(2, $data['count']);
        foreach ($data['events'] as $event) {
            self::assertContains($event['agenda'], ['Défaut', 'Garde Ael']);
        }
    }

    public function testUpcomingTakesAnAgendaIdToo(): void
    {
        $this->loginFixtureUser();
        $sport = (string) $this->getFixture('sport_agenda')->getId();

        $data = $this->upcoming(7, [$sport]);

        self::assertSame(['Squash'], $this->summaries($data));
    }

    public function testUpcomingWithAnUnknownAgendaFailsAndListsTheUsersAgendas(): void
    {
        $this->loginFixtureUser();

        $data = $this->upcoming(7, ['Défaut', 'Garde Aël et Cie']);

        self::assertArrayNotHasKey('events', $data);
        self::assertStringContainsString('Garde Aël et Cie', $data['error']);
        foreach (['Défaut', 'Garde Ael', 'Sport'] as $name) {
            self::assertStringContainsString($name, $data['error']);
        }
        self::assertStringNotContainsString('Agenda secret du voisin', $data['error']);
    }

    public function testAnotherUsersAgendaIsUnknownToTheCaller(): void
    {
        $this->loginFixtureUser();
        $theirs = (string) $this->getFixture('other_secret_agenda')->getId();

        $data = $this->upcoming(7, [$theirs]);

        self::assertArrayNotHasKey('events', $data);
        self::assertArrayHasKey('error', $data);
    }

    public function testEventsByDateOnlyReturnsTheNamedAgendas(): void
    {
        $this->loginFixtureUser();

        $data = $this->byDate($this->tomorrow(), ['Sport']);

        self::assertSame(['Squash'], $this->summaries($data));
    }

    public function testEventsByDateWithAnUnknownAgendaFails(): void
    {
        $this->loginFixtureUser();

        $data = $this->byDate($this->tomorrow(), ['Sports']);

        self::assertArrayNotHasKey('events', $data);
        self::assertStringContainsString('Sports', $data['error']);
        self::assertStringContainsString('Garde Ael', $data['error']);
    }

    public function testASeriesStartingBeyondTheWindowIsFoundByItsTitle(): void
    {
        $this->loginFixtureUser();

        self::assertNotContains('Piano', $this->summaries($this->upcoming(7)));

        $data = $this->upcoming(null, null, 'piano');

        self::assertSame(['Piano'], $this->summaries($data));
        self::assertTrue($data['events'][0]['recurring']);
        self::assertSame('FREQ=WEEKLY', $data['events'][0]['rrule']);
        self::assertSame((string) $this->getFixture('piano_series')->getId(), $data['events'][0]['id']);
    }

    public function testASeriesIsListedOnceNotOncePerOccurrence(): void
    {
        $this->loginFixtureUser();

        $data = $this->upcoming(null, null, 'piano');

        self::assertSame(1, $data['count']);
    }

    public function testTheTitleToleratesCaseAccentsAndTypos(): void
    {
        $this->loginFixtureUser();

        foreach (['PIANO', 'piano du jeudi', 'pianno', 'récupérer ael', 'squash'] as $query) {
            self::assertNotSame(0, $this->upcoming(null, null, $query)['count'], $query);
        }
        self::assertSame(['Récupérer Ael à l\'école'], $this->summaries($this->upcoming(null, null, 'recuperer ael ecole')));
    }

    public function testALigatureInTheTitleIsFoundWithoutIt(): void
    {
        $this->loginFixtureUser();

        foreach (['boeuf bourguignon', 'Bœuf bourguignon', 'BOEUF', 'bourguignon'] as $query) {
            $data = $this->upcoming(null, null, $query);
            self::assertSame(['Bœuf bourguignon'], $this->summaries($data), $query);
            self::assertSame('Défaut', $data['events'][0]['agenda'], $query);
        }
    }

    public function testASearchThatFindsNothingPointsToTheMealsTool(): void
    {
        $this->loginFixtureUser();

        $data = $this->upcoming(null, null, 'lasagnes');
        self::assertSame(0, $data['count']);
        self::assertStringContainsString('manage_meals', $data['hint']);
        self::assertArrayHasKey('hint', $this->byDate($this->tomorrow(), null, 'lasagnes'));

        self::assertArrayNotHasKey('hint', $this->upcoming(null, null, 'piano'));
        self::assertArrayNotHasKey('hint', $this->upcoming(7));
    }

    public function testTheTitleLeavesOutWhatDoesNotMatch(): void
    {
        $this->loginFixtureUser();

        $data = $this->upcoming(null, null, 'dentiste');

        self::assertSame(['Dentiste'], $this->summaries($data));
    }

    public function testAnExplicitWindowStillBoundsATitleSearch(): void
    {
        $this->loginFixtureUser();

        self::assertSame(0, $this->upcoming(7, null, 'piano')['count']);
        self::assertSame(1, $this->upcoming(10, null, 'piano')['count']);
    }

    public function testTitleAndAgendasCombine(): void
    {
        $this->loginFixtureUser();

        self::assertSame(['Piano'], $this->summaries($this->upcoming(null, ['Garde Ael'], 'piano')));
        self::assertSame(0, $this->upcoming(null, ['Défaut'], 'piano')['count']);
    }

    public function testTitleSearchByDate(): void
    {
        $this->loginFixtureUser();

        $data = $this->byDate($this->tomorrow(), null, 'squash');

        self::assertSame(['Squash'], $this->summaries($data));
    }

    public function testATitleSearchNeverReturnsTheNeighboursEvents(): void
    {
        $this->loginFixtureUser();

        self::assertNotContains('Piano du voisin', $this->summaries($this->upcoming(null, null, 'piano')));
    }

    public function testTimesAreReadInTheEventsOwnZone(): void
    {
        $this->loginFixtureUser();

        $event = $this->upcoming(null, null, 'dentiste')['events'][0];

        self::assertStringStartsWith($this->tomorrow().'T10:00:00', $event['startAt']);
        self::assertSame('Europe/Paris', $event['timeZone']);
    }

    public function testAQueryWithNoWordToLookForIsRefusedNotIgnored(): void
    {
        $this->loginFixtureUser();

        foreach (['ab', 'les'] as $query) {
            $upcoming = $this->upcoming(null, null, $query);
            self::assertArrayNotHasKey('events', $upcoming, $query);
            self::assertStringContainsString('no word to look for', $upcoming['error']);
            self::assertArrayHasKey('error', $this->byDate($this->tomorrow(), null, $query), $query);
        }
    }

    public function testBothToolsRefuseAnAnonymousCaller(): void
    {
        self::assertSame(MissingMcpUserException::MESSAGE, $this->upcoming(7, ['Défaut'], 'piano')['error']);
        self::assertSame(MissingMcpUserException::MESSAGE, $this->byDate($this->tomorrow(), ['Défaut'], 'piano')['error']);
    }
}
