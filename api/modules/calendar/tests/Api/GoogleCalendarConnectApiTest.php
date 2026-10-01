<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Google\Service\Calendar\CalendarListEntry;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * Connecting a Google calendar is idempotent (MAG-148).
 *
 * In production two agendas ended up sharing the same `google_calendar_id`,
 * each syncing on its own, which duplicated every event. Nothing stopped the
 * same calendar from being imported twice, so the endpoint owns the guarantee.
 */
class GoogleCalendarConnectApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;
    private int $watchCalls = 0;
    /** @var list<string> */
    private array $stoppedChannels = [];

    protected function setUp(): void
    {
        $this->client = self::createClient();
        // The stubbed Google client lives in the test container, and a reboot
        // would build a fresh one — the second request of a reconnect test
        // would then reach the real API.
        $this->client->disableReboot();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function load(): User
    {
        $this->loadFixtures('GoogleCalendarConnectApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        return $user;
    }

    /**
     * @param array<int, array{id: string, summary?: string, summaryOverride?: string, primary?: bool, backgroundColor?: string}> $calendars
     * @param int|null                                                                                                            $watchFailsFromCall the call from which Google refuses to register a push channel
     *
     * Set once per test: the container refuses to replace a service it has
     * already built, so a test that needs Google to start refusing halfway
     * says so up front
     */
    private function stubGoogle(array $calendars, ?int $watchFailsFromCall = null): void
    {
        $entries = [];
        foreach ($calendars as $calendar) {
            $entry = new CalendarListEntry();
            $entry->setId($calendar['id']);
            $entry->setSummary($calendar['summary'] ?? null);
            $entry->setSummaryOverride($calendar['summaryOverride'] ?? null);
            $entry->setPrimary($calendar['primary'] ?? false);
            $entry->setBackgroundColor($calendar['backgroundColor'] ?? null);
            $entries[$calendar['id']] = $entry;
        }

        $apiClient = $this->createMock(GoogleCalendarApiClient::class);
        $apiClient->method('listCalendars')->willReturn(array_values($entries));
        $apiClient->method('getCalendarListEntry')->willReturnCallback(
            fn (User $user, string $calendarId) => $entries[$calendarId]
                ?? throw new \RuntimeException("Unknown calendar {$calendarId}"),
        );
        $this->watchCalls = 0;
        $apiClient->method('watchEvents')->willReturnCallback(
            function () use ($watchFailsFromCall): array {
                ++$this->watchCalls;
                if (null !== $watchFailsFromCall && $this->watchCalls >= $watchFailsFromCall) {
                    throw new \RuntimeException('Google refused the channel');
                }

                return [
                    'channelId' => 'channel-'.$this->watchCalls,
                    'resourceId' => 'resource-'.$this->watchCalls,
                    'expiration' => 1_900_000_000_000,
                ];
            },
        );
        $this->stoppedChannels = [];
        $apiClient->method('stopWatch')->willReturnCallback(
            function (User $user, string $channelId): void {
                $this->stoppedChannels[] = $channelId;
            },
        );
        $apiClient->method('listEvents')->willReturn([
            'events' => [],
            'nextSyncToken' => 'sync-token',
            'nextPageToken' => null,
        ]);

        self::getContainer()->set(GoogleCalendarApiClient::class, $apiClient);
    }

    /** @param array<string, mixed> $payload */
    private function import(array $payload, bool $authenticated = true): void
    {
        $this->client->request(
            'POST',
            '/api/calendar/google/import',
            [],
            [],
            array_merge(
                ['CONTENT_TYPE' => 'application/json'],
                $authenticated ? $this->authHeaders() : [],
            ),
            json_encode($payload, JSON_THROW_ON_ERROR),
        );
    }

    /** @return Agenda[] */
    private function agendasOf(User $user): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Agenda::class)->findBy(['user' => $user->getId()]);
    }

    public function testImportRequiresAuthentication(): void
    {
        $this->load();

        $this->import(['googleCalendarId' => 'cal-1'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testImportRejectsAMissingCalendarId(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'cal-1', 'summary' => 'Concerts']]);

        $this->import([]);

        self::assertResponseStatusCodeSame(400);
    }

    public function testImportCreatesTheAgendaWithTheNameGoogleDisplays(): void
    {
        $user = $this->load();
        $this->stubGoogle([[
            'id' => 'cal-concerts',
            'summary' => 'Concerts',
            'summaryOverride' => 'Mes concerts',
            'backgroundColor' => '#e91e63',
        ]]);

        $this->import(['googleCalendarId' => 'cal-concerts']);

        self::assertResponseStatusCodeSame(201);

        $agendas = $this->agendasOf($user);
        self::assertCount(1, $agendas);
        self::assertSame('Mes concerts', $agendas[0]->getName());
        self::assertSame('cal-concerts', $agendas[0]->getGoogleCalendarId());
        self::assertSame('#e91e63', $agendas[0]->getColor());

        $this->assertMercureUpdatePublished('/agendas/'.$agendas[0]->getId());
        $this->assertElasticsearchIndexDispatched(Agenda::class);
    }

    public function testImportingTheSameCalendarTwiceKeepsASingleAgenda(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'cal-concerts', 'summary' => 'Concerts']]);

        $this->import(['googleCalendarId' => 'cal-concerts']);
        self::assertResponseStatusCodeSame(201);
        $first = $this->agendasOf($user)[0];

        $this->import(['googleCalendarId' => 'cal-concerts']);

        self::assertResponseStatusCodeSame(200);
        $agendas = $this->agendasOf($user);
        self::assertCount(1, $agendas);
        self::assertSame((string) $first->getId(), (string) $agendas[0]->getId());

        $body = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame((string) $first->getId(), $body['id']);
    }

    public function testImportNamesThePrimaryCalendarDefaultAndMakesItTheDefaultAgenda(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'cal-primary', 'summary' => 'fixture@example.com', 'primary' => true]]);

        $this->import(['googleCalendarId' => 'cal-primary']);
        self::assertResponseStatusCodeSame(201);

        $agendas = $this->agendasOf($user);
        self::assertCount(1, $agendas);
        self::assertSame('Défaut', $agendas[0]->getName(), 'The Google primary calendar is named "Défaut", not after the user');
        self::assertTrue($agendas[0]->isDefault(), 'The primary calendar becomes the default agenda when the user has none');
    }

    public function testImportIgnoresANameSentByTheClient(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'cal-primary', 'summary' => 'Fixture User', 'primary' => true]]);

        // The mobile app sends the Google summary as the name; for the primary
        // calendar that is the user's own name, which is what MAG-148 reported.
        $this->import(['googleCalendarId' => 'cal-primary', 'name' => 'Fixture User']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('Défaut', $this->agendasOf($user)[0]->getName());
    }

    public function testReconnectingKeepsThePushChannelItAlreadyHas(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'cal-concerts', 'summary' => 'Concerts']]);

        $this->import(['googleCalendarId' => 'cal-concerts']);
        $this->import(['googleCalendarId' => 'cal-concerts']);

        self::assertResponseStatusCodeSame(200);
        self::assertSame(1, $this->watchCalls, 'A second channel on the same calendar would push everything twice');
        self::assertSame([], $this->stoppedChannels);
    }

    /** Brings the agenda's push channel to the edge of expiry, as the cron would find it. */
    private function expireWatchChannel(Agenda $agenda): void
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->getRepository(Agenda::class)
            ->find($agenda->getId())
            ->setGoogleWatchExpiresAt(new \DateTimeImmutable('+10 minutes'));
        $em->flush();
    }

    public function testReconnectingReplacesAPushChannelAboutToExpire(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'cal-concerts', 'summary' => 'Concerts']]);

        $this->import(['googleCalendarId' => 'cal-concerts']);
        $this->expireWatchChannel($this->agendasOf($user)[0]);

        $this->import(['googleCalendarId' => 'cal-concerts']);

        self::assertResponseStatusCodeSame(200);
        self::assertSame(['channel-1'], $this->stoppedChannels, 'The channel being replaced is closed first');
        self::assertSame('channel-2', $this->agendasOf($user)[0]->getGoogleWatchChannelId());
    }

    public function testAReplacementChannelGoogleRefusesLeavesTheAgendaUpForRenewal(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'cal-concerts', 'summary' => 'Concerts']], watchFailsFromCall: 2);

        $this->import(['googleCalendarId' => 'cal-concerts']);
        $this->expireWatchChannel($this->agendasOf($user)[0]);
        $this->import(['googleCalendarId' => 'cal-concerts']);

        self::assertResponseStatusCodeSame(200);

        // The dead channel's id is kept on purpose: it is what puts the agenda
        // in findWithExpiringWatchChannels, so maggie:google-calendar:renew-watch
        // tries again half an hour later. Clearing it would drop the agenda out
        // of the renewal set for good.
        $agenda = $this->agendasOf($user)[0];
        self::assertSame('channel-1', $agenda->getGoogleWatchChannelId());
        self::assertLessThan(new \DateTimeImmutable('+1 hour'), $agenda->getGoogleWatchExpiresAt());
    }

    public function testConnectingSucceedsEvenWhenGoogleRefusesThePushChannel(): void
    {
        $user = $this->load();
        $this->stubGoogle([['id' => 'cal-concerts', 'summary' => 'Concerts']], watchFailsFromCall: 1);

        $this->import(['googleCalendarId' => 'cal-concerts']);

        // Without a channel the agenda still syncs every five minutes through
        // the cron, so the connection is not worth failing.
        self::assertResponseStatusCodeSame(201);
        $agendas = $this->agendasOf($user);
        self::assertCount(1, $agendas);
        self::assertNull($agendas[0]->getGoogleWatchChannelId());
    }

    public function testImportRejectsACalendarTheUserCannotSee(): void
    {
        $this->load();
        $this->stubGoogle([['id' => 'cal-concerts', 'summary' => 'Concerts']]);

        $this->import(['googleCalendarId' => 'cal-unknown']);

        self::assertResponseStatusCodeSame(404);
    }
}
