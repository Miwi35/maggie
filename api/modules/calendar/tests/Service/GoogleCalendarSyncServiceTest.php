<?php

namespace Maggie\Calendar\Tests\Service;

use Doctrine\ORM\EntityManagerInterface;
use Google\Service\Calendar\Event as GoogleEvent;
use Google\Service\Calendar\EventDateTime;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Repository\AgendaRepository;
use Maggie\Calendar\Repository\EventRepository;
use Maggie\Calendar\Service\GoogleCalendarApiClient;
use Maggie\Calendar\Service\GoogleCalendarSyncService;
use Maggie\Calendar\Service\GoogleEventMapper;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;
use Psr\Log\NullLogger;
use Symfony\Component\Mercure\HubInterface;
use Symfony\Component\Mercure\Update;
use Symfony\Component\Messenger\Envelope;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Uid\Ulid;

class GoogleCalendarSyncServiceTest extends TestCase
{
    /** @var Update[] */
    private array $publishedUpdates = [];
    /** @var object[] */
    private array $dispatchedMessages = [];
    private HubInterface $hub;
    private MessageBusInterface $messageBus;
    private GoogleCalendarApiClient $apiClient;
    private GoogleEventMapper $eventMapper;
    private EventRepository $eventRepository;
    private AgendaRepository $agendaRepository;
    private EntityManagerInterface $entityManager;

    protected function setUp(): void
    {
        $this->publishedUpdates = [];
        $this->dispatchedMessages = [];
        $this->hub = $this->createMock(HubInterface::class);
        $this->hub->method('publish')->willReturnCallback(function (Update $update) {
            $this->publishedUpdates[] = $update;

            return 'urn:uuid:'.new Ulid();
        });

        $this->messageBus = $this->createMock(MessageBusInterface::class);
        $this->messageBus->method('dispatch')->willReturnCallback(function (object $message) {
            $this->dispatchedMessages[] = $message;

            return new Envelope($message);
        });

        $this->apiClient = $this->createMock(GoogleCalendarApiClient::class);
        $this->eventMapper = $this->createMock(GoogleEventMapper::class);
        $this->eventRepository = $this->createMock(EventRepository::class);
        $this->agendaRepository = $this->createMock(AgendaRepository::class);
        $this->entityManager = $this->createMock(EntityManagerInterface::class);
    }

    private function createService(): GoogleCalendarSyncService
    {
        return new GoogleCalendarSyncService(
            $this->apiClient,
            $this->eventMapper,
            $this->eventRepository,
            $this->agendaRepository,
            $this->entityManager,
            $this->hub,
            $this->messageBus,
            new NullLogger(),
        );
    }

    private function createSyncedAgenda(): Agenda
    {
        $user = new User();
        $user->setEmail('test@example.com');
        $user->setName('Test User');
        $user->setGoogleId('google-123');
        $user->setGoogleAccessToken('access-token');
        $user->setGoogleRefreshToken('refresh-token');

        $agenda = new Agenda();
        $agenda->setUser($user);
        $agenda->setName('Test Agenda');
        $agenda->setGoogleCalendarId('google-cal-id');

        return $agenda;
    }

    private function createGoogleEvent(string $id, string $summary, string $status = 'confirmed'): GoogleEvent
    {
        $googleEvent = new GoogleEvent();
        $googleEvent->setId($id);
        $googleEvent->setSummary($summary);
        $googleEvent->setStatus($status);

        $start = new EventDateTime();
        $start->setDateTime('2026-03-20T10:00:00+01:00');
        $googleEvent->setStart($start);

        $end = new EventDateTime();
        $end->setDateTime('2026-03-20T11:00:00+01:00');
        $googleEvent->setEnd($end);

        return $googleEvent;
    }

