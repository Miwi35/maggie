<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use Maggie\Core\Mcp\MissingMcpUserException;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * create_event and update_event take the agenda by name or by id (MAG-230).
 */
class AgendaByNameToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('AgendaByNameToolsTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function create(?string $agenda): array
    {
        return json_decode(
            (self::getContainer()->get(CreateEventTool::class))(
                'Black Wizards', '2026-11-02', '19:00', '2026-11-02', '21:00', location: 'UBU', agenda_id: $agenda,
            ),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    /** @return array<string, mixed> */
    private function update(?string $agenda): array
    {
        return json_decode(
            (self::getContainer()->get(UpdateEventTool::class))(
                (string) $this->getFixture('movable_event')->getId(),
                agenda_id: $agenda,
            ),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );
    }

    private function em(): \Doctrine\ORM\EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function eventCount(): int
    {
        return (int) $this->em()->getConnection()->fetchOne('SELECT COUNT(*) FROM event');
    }

    private function agendaNameOf(string $summary): string
    {
        $this->em()->clear();

        return $this->em()->getRepository(Event::class)->findOneBy(['summary' => $summary])->getAgenda()->getName();
    }

    /** @return iterable<string, array{string, string}> */
    public static function spokenNames(): iterable
    {
        yield 'exact' => ['Concerts', 'Concerts'];
        yield 'lower case' => ['concerts', 'Concerts'];
        yield 'upper case with spaces' => ['  CONCERTS ', 'Concerts'];
        yield 'accents dropped' => ['soirees', 'Soirées'];
        yield 'accents added' => ['Soirées', 'Soirées'];
    }

    #[DataProvider('spokenNames')]
    public function testCreateEventResolvesTheAgendaByName(string $spoken, string $expected): void
    {
        $this->loginFixtureUser();

        $data = $this->create($spoken);

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame($expected, $data['event']['agenda']);
        self::assertSame($expected, $this->agendaNameOf('Black Wizards'));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testCreateEventStillAcceptsTheAgendaId(): void
    {
        $this->loginFixtureUser();

        $data = $this->create((string) $this->getFixture('concerts_agenda')->getId());

        self::assertTrue($data['success']);
        self::assertSame('Concerts', $data['event']['agenda']);
        $event = $this->em()->getRepository(Event::class)->findOneBy(['summary' => 'Black Wizards', 'location' => 'UBU']);
        self::assertSame((string) $this->getFixture('concerts_agenda')->getId(), (string) $event->getAgenda()->getId());
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testCreateEventUnknownNameListsOnlyTheCallersAgendas(): void
    {
        $this->loginFixtureUser();
        $before = $this->eventCount();

        $data = $this->create('Théâtre');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('"Théâtre"', $data['error']);
        foreach (['Main', 'Concerts', 'Soirées', 'Sport'] as $name) {
            self::assertStringContainsString($name, $data['error']);
        }
        self::assertStringContainsString((string) $this->getFixture('concerts_agenda')->getId(), $data['error']);
        self::assertStringNotContainsString('secret', $data['error']);
        self::assertStringNotContainsString((string) $this->getFixture('other_concerts_agenda')->getId(), $data['error']);
        self::assertSame($before, $this->eventCount());
    }

    public function testCreateEventAmbiguousNameListsTheAgendas(): void
    {
        $this->loginFixtureUser();
        $before = $this->eventCount();

        $data = $this->create('SPORT');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('Several agendas', $data['error']);
        self::assertStringContainsString((string) $this->getFixture('sport_agenda')->getId(), $data['error']);
        self::assertStringContainsString((string) $this->getFixture('sport_twin_agenda')->getId(), $data['error']);
        self::assertSame($before, $this->eventCount());
    }

    public function testCreateEventUnknownIdListsTheAgendas(): void
    {
        $this->loginFixtureUser();
        $before = $this->eventCount();

        $data = $this->create('01JZZZZZZZZZZZZZZZZZZZZZZZ');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('Available agendas', $data['error']);
        self::assertStringContainsString('Concerts', $data['error']);
        self::assertSame($before, $this->eventCount());
    }

    public function testCreateEventRefusesAnotherUsersAgendaIdAndName(): void
    {
        $this->loginFixtureUser();
        $before = $this->eventCount();

        $byId = $this->create((string) $this->getFixture('other_secret_agenda')->getId());
        $byName = $this->create('Agenda secret du voisin');

        foreach ([$byId, $byName] as $data) {
            self::assertArrayNotHasKey('success', $data);
            self::assertStringContainsString('Available agendas', $data['error']);
            self::assertStringNotContainsString('Agenda secret du voisin (id', $data['error']);
            self::assertStringNotContainsString((string) $this->getFixture('other_secret_agenda')->getId().' ', $data['error']);
        }
        self::assertSame($before, $this->eventCount());
    }

    public function testCreateEventResolvesTheNameAmongTheCallersAgendasOnly(): void
    {
        $this->loginFixtureUser();

        $data = $this->create('Concerts');

        $event = $this->em()->getRepository(Event::class)->findOneBy(['summary' => 'Black Wizards', 'location' => 'UBU']);
        self::assertTrue($data['success']);
        self::assertSame((string) $this->getFixture('concerts_agenda')->getId(), (string) $event->getAgenda()->getId());
    }

    public function testCreateEventWithAnAgendaButNoUserIsRefused(): void
    {
        $before = $this->eventCount();

        $data = $this->create('Concerts');

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame($before, $this->eventCount());
    }

    #[DataProvider('spokenNames')]
    public function testUpdateEventMovesTheEventToTheAgendaByName(string $spoken, string $expected): void
    {
        $this->loginFixtureUser();

        $data = $this->update($spoken);

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame($expected, $data['event']['agenda']);
        self::assertSame($expected, $this->agendaNameOf('Movable event'));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testUpdateEventStillAcceptsTheAgendaId(): void
    {
        $this->loginFixtureUser();

        $data = $this->update((string) $this->getFixture('concerts_agenda')->getId());

        self::assertTrue($data['success']);
        self::assertSame('Concerts', $this->agendaNameOf('Movable event'));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testUpdateEventWithoutAgendaLeavesItAlone(): void
    {
        $this->loginFixtureUser();

        $data = $this->update(null);

        self::assertTrue($data['success']);
        self::assertSame('Main', $this->agendaNameOf('Movable event'));
    }

    public function testUpdateEventUnknownNameAmbiguousNameAndUnknownIdLeaveTheEventInPlace(): void
    {
        $this->loginFixtureUser();

        $unknown = $this->update('Théâtre');
        $ambiguous = $this->update('sport');
        $unknownId = $this->update('01JZZZZZZZZZZZZZZZZZZZZZZZ');
        $foreign = $this->update((string) $this->getFixture('other_secret_agenda')->getId());

        foreach ([$unknown, $ambiguous, $unknownId, $foreign] as $data) {
            self::assertArrayNotHasKey('success', $data);
            self::assertStringContainsString('Concerts', $data['error']);
            self::assertStringNotContainsString('Agenda secret du voisin (id', $data['error']);
        }
        self::assertStringContainsString('Several agendas', $ambiguous['error']);
        self::assertSame('Main', $this->agendaNameOf('Movable event'));
    }

    public function testUpdateEventWithAnAgendaButNoUserIsRefused(): void
    {
        $data = $this->update('Concerts');

        self::assertSame(MissingMcpUserException::MESSAGE, $data['error']);
        self::assertSame('Main', $this->agendaNameOf('Movable event'));
    }
}
