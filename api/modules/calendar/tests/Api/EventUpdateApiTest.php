<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EventUpdateApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('EventUpdateApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    /** @param array<string, mixed> $payload */
    private function patch(array $payload): void
    {
        $this->client->request('PATCH', '/api/events/'.$this->getFixture('event_1')->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Event::class)->find($this->getFixture('event_1')->getId());
    }

    public function testPatchRequiresAuthentication(): void
    {
        $this->client->request('PATCH', '/api/events/'.$this->getFixture('event_1')->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['status' => 'cancelled'], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchCancelsTheEvent(): void
    {
        $this->patch(['status' => 'cancelled']);

        self::assertResponseIsSuccessful();
        $event = $this->reload();
        self::assertSame(EventStatus::Cancelled, $event->getStatus());
        self::assertSame('Dentist', $event->getSummary());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testPatchWithUnknownTimeZoneIsRefusedAndChangesNothing(): void
    {
        $this->patch(['summary' => 'Renamed', 'timeZone' => 'Mars/Olympus']);

        self::assertResponseStatusCodeSame(422);
        $event = $this->reload();
        self::assertSame('Dentist', $event->getSummary());
        self::assertSame('Europe/Paris', $event->getTimeZone());
    }

    public function testPatchMovesTheEventToAnotherAgenda(): void
    {
        $agendaB = $this->getFixture('agenda_b');

        $this->patch(['agenda' => '/api/agendas/'.$agendaB->getId()]);

        self::assertResponseIsSuccessful();
        $event = $this->reload();
        self::assertSame((string) $agendaB->getId(), (string) $event->getAgenda()->getId());
        self::assertSame('Dentist', $event->getSummary());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testPatchCannotMoveTheEventToAnotherUsersAgenda(): void
    {
        $foreign = $this->getFixture('foreign_agenda');

        $this->patch(['agenda' => '/api/agendas/'.$foreign->getId()]);

        self::assertResponseStatusCodeSame(400);
        self::assertSame(
            (string) $this->getFixture('agenda_a')->getId(),
            (string) $this->reload()->getAgenda()->getId(),
        );
    }

    public function testPatchReplacesTheReminders(): void
    {
        $reminders = ['useDefault' => false, 'overrides' => [['method' => 'popup', 'minutes' => 30]]];

        $this->patch(['reminders' => $reminders]);

        self::assertResponseIsSuccessful();
        self::assertSame($reminders, $this->reload()->getReminders());
        $this->assertMercureUpdatePublished('/events/');
    }

    public function testPatchClearsTheReminders(): void
    {
        $this->patch(['reminders' => null]);

        self::assertResponseIsSuccessful();
        self::assertNull($this->reload()->getReminders());
    }
}