    public function testPullPublishesMercureUpdateForEachEvent(): void
    {
        $agenda = $this->createSyncedAgenda();

        $googleEvent1 = $this->createGoogleEvent('g-evt-1', 'Meeting');
        $googleEvent2 = $this->createGoogleEvent('g-evt-2', 'Lunch');

        $event1 = new Event();
        $event1->setSummary('Meeting');
        $event1->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event1->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $event1->setAgenda($agenda);

        $event2 = new Event();
        $event2->setSummary('Lunch');
        $event2->setStartAt(new \DateTimeImmutable('2026-03-20T12:00:00+01:00'));
        $event2->setEndAt(new \DateTimeImmutable('2026-03-20T13:00:00+01:00'));
        $event2->setAgenda($agenda);

        $this->apiClient->method('listEvents')->willReturn([
            'events' => [$googleEvent1, $googleEvent2],
            'nextPageToken' => null,
            'nextSyncToken' => 'new-sync-token',
        ]);

        $this->eventRepository->method('findByGoogleEventId')->willReturn(null);

        $this->eventMapper->method('fromGoogle')->willReturnOnConsecutiveCalls($event1, $event2);

        // After flush + Mercure publish, eventRepository->find() returns the events
        $this->eventRepository->method('find')
            ->willReturnCallback(fn (string $id) => match ($id) {
                (string) $event1->getId() => $event1,
                (string) $event2->getId() => $event2,
                default => null,
            });

        $service = $this->createService();
        $service->pullFromGoogle($agenda);

        self::assertCount(2, $this->publishedUpdates);

        $data1 = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/events/'.$event1->getId(), $data1['@id']);
        self::assertSame('Meeting', $data1['summary']);

        $data2 = json_decode($this->publishedUpdates[1]->getData(), true);
        self::assertSame('/api/events/'.$event2->getId(), $data2['@id']);
        self::assertSame('Lunch', $data2['summary']);
    }

    public function testPullSkipsEventWhenGoogleTimestampNotNewer(): void
    {
        $agenda = $this->createSyncedAgenda();

        $googleEvent = $this->createGoogleEvent('g-evt-1', 'Meeting');
        $googleEvent->setUpdated('2026-03-20T10:00:00Z');

        $existingEvent = new Event();
        $existingEvent->setSummary('Meeting');
        $existingEvent->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $existingEvent->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $existingEvent->setAgenda($agenda);
        $existingEvent->setGoogleEventId('g-evt-1');
        $existingEvent->setGoogleUpdatedAt(new \DateTimeImmutable('2026-03-20T10:00:00Z'));

        $this->apiClient->method('listEvents')->willReturn([
            'events' => [$googleEvent],
            'nextPageToken' => null,
            'nextSyncToken' => 'new-sync-token',
        ]);

        $this->eventRepository->method('findByGoogleEventId')
            ->with('g-evt-1', $agenda)
            ->willReturn($existingEvent);

        // fromGoogle should NOT be called since the event is skipped
        $this->eventMapper->expects(self::never())->method('fromGoogle');

        $this->eventRepository->method('find')
            ->willReturnCallback(fn (string $id) => match ($id) {
                (string) $existingEvent->getId() => $existingEvent,
                default => null,
            });

        $service = $this->createService();
        $service->pullFromGoogle($agenda);

        // Should still publish Mercure update for the returned ID but mapper was not called
        self::assertCount(1, $this->publishedUpdates);
    }

