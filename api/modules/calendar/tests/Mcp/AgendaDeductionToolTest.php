<?php

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Event;
use Maggie\Calendar\Mcp\Tool\CreateEventTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * `create_event` works out which agenda an event belongs to, says which one it chose, and
 * asks instead of guessing when two fit (MAG-150) — the three acceptance criteria of the
 * ticket, plus what must not change around them.
 *
 * The world is `Service/fixtures/AgendaSuggesterTest.yaml`, shared rather than copied: the
 * scoring is asserted there against the same agendas and the same habits, and two copies
 * of that world would let the two suites disagree about what they are testing.
 */
class AgendaDeductionToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(dirname(__DIR__).'/Service/fixtures/AgendaSuggesterTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function create(string $title, ?string $agenda = null, ?string $location = null): array
    {
        return json_decode(
            (self::getContainer()->get(CreateEventTool::class))(
                $title, '2099-11-12', '20:00', '2099-11-12', '22:00', location: $location, agenda_id: $agenda,
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

    /**
     * The agenda the event actually landed in, read back from the database by id.
     *
     * By id and not by title: the fixtures already hold a « Déjeuner avec Camille » and a
     * « Rendez-vous avec Paul » — that is what the deduction reads — so a lookup by
     * summary would answer about the habit instead of about the event just created.
     *
     * @param array<string, mixed> $created what the tool returned
     */
    private function agendaOf(array $created): string
    {
        $this->em()->clear();
        $event = $this->em()->getRepository(Event::class)->find($created['event']['id']);
        self::assertInstanceOf(Event::class, $event);

        return $event->getAgenda()->getName();
    }

    private function eventsNamed(string $summary): int
    {
        $this->em()->clear();

        return count($this->em()->getRepository(Event::class)->findBy(['summary' => $summary]));
    }

    public function testAConcertGoesToTheConcertsAgendaAndTheToolSaysSo(): void
    {
        $this->loginFixtureUser();

        $data = $this->create('Concert de Stromae');

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame('Concerts', $data['event']['agenda']);
        self::assertSame('deduced', $data['agendaChoice']);
        self::assertStringContainsString('concert', (string) $data['agendaReason']);
        self::assertSame('Concerts', $this->agendaOf($data));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testAnAppointmentGoesWhereThePastOnesWent(): void
    {
        $this->loginFixtureUser();

        $data = $this->create('Rendez-vous avec Paul');

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame('Boulot', $data['event']['agenda']);
        self::assertSame('deduced', $data['agendaChoice']);
        // Not the default agenda, which is what it used to get before MAG-150.
        self::assertSame('Boulot', $this->agendaOf($data));
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Event::class);
    }

    public function testAnAmbiguousEventIsNotCreatedAndTheAnswerAsksWhichAgenda(): void
    {
        $this->loginFixtureUser();
        $before = $this->eventCount();

        $data = $this->create('Déjeuner avec Camille');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('Several agendas fit this event', $data['error']);
        self::assertStringContainsString('Boulot (id ', $data['error']);
        self::assertStringContainsString('Famille (id ', $data['error']);
        self::assertStringContainsString('ask the user which one they mean', $data['error']);
        // « Perso » lunches too, but a third as convincingly: asking about it is noise.
        self::assertStringNotContainsString('Perso (id ', $data['error']);
        self::assertSame($before, $this->eventCount());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheAnswerToTheQuestionCreatesTheEvent(): void
    {
        $this->loginFixtureUser();

        $refused = $this->create('Déjeuner avec Camille');
        $data = $this->create('Déjeuner avec Camille', 'boulot');

        self::assertArrayNotHasKey('success', $refused);
        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame('Boulot', $data['event']['agenda']);
        self::assertSame('named', $data['agendaChoice']);
        self::assertSame('Boulot', $this->agendaOf($data));
    }

    public function testAnEventNothingPointsAtGoesToTheDefaultAgenda(): void
    {
        $this->loginFixtureUser();

        $data = $this->create('Plomberie');

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame('Perso', $data['event']['agenda']);
        self::assertSame('default', $data['agendaChoice']);
        self::assertSame('Perso', $this->agendaOf($data));
    }

    public function testAnAgendaTheUserNamedStillWinsOverEverySignal(): void
    {
        $this->loginFixtureUser();

        // Everything about this event points at « Concerts ». The user said « Famille ».
        $data = $this->create('Concert de Stromae', 'famille');

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame('Famille', $data['event']['agenda']);
        self::assertSame('named', $data['agendaChoice']);
        self::assertSame('Famille', $this->agendaOf($data));
    }

    public function testAnAgendaNameShortenedByTheUserStillReachesIt(): void
    {
        $this->loginFixtureUser();

        $data = $this->create('Point client', 'boulo');

        self::assertTrue($data['success'], $data['error'] ?? '');
        self::assertSame('Boulot', $data['event']['agenda']);
        self::assertSame('deduced', $data['agendaChoice']);
    }

    public function testAnAgendaNameMatchingNoneAsksInsteadOfDeducingAroundIt(): void
    {
        $this->loginFixtureUser();
        $before = $this->eventCount();

        // « Concerts » would have won on its own. The user naming an agenda they do not
        // have is a question, not a licence to file the event somewhere else.
        $data = $this->create('Concert de Stromae', 'Théâtre');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('No agenda has the id or name "Théâtre"', $data['error']);
        self::assertStringContainsString('Concerts', $data['error']);
        self::assertSame($before, $this->eventCount());
        self::assertSame(0, $this->eventsNamed('Concert de Stromae'));
    }

    public function testTwoAgendasOfTheSameNameAreStillRefusedOutright(): void
    {
        $user = $this->loginFixtureUser();
        $twin = new Agenda();
        $twin->setName('concerts');
        $twin->setUser($user);
        $this->em()->persist($twin);
        $this->em()->flush();
        $before = $this->eventCount();

        $data = $this->create('Concert de Stromae', 'Concerts');

        self::assertArrayNotHasKey('success', $data);
        self::assertStringContainsString('Several agendas are named "Concerts"', $data['error']);
        self::assertSame($before, $this->eventCount());
    }

    public function testWithNoUserBoundNothingIsDeducedAndNothingIsCreated(): void
    {
        $before = $this->eventCount();

        $deduced = $this->create('Concert de Stromae');
        $named = $this->create('Concert de Stromae', 'Concerts');

        // Not « Concerts »: there is no caller whose agendas could be read.
        self::assertStringContainsString('No default agenda', $deduced['error']);
        self::assertStringContainsString('No user bound', $named['error']);
        self::assertSame($before, $this->eventCount());
    }
}
