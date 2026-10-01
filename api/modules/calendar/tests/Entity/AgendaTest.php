<?php

namespace Maggie\Calendar\Tests\Entity;

use Maggie\Calendar\Entity\Agenda;
use Maggie\Core\Elasticsearch\IndexMetadataReader;
use Maggie\Core\Entity\User;
use PHPUnit\Framework\TestCase;

class AgendaTest extends TestCase
{
    private function agenda(): Agenda
    {
        $user = new User();
        $user->setEmail('test@example.com');
        $user->setGoogleId('google-123');
        $user->setName('Test User');

        $agenda = new Agenda();
        $agenda->setUser($user);
        $agenda->setName('Concerts');

        return $agenda;
    }

    /**
     * The agenda collection is served from Elasticsearch, and the hydrator
     * rebuilds the entity from the indexed document alone. A field missing from
     * it comes back null to every client — which is how the admin's guard
     * against connecting the same Google calendar twice never fired (MAG-148).
     */
    public function testTheIndexedDocumentCarriesTheGoogleCalendar(): void
    {
        $agenda = $this->agenda();
        $agenda->setGoogleCalendarId('concerts@group.calendar.google.com');

        self::assertSame(
            'concerts@group.calendar.google.com',
            $agenda->toSearchDocument()['googleCalendarId'] ?? null,
        );
    }

    public function testAnAgendaWithNoGoogleCalendarSaysSoInTheIndex(): void
    {
        self::assertArrayHasKey('googleCalendarId', $this->agenda()->toSearchDocument());
        self::assertNull($this->agenda()->toSearchDocument()['googleCalendarId']);
    }

    public function testTheGoogleCalendarIsMappedAsAnIdentifier(): void
    {
        $meta = (new IndexMetadataReader())->read(Agenda::class);

        self::assertNotNull($meta);
        self::assertSame(
            ['type' => 'keyword'],
            $meta['fields']['googleCalendarId'] ?? null,
            'Analysed as text, an address would not be matched whole',
        );
    }
}
