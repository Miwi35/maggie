<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EventApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    public function testGetEventCollection(): void
    {
        $this->loadFixtures('EventApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('GET', '/api/events', [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('content-type', 'application/ld+json; charset=utf-8');

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('member', $data);
        self::assertCount(2, $data['member']);
    }

    public function testGetSingleEvent(): void
    {
        $this->loadFixtures('EventApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $event = $this->getFixture('event_1');

        $this->client->request('GET', '/api/events/'.$event->getId(), [], [], array_merge(
            ['HTTP_ACCEPT' => 'application/ld+json'],
            $this->authHeaders(),
        ));

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('Event 1', $data['summary']);
    }

    public function testCreateEvent(): void
    {
        $this->loadFixtures('EventApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $agenda = $this->getFixture('test_agenda');

        $this->client->request('POST', '/api/events', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'summary' => 'New Event',
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'agenda' => '/api/agendas/'.$agenda->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('New Event', $data['summary']);
    }

    public function testCreateEventValidationError(): void
    {
        $this->loadFixtures('EventApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $agenda = $this->getFixture('test_agenda');

        $this->client->request('POST', '/api/events', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            // Missing required 'summary'
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'agenda' => '/api/agendas/'.$agenda->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }

    public function testCreateEventWithUnknownTimeZoneIsRefused(): void
    {
        $this->post([
            'summary' => 'On Mars',
            'timeZone' => 'Mars/Olympus',
        ]);

        self::assertResponseStatusCodeSame(422);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertNull($em->getRepository(Event::class)->findOneBy(['summary' => 'On Mars']));
    }

    /**
     * Reminders set on creation are stored (MAG-121).
     *
     * They used to be dropped in silence: `CreateEventCommand` carried no
     * reminders, so a client that sent them got a 201 and an event nobody would
     * ever be reminded of — and PATCH was the only way in, which is why the
     * smoke journey had to send two requests for one event.
     */
    public function testCreateEventWithReminders(): void
    {
        $reminders = ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 30]]];

        $this->post(['summary' => 'Dentiste', 'reminders' => $reminders]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame($reminders, $this->stored('Dentiste')->getReminders());
    }

    /**
     * Google's shape is the only one accepted.
     *
     * A bare list is the shape the e2e seed shipped for a while, and nothing read
     * it: the cron looks under `overrides`, so the event stored a reminder that
     * fired nothing and said nothing. Now that the web, the mobile app and Maggie
     * all write this field, it is refused on the way in.
     */
    public function testABareListOfRemindersIsRefused(): void
    {
        $this->post([
            'summary' => 'Rendez-vous mal formé',
            'reminders' => [['method' => 'popup', 'minutes' => 30]],
        ]);

        self::assertResponseStatusCodeSame(422);
    }

    /**
     * Zero minutes is accepted, because Google writes it.
     *
     * "Au moment de l'événement" comes back from the import as `minutes: 0`, and
     * the validator runs on the whole stored value at every PATCH — so refusing it
     * would make an imported event uneditable on a field nobody touched. The cron
     * skips it and `EventReminders` refuses it, so nothing of ours promises a
     * reminder that never comes.
     */
    public function testAReminderOfZeroMinutesIsStoredBecauseGoogleWritesIt(): void
    {
        $reminders = ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 0]]];

        $this->post(['summary' => 'Rendez-vous sans délai', 'reminders' => $reminders]);

        self::assertResponseStatusCodeSame(201);
        self::assertSame($reminders, $this->stored('Rendez-vous sans délai')->getReminders());
    }

    public function testADelayBeyondWhatGoogleAcceptsIsRefused(): void
    {
        $this->post([
            'summary' => 'Rendez-vous dans deux mois',
            'reminders' => ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 50000]]],
        ]);

        self::assertResponseStatusCodeSame(422);
    }

    public function testAnUnknownReminderMethodIsRefused(): void
    {
        $this->post([
            'summary' => 'Rendez-vous par pigeon',
            'reminders' => ['useDefault' => false, 'overrides' => [['method' => 'pigeon', 'minutes' => 30]]],
        ]);

        self::assertResponseStatusCodeSame(422);
    }

    /** POSTs an event on the fixture agenda, filling in everything but what the caller cares about. */
    private function post(array $payload): void
    {
        $this->loadFixtures('EventApiTest.yaml');
        /** @var User $user */
        $user = $this->getFixture('test_user');
        $this->authenticateAsUser($user);

        $this->client->request('POST', '/api/events', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(array_merge([
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'agenda' => '/api/agendas/'.$this->getFixture('test_agenda')->getId(),
        ], $payload), JSON_THROW_ON_ERROR));
    }

    /** An event read back from the database, not from the response that claimed to write it. */
    private function stored(string $summary): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        $event = $em->getRepository(Event::class)->findOneBy(['summary' => $summary]);
        self::assertInstanceOf(Event::class, $event);

        return $event;
    }
}
