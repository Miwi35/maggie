<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** GET /api/home/agenda?day=today|tomorrow (MAG-562). The clock reads 14:12 on 14 October 2026, in Paris. */
class AgendaHomeCardTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-14 14:12:00', new \DateTimeZone('Europe/Paris'))));
        $this->loadFixtures('AgendaHomeCardTest.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function card(string $query = ''): array
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->client->request('GET', '/api/home/agenda'.$query, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $card
     *
     * @return list<string>
     */
    private static function titles(array $card): array
    {
        return array_column($card['events'], 'summary');
    }

    public function testUnauthenticatedIs401(): void
    {
        $this->client->request('GET', '/api/home/agenda');

        self::assertResponseStatusCodeSame(401);
    }

    public function testInvalidDayIs400(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/home/agenda?day=yesterday', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(400);
    }

    public function testTodayListsTheDaysEventsInOrderWithNow(): void
    {
        $card = $this->card('?day=today');

        self::assertSame('agenda', $card['module']);
        self::assertSame('today', $card['day']);
        self::assertSame('2026-10-14', $card['date']);
        self::assertSame('2026-10-14T14:12:00+02:00', $card['now']);
        // Whole-day first, then the timed ones by start — the one begun the evening before leads them.
        self::assertSame(['Séjour', 'Soirée', 'Médicament', 'Réunion équipe', 'Dentiste'], self::titles($card));
    }

    public function testAnAllDayEventBegunTheDayBeforeIsOnTheDay(): void
    {
        $card = $this->card();

        $stay = $card['events'][0];
        self::assertSame('Séjour', $stay['summary']);
        self::assertTrue($stay['allDay']);
        self::assertSame('2026-10-13', $stay['startDate']);
        self::assertSame('2026-10-16', $stay['endDate']);
        self::assertSame('2026-10-15', $stay['lastDay']);
        self::assertNull($stay['startAt']);
    }

    public function testAnAllDayEventEndedTheDayBeforeIsNot(): void
    {
        self::assertNotContains('Colloque', self::titles($this->card()));
    }

    public function testACancelledEventAndAnotherUsersEventAreLeftOut(): void
    {
        $titles = self::titles($this->card());

        self::assertNotContains('Annulé', $titles);
        self::assertNotContains("Rendez-vous d'un autre", $titles);
    }

    public function testAnEventCarriesWhatTheSheetShows(): void
    {
        $dentist = $this->card()['events'][4];

        self::assertSame('Dentiste', $dentist['summary']);
        self::assertSame('2026-10-14T15:00:00+02:00', $dentist['startAt']);
        self::assertSame('2026-10-14T16:00:00+02:00', $dentist['endAt']);
        self::assertSame('Europe/Paris', $dentist['timeZone']);
        self::assertSame('Cabinet Martin, 15 rue de la Paix', $dentist['location']);
        self::assertSame('Cabinet Martin', $dentist['locationShort']);
        self::assertSame('Contrôle annuel', $dentist['description']);
        self::assertSame(['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 30]]], $dentist['reminders']);
        self::assertNull($dentist['activity']);
        self::assertSame('Perso', $dentist['agendaName']);
        self::assertSame('#4285F4', $dentist['agendaColor']);
        self::assertSame((string) $this->getFixture('perso')->getId(), $dentist['agendaId']);
    }

    public function testTomorrowIsTheWholeDayWithoutNow(): void
    {
        $card = $this->card('?day=tomorrow');

        self::assertSame('tomorrow', $card['day']);
        self::assertSame('2026-10-15', $card['date']);
        self::assertNull($card['now']);
        self::assertSame(['Séjour', 'Pont', 'Médicament', 'Plombier'], self::titles($card));
    }

    public function testAnotherUserSeesOnlyTheirOwn(): void
    {
        $this->authenticateAsUser($this->getFixture('other_user'));

        $this->client->request('GET', '/api/home/agenda', [], [], $this->authHeaders());

        $card = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame(["Rendez-vous d'un autre"], self::titles($card));
    }

    public function testADayFarFromTheFixturesKeepsOnlyTheRecurringEvent(): void
    {
        Clock::set(new MockClock(new \DateTimeImmutable('2027-03-02 10:00:00', new \DateTimeZone('Europe/Paris'))));

        $events = $this->card()['events'];

        self::assertSame(['Médicament'], array_column($events, 'summary'));
        self::assertSame('2027-03-02T08:00:00+01:00', $events[0]['startAt']);
    }

    public function testTodayFollowsTheOwnersZoneJustAfterMidnight(): void
    {
        // 00:30 in Paris on the 15th is still the 14th in UTC.
        Clock::set(new MockClock(new \DateTimeImmutable('2026-10-15 00:30:00', new \DateTimeZone('Europe/Paris'))));

        $card = $this->card();

        self::assertSame('2026-10-15', $card['date']);
        self::assertContains('Plombier', self::titles($card));
    }
}
