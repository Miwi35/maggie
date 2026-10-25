<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * `GET /api/events?expand=true` unfolds the series of a period into their occurrences (MAG-589):
 * what the Accueil's Agenda card reads, so no front runs the RRULE rules for it.
 */
class EventExpandApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    /** The 11th of March 2037, Paris. */
    private const DAY = [
        'endAt[after]' => '2037-03-11T00:00:00+01:00',
        'startAt[before]' => '2037-03-12T00:00:00+01:00',
    ];

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('EventExpandApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);
    }

    /**
     * @param array<string, string> $query
     *
     * @return array<string, mixed>
     */
    private function get(array $query, bool $authenticated = true): array
    {
        $this->client->request('GET', '/api/events?'.http_build_query($query), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $authenticated ? $this->authHeaders() : []));

        $content = (string) $this->client->getResponse()->getContent();

        return '' === $content ? [] : json_decode($content, true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $data
     *
     * @return list<string>
     */
    private static function summaries(array $data): array
    {
        return array_column($data['member'], 'summary');
    }

    public function testTheDayHoldsEveryEventOfItWithItsSeriesUnfolded(): void
    {
        $data = $this->get(['expand' => 'true'] + self::DAY);

        self::assertResponseStatusCodeSame(200);
        $summaries = self::summaries($data);
        sort($summaries);
        // The one-off, the all-day one, the one begun the day before, the moved occurrence and the yearly all-day series.
        self::assertSame(['Anniversaire', 'Dentiste', 'Fête', 'Garde de nuit', 'Point hebdo (déplacé)'], $summaries);
        self::assertSame(5, $data['totalItems']);
    }

    public function testAnOccurrenceCarriesItsOwnTimesAndTheSeriesId(): void
    {
        $data = $this->get(['expand' => 'true'] + self::DAY);

        $weekly = array_values(array_filter($data['member'], static fn (array $e): bool => str_starts_with($e['summary'], 'Point hebdo')));
        self::assertCount(1, $weekly, 'the occurrence of the 11th is the moved one, not the series twice');
        self::assertStringStartsWith('2037-03-11T17:00:00', $weekly[0]['startAt']);
    }

    public function testASeriesWithoutExceptionGivesAnOccurrenceOnTheDay(): void
    {
        $data = $this->get(['expand' => 'true', 'endAt[after]' => '2037-03-18T00:00:00+01:00', 'startAt[before]' => '2037-03-19T00:00:00+01:00']);

        self::assertResponseStatusCodeSame(200);
        $weekly = array_values(array_filter($data['member'], static fn (array $e): bool => 'Point hebdo' === $e['summary']));
        self::assertCount(1, $weekly);
        self::assertStringStartsWith('2037-03-18T09:00:00', $weekly[0]['startAt']);
        self::assertStringStartsWith('2037-03-18T10:00:00', $weekly[0]['endAt']);
        self::assertSame('FREQ=WEEKLY', $weekly[0]['rrule']);
    }

    public function testAnAllDaySeriesIsUnfoldedOnItsDays(): void
    {
        $data = $this->get(['expand' => 'true'] + self::DAY);

        $party = array_values(array_filter($data['member'], static fn (array $e): bool => 'Fête' === $e['summary']));
        self::assertCount(1, $party);
        self::assertSame('2037-03-11', $party[0]['startDate']);
        self::assertSame('2037-03-12', $party[0]['endDate']);
        self::assertArrayNotHasKey('startAt', $party[0]);
    }

    public function testACancelledEventAndAFinishedSeriesAreLeftOut(): void
    {
        $summaries = self::summaries($this->get(['expand' => 'true'] + self::DAY));

        self::assertNotContains('Annulé', $summaries);
        self::assertNotContains('Cours terminé', $summaries);
        self::assertNotContains('Médicament', $summaries);
    }

    public function testTheTimedOnesComeByStart(): void
    {
        $data = $this->get(['expand' => 'true'] + self::DAY);

        $timed = array_values(array_filter($data['member'], static fn (array $e): bool => !$e['allDay']));
        self::assertSame(['Garde de nuit', 'Dentiste', 'Point hebdo (déplacé)'], array_column($timed, 'summary'));
    }

    public function testAnotherUsersEventsAreNeverServed(): void
    {
        self::assertNotContains('Stranger', self::summaries($this->get(['expand' => 'true'] + self::DAY)));
    }

    public function testWithoutExpandTheSeriesStaysOneRow(): void
    {
        $data = $this->get(self::DAY);

        self::assertResponseStatusCodeSame(200);
        self::assertNotContains('Point hebdo', self::summaries($data));
        self::assertNotContains('Fête', self::summaries($data));
    }

    public function testExpandFalseIsTheCollectionAsBefore(): void
    {
        $plain = $this->get(self::DAY);
        $data = $this->get(['expand' => 'false'] + self::DAY);

        self::assertResponseStatusCodeSame(200);
        self::assertSame(self::summaries($plain), self::summaries($data));
    }

    public function testExpandNeedsThePeriod(): void
    {
        $this->get(['expand' => 'true', 'endAt[after]' => '2037-03-11T00:00:00+01:00']);
        self::assertResponseStatusCodeSame(400);

        $this->get(['expand' => 'true']);
        self::assertResponseStatusCodeSame(400);
    }

    public function testExpandAndABoundThatIsNotADateAreRefused(): void
    {
        $this->get(['expand' => 'true', 'endAt[after]' => 'tomorrow-ish', 'startAt[before]' => '2037-03-12T00:00:00+01:00']);
        self::assertResponseStatusCodeSame(400);

        $this->get(['expand' => 'maybe'] + self::DAY);
        self::assertResponseStatusCodeSame(400);
    }

    public function testAnAnonymousReadIsRefused(): void
    {
        $this->get(['expand' => 'true'] + self::DAY, authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }
}
