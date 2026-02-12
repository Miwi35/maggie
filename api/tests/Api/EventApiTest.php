<?php

namespace App\Tests\Api;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class EventApiTest extends WebTestCase
{
    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();

        $em = self::getContainer()->get('doctrine.orm.entity_manager');

        // Clean tables
        $em->createQuery('DELETE FROM ' . Event::class)->execute();
        $em->createQuery('DELETE FROM ' . Agenda::class)->execute();
    }

    private function createAgenda(): Agenda
    {
        $agenda = new Agenda();
        $agenda->setName('Test Agenda');
        $agenda->setIsDefault(true);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($agenda);
        $em->flush();

        return $agenda;
    }

    private function createEvent(Agenda $agenda, string $summary = 'Test Event'): Event
    {
        $event = new Event();
        $event->setSummary($summary);
        $event->setStartAt(new \DateTimeImmutable('2026-03-15 10:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-15 11:00'));
        $event->setAgenda($agenda);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($event);
        $em->flush();

        return $event;
    }

    public function testGetEventCollection(): void
    {
        $agenda = $this->createAgenda();
        $this->createEvent($agenda, 'Event 1');
        $this->createEvent($agenda, 'Event 2');

        $this->client->request('GET', '/api/events', [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('content-type', 'application/ld+json; charset=utf-8');

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('member', $data);
        self::assertCount(2, $data['member']);
    }

    public function testGetSingleEvent(): void
    {
        $agenda = $this->createAgenda();
        $event = $this->createEvent($agenda, 'My Event');

        $this->client->request('GET', '/api/events/' . $event->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('My Event', $data['summary']);
    }

    public function testCreateEvent(): void
    {
        $agenda = $this->createAgenda();

        $this->client->request('POST', '/api/events', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            'summary' => 'New Event',
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'agenda' => '/api/agendas/' . $agenda->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('New Event', $data['summary']);
    }

    public function testCreateEventValidationError(): void
    {
        $agenda = $this->createAgenda();

        $this->client->request('POST', '/api/events', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            // Missing required 'summary'
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'agenda' => '/api/agendas/' . $agenda->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }
}
