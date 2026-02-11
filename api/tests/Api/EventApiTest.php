<?php

namespace App\Tests\Api;

use Maggie\Agenda\Entity\Calendar;
use Maggie\Agenda\Entity\Event;
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
        $em->createQuery('DELETE FROM ' . Calendar::class)->execute();
    }

    private function createCalendar(): Calendar
    {
        $calendar = new Calendar();
        $calendar->setName('Test Calendar');
        $calendar->setIsDefault(true);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($calendar);
        $em->flush();

        return $calendar;
    }

    private function createEvent(Calendar $calendar, string $summary = 'Test Event'): Event
    {
        $event = new Event();
        $event->setSummary($summary);
        $event->setStartAt(new \DateTimeImmutable('2026-03-15 10:00'));
        $event->setEndAt(new \DateTimeImmutable('2026-03-15 11:00'));
        $event->setCalendar($calendar);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($event);
        $em->flush();

        return $event;
    }

    public function testGetEventCollection(): void
    {
        $calendar = $this->createCalendar();
        $this->createEvent($calendar, 'Event 1');
        $this->createEvent($calendar, 'Event 2');

        $this->client->request('GET', '/api/events', [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseIsSuccessful();
        self::assertResponseHeaderSame('content-type', 'application/ld+json; charset=utf-8');

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('member', $data);
        self::assertCount(2, $data['member']);
    }

    public function testGetSingleEvent(): void
    {
        $calendar = $this->createCalendar();
        $event = $this->createEvent($calendar, 'My Event');

        $this->client->request('GET', '/api/events/' . $event->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseIsSuccessful();

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('My Event', $data['summary']);
    }

    public function testCreateEvent(): void
    {
        $calendar = $this->createCalendar();

        $this->client->request('POST', '/api/events', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            'summary' => 'New Event',
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'calendar' => '/api/calendars/' . $calendar->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);

        $data = json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
        self::assertSame('New Event', $data['summary']);
    }

    public function testCreateEventValidationError(): void
    {
        $calendar = $this->createCalendar();

        $this->client->request('POST', '/api/events', [], [], [
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], json_encode([
            // Missing required 'summary'
            'startAt' => '2026-03-20T10:00:00+01:00',
            'endAt' => '2026-03-20T11:00:00+01:00',
            'calendar' => '/api/calendars/' . $calendar->getId(),
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(422);
    }
}