    public function testPullUpdatesEventWhenGoogleTimestampIsNewer(): void
    {
        $agenda = $this->createSyncedAgenda();

        $googleEvent = $this->createGoogleEvent('g-evt-1', 'Updated Meeting');
        $googleEvent->setUpdated('2026-03-20T12:00:00Z');

        $existingEvent = new Event();
        $existingEvent->setSummary('Meeting');
        $existingEvent->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $existingEvent->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $existingEvent->setAgenda($agenda);
        $existingEvent->setGoogleEventId('g-evt-1');
        $existingEvent->setGoogleUpdatedAt(new \DateTimeImmutable('2026-03-20T10:00:00Z'));

        $updatedEvent = new Event();
        $updatedEvent->setSummary('Updated Meeting');
        $updatedEvent->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $updatedEvent->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $updatedEvent->setAgenda($agenda);

        $this->apiClient->method('listEvents')->willReturn([
            'events' => [$googleEvent],
            'nextPageToken' => null,
            'nextSyncToken' => 'new-sync-token',
        ]);

        $this->eventRepository->method('findByGoogleEventId')
            ->with('g-evt-1', $agenda)
            ->willReturn($existingEvent);

        // fromGoogle SHOULD be called since Google timestamp is newer
        $this->eventMapper->expects(self::once())
            ->method('fromGoogle')
            ->with($googleEvent, $agenda, $existingEvent)
            ->willReturn($updatedEvent);

        $this->eventRepository->method('find')
            ->willReturnCallback(fn (string $id) => match ($id) {
                (string) $updatedEvent->getId() => $updatedEvent,
                default => null,
            });

        $service = $this->createService();
        $service->pullFromGoogle($agenda);

        self::assertCount(1, $this->publishedUpdates);
        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('Updated Meeting', $data['summary']);
    }

    public function testPushEventUsePatchWhenChangedFieldsProvided(): void
    {
        $agenda = $this->createSyncedAgenda();
        $user = $agenda->getUser();

        $event = new Event();
        $event->setSummary('Test Event');
        $event->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $event->setAgenda($agenda);
        $event->setGoogleEventId('g-evt-existing');

        $patchGoogleEvent = new GoogleEvent();
        $patchGoogleEvent->setSummary('Test Event');

        $resultGoogleEvent = new GoogleEvent();
        $resultGoogleEvent->setEtag('"new-etag"');
        $resultGoogleEvent->setUpdated('2026-03-20T12:00:00Z');

        $this->eventMapper = new GoogleEventMapper();

        $this->apiClient->expects(self::never())->method('updateEvent');
        $this->apiClient->expects(self::once())
            ->method('patchEvent')
            ->with($user, 'google-cal-id', 'g-evt-existing', self::callback(
                fn (GoogleEvent $e) => 'Test Event' === $e->getSummary() && null === $e->getDescription(),
            ))
            ->willReturn($resultGoogleEvent);

        $service = $this->createService();
        $service->pushEventToGoogle($event, 'update', ['summary']);

        self::assertSame('"new-etag"', $event->getGoogleEtag());
    }

    public function testPullPublishesMercureDeleteForCancelledEvent(): void
    {
        $agenda = $this->createSyncedAgenda();

        $existingEvent = new Event();
        $existingEvent->setSummary('Cancelled Meeting');
        $existingEvent->setStartAt(new \DateTimeImmutable('2026-03-20T10:00:00+01:00'));
        $existingEvent->setEndAt(new \DateTimeImmutable('2026-03-20T11:00:00+01:00'));
        $existingEvent->setAgenda($agenda);
        $eventId = (string) $existingEvent->getId();

        $cancelledGoogleEvent = $this->createGoogleEvent('g-evt-cancelled', 'Cancelled Meeting', 'cancelled');

        $this->apiClient->method('listEvents')->willReturn([
            'events' => [$cancelledGoogleEvent],
            'nextPageToken' => null,
            'nextSyncToken' => 'new-sync-token',
        ]);

        $this->eventRepository->method('findByGoogleEventId')
            ->with('g-evt-cancelled', $agenda)
            ->willReturn($existingEvent);

        // After removal + flush, find() returns null (event deleted)
        $this->eventRepository->method('find')
            ->with($eventId)
            ->willReturn(null);

        $service = $this->createService();
        $service->pullFromGoogle($agenda);

        self::assertCount(1, $this->publishedUpdates);

        $data = json_decode($this->publishedUpdates[0]->getData(), true);
        self::assertSame('/api/events/'.$eventId, $data['@id']);
        self::assertTrue($data['deleted']);
    }
}
