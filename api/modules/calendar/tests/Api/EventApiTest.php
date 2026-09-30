<?php

namespace Maggie\Calendar\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
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
}
