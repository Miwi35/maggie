<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Calendar\Entity\Event;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EventClearApiTest extends WebTestCase
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
    }

    /** @param array<string, mixed> $payload */
    private function patch(Event $entity, array $payload): void
    {
        $this->client->request('PATCH', '/api/events/'.$entity->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($payload, JSON_THROW_ON_ERROR));
    }

    private function reload(Event $entity): Event
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Event::class)->find($entity->getId());
    }

    private function load(): Event
    {
        $this->loadFixtures('EventClearApiTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));

        return $this->getFixture('event_full');
    }

    public function testPatchRequiresAuthentication(): void
    {
        $entity = $this->load();

        $this->client->request('PATCH', '/api/events/'.$entity->getId(), [], [], [
            'CONTENT_TYPE' => 'application/merge-patch+json',
        ], json_encode(['description' => null], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(401);
    }

    public function testPatchWithNullFieldsClearsThem(): void
    {
        $event = $this->load();

        $this->patch($event, ['description' => null, 'location' => null, 'rrule' => null]);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($event);
        self::assertNull($reloaded->getDescription());
        self::assertNull($reloaded->getLocation());
        self::assertNull($reloaded->getRrule());
        self::assertSame('Weekly sync', $reloaded->getSummary());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testPatchLeavesFieldsOutOfThePayloadUntouched(): void
    {
        $event = $this->load();

        $this->patch($event, ['summary' => 'Renamed']);

        self::assertResponseIsSuccessful();
        $reloaded = $this->reload($event);
        self::assertSame('Renamed', $reloaded->getSummary());
        self::assertSame('Bring slides', $reloaded->getDescription());
        self::assertSame('Room 4', $reloaded->getLocation());
        self::assertSame('FREQ=WEEKLY', $reloaded->getRrule());
    }
}
