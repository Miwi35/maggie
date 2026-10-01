<?php

namespace Maggie\Calendar\Tests\Entity;

use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Core\Elasticsearch\Hydrator\ElasticsearchEntityHydrator;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

/**
 * Collections and items of indexed entities are rebuilt from the Elasticsearch
 * document alone, so whatever the document leaves out is lost on the way to the
 * client (MAG-169: an exception instance came back as an ordinary event).
 */
class EventSearchDocumentTest extends TestCase
{
    private Agenda $agenda;
    private Event $master;
    private Event $exception;

    protected function setUp(): void
    {
        $user = new User();
        $user->setEmail('piano@example.com');
        $user->setGoogleId('google-piano');
        $user->setName('Piano');

        $this->agenda = new Agenda();
        $this->agenda->setName('Test');
        $this->agenda->setUser($user);

        $this->master = new Event();
        $this->master->setSummary('Cours de piano');
        $this->master->setStartAt(new \DateTimeImmutable('2026-03-01T10:00:00+00:00'));
        $this->master->setEndAt(new \DateTimeImmutable('2026-03-01T11:00:00+00:00'));
        $this->master->setRrule('FREQ=WEEKLY;COUNT=10');
        $this->master->setAgenda($this->agenda);

        $this->exception = new Event();
        $this->exception->setSummary('Cours de piano');
        $this->exception->setStartAt(new \DateTimeImmutable('2026-03-15T11:00:00+00:00'));
        $this->exception->setEndAt(new \DateTimeImmutable('2026-03-15T12:00:00+00:00'));
        $this->exception->setAgenda($this->agenda);
        $this->exception->setRecurringEvent($this->master);
        $this->exception->setOriginalStartAt(new \DateTimeImmutable('2026-03-15T10:00:00+00:00'));
        $this->exception->setStatus(EventStatus::Cancelled);
    }

    private function hydrate(Event $event): Event
    {
        $em = $this->createMock(EntityManagerInterface::class);
        $em->method('getReference')->willReturnCallback(
            fn (string $class, mixed $id): ?object => Agenda::class === $class ? $this->agenda : $this->master,
        );

        $source = $event->toSearchDocument();
        $source['id'] = (string) $event->getId();

        $hydrated = (new ElasticsearchEntityHydrator($em))->hydrate($source, Event::class);
        self::assertInstanceOf(Event::class, $hydrated);

        return $hydrated;
    }

    public function testExceptionInstanceSurvivesTheRoundTripThroughElasticsearch(): void
    {
        $hydrated = $this->hydrate($this->exception);

        self::assertTrue($hydrated->isException());
        self::assertSame((string) $this->master->getId(), (string) $hydrated->getRecurringEvent()?->getId());
        self::assertEquals($this->exception->getOriginalStartAt(), $hydrated->getOriginalStartAt());
        self::assertSame(EventStatus::Cancelled, $hydrated->getStatus());
    }

    public function testOrdinaryEventStaysOrdinaryThroughTheRoundTrip(): void
    {
        $hydrated = $this->hydrate($this->master);

        self::assertFalse($hydrated->isException());
        self::assertNull($hydrated->getRecurringEvent());
        self::assertNull($hydrated->getOriginalStartAt());
    }

    public function testTheMappingDeclaresTheFieldsTheDocumentCarries(): void
    {
        $meta = (new IndexMetadataReader())->read(Event::class);

        self::assertNotNull($meta);
        self::assertSame('date', $meta['fields']['originalStartAt']['type'] ?? null);
        self::assertSame('recurringEventId', $meta['relations']['recurringEvent']['sourceField'] ?? null);
        self::assertSame(Event::class, $meta['relations']['recurringEvent']['targetEntity'] ?? null);
    }
}
