<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Mcp\Tool\CancelProactionTool;
use Maggie\Calendar\Mcp\Tool\CheckConflictsTool;
use Maggie\Calendar\Mcp\Tool\ListProactionsTool;
use Maggie\Calendar\Mcp\Tool\ScheduleProactionTool;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use Maggie\Calendar\Message\PushEventToGoogleCommand;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/** schedule_proaction, list_proactions and cancel_proaction (MAG-326). */
class ProactionToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('ProactionToolsTest.yaml');
    }

    /** @return array<string, mixed> */
    private function call(string $tool, mixed ...$args): array
    {
        return json_decode(self::getContainer()->get($tool)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function tomorrowAt(string $time): string
    {
        return (new \DateTimeImmutable('tomorrow '.$time, new \DateTimeZone('Europe/Paris')))->format('c');
    }

    public function testEveryToolRefusesACallWithNoUser(): void
    {
        $id = (string) $this->getFixture('other_due')->getId();

        foreach ([
            [ScheduleProactionTool::class, ['Rappelle le pain', $this->tomorrowAt('08:30')]],
            [ListProactionsTool::class, []],
            [CancelProactionTool::class, [$id]],
        ] as [$tool, $args]) {
            $data = $this->call($tool, ...$args);
            self::assertStringContainsString('No user bound', $data['error'] ?? '', $tool);
        }
        self::assertCount(2, $this->proactions());
    }

    public function testScheduleFilesTheProactionInTheMaggieModuleAgendaAndNeverInGoogle(): void
    {
        $user = $this->loginFixtureUser();
        $at = $this->tomorrowAt('08:30');

        $data = $this->call(ScheduleProactionTool::class, 'Rappelle à Meven de déposer Ael à l\'école à 8 h 45', $at);

        self::assertTrue($data['success'], json_encode($data, JSON_THROW_ON_ERROR));
        self::assertSame('Maggie', $data['agenda']);
        self::assertSame('pending', $data['proaction']['status']);
        self::assertSame($at, $data['proaction']['scheduledAt']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        $stored = $em->find(Proaction::class, $data['proaction']['id']);
        self::assertNotNull($stored);
        self::assertSame('Rappelle à Meven de déposer Ael à l\'école à 8 h 45', $stored->getPrompt());
        self::assertSame(ProactionStatus::Pending, $stored->getExecutionStatus());
        self::assertEquals(new \DateTimeImmutable($at), $stored->getStartAt());
        // Shown five minutes long, at an instant, never repeating.
        self::assertEquals((new \DateTimeImmutable($at))->modify('+5 minutes'), $stored->getEndAt());
        self::assertFalse($stored->isAllDay());
        self::assertNull($stored->getRrule());

        $agenda = $stored->getAgenda();
        self::assertSame(Agenda::MODULE_AGENT, $agenda->getModule());
        self::assertSame((string) $user->getId(), (string) $agenda->getUser()->getId());
        self::assertFalse($agenda->isGoogleSynced());
        self::assertFalse($agenda->isDefault());
        // The user's own « Maggie » agenda is left alone: a new module agenda was created.
        self::assertNotSame((string) $this->getFixture('own_maggie_agenda')->getId(), (string) $agenda->getId());
        self::assertNull($em->find(Agenda::class, $this->getFixture('own_maggie_agenda')->getId())?->getModule());

        foreach ($this->getAsyncTransport()->getSent() as $envelope) {
            self::assertNotInstanceOf(PushEventToGoogleCommand::class, $envelope->getMessage());
        }

        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertMercureUpdatePublished('/events/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    public function testASecondProactionGoesInTheSameAgenda(): void
    {
        $this->loginFixtureUser();

        $first = $this->call(ScheduleProactionTool::class, 'Premier rappel', $this->tomorrowAt('08:30'));
        $second = $this->call(ScheduleProactionTool::class, 'Second rappel', $this->tomorrowAt('09:30'), 'Second');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertSame(
            (string) $em->find(Proaction::class, $first['proaction']['id'])->getAgenda()->getId(),
            (string) $em->find(Proaction::class, $second['proaction']['id'])->getAgenda()->getId(),
        );
        self::assertSame('Second', $second['proaction']['summary']);
    }

    public function testALongPromptIsShortenedForTheAgendaLabel(): void
    {
        $this->loginFixtureUser();
        $prompt = 'Rappelle à Meven que c\'est son jour de garde, qu\'il doit déposer Ael à l\'école avant 8 h 45 et prendre son goûter';

        $data = $this->call(ScheduleProactionTool::class, $prompt, $this->tomorrowAt('08:00'));

        self::assertLessThanOrEqual(60, mb_strlen($data['proaction']['summary']));
        self::assertStringEndsWith('…', $data['proaction']['summary']);
        self::assertSame($prompt, $data['proaction']['prompt']);
    }

    /** @return iterable<string, array{string}> */
    public static function badInstants(): iterable
    {
        yield 'past' => ['2020-01-15T08:30:00+01:00'];
        yield 'not a date' => ['demain matin'];
        yield 'no time' => ['2037-01-15'];
        yield 'impossible day' => ['2037-02-30T08:30:00'];
        yield 'empty' => [''];
    }

    #[DataProvider('badInstants')]
    public function testScheduleRefusesAPastOrInvalidInstant(string $scheduledAt): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ScheduleProactionTool::class, 'Rappelle le pain', $scheduledAt);

        self::assertArrayHasKey('error', $data);
        self::assertCount(2, $this->proactions());
        $this->assertMercureUpdateCount(0);
    }

    public function testScheduleRefusesAnEmptyPrompt(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ScheduleProactionTool::class, '  ', $this->tomorrowAt('08:30'));

        self::assertStringContainsString('prompt', $data['error'] ?? '');
        self::assertCount(2, $this->proactions());
    }

    public function testAnInstantWithoutOffsetIsReadInParis(): void
    {
        $this->loginFixtureUser();
        $day = (new \DateTimeImmutable('+2 days'))->format('Y-m-d');

        $data = $this->call(ScheduleProactionTool::class, 'Rappelle le pain', $day.'T08:30');

        self::assertEquals(new \DateTimeImmutable($day.' 08:30', new \DateTimeZone('Europe/Paris')), new \DateTimeImmutable($data['proaction']['scheduledAt']));
    }

    public function testListReturnsOnlyMyProactionsWithinTheWindowAndStatus(): void
    {
        $this->loginFixtureUser();
        $this->call(ScheduleProactionTool::class, 'Demain', $this->tomorrowAt('08:30'));
        $this->call(ScheduleProactionTool::class, 'Dans trois jours', (new \DateTimeImmutable('+3 days'))->format('c'));

        $all = $this->call(ListProactionsTool::class);
        self::assertSame(2, $all['count']);
        self::assertSame(['Demain', 'Dans trois jours'], array_column($all['proactions'], 'prompt'));

        $tomorrow = (new \DateTimeImmutable('tomorrow', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
        $window = $this->call(ListProactionsTool::class, from: $tomorrow, to: $tomorrow);
        self::assertSame(['Demain'], array_column($window['proactions'], 'prompt'));

        self::assertSame(2, $this->call(ListProactionsTool::class, status: 'pending')['count']);
        self::assertSame(0, $this->call(ListProactionsTool::class, status: 'running')['count']);
    }

    public function testListRefusesAnUnknownStatusOrDate(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->call(ListProactionsTool::class, status: 'done'));
        self::assertArrayHasKey('error', $this->call(ListProactionsTool::class, from: 'hier soir peut-être'));
    }

    public function testCancelDeletesTheProactionPublishesAndLeavesTheIndices(): void
    {
        $this->loginFixtureUser();
        $created = $this->call(ScheduleProactionTool::class, 'Rappelle le pain', $this->tomorrowAt('08:30'));
        $id = $created['proaction']['id'];
        $this->resetMercure();
        $this->resetAsyncTransport();

        $data = $this->call(CancelProactionTool::class, $id);

        self::assertTrue($data['success']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->find(Proaction::class, $id));
        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchDeleteDispatched('proactions');
        $this->assertElasticsearchDeleteDispatched('events');
    }

    public function testCancelRefusesAnUnknownId(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('not found', $this->call(CancelProactionTool::class, 'not-an-id')['error'] ?? '');
    }

    public function testAnotherUserNeitherSeesNorCancelsMyProactions(): void
    {
        $this->loginFixtureUser();
        $othersId = (string) $this->getFixture('other_due')->getId();

        self::assertSame(0, $this->call(ListProactionsTool::class)['count']);
        self::assertStringContainsString('not found', $this->call(CancelProactionTool::class, $othersId)['error'] ?? '');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNotNull($em->find(Proaction::class, $othersId));
    }

    public function testUpdateEventCannotMakeAProactionLastAWholeDay(): void
    {
        $this->loginFixtureUser();
        $created = $this->call(ScheduleProactionTool::class, 'Rappelle le pain', $this->tomorrowAt('08:30'));
        $day = (new \DateTimeImmutable('tomorrow', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');

        $data = $this->call(UpdateEventTool::class, $created['proaction']['id'], all_day: true, start_date: $day, end_date: (new \DateTimeImmutable($day))->modify('+1 day')->format('Y-m-d'));

        self::assertStringContainsString('instant', $data['error'] ?? '');
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertFalse($em->find(Proaction::class, $created['proaction']['id'])->isAllDay());
    }

    public function testAProactionDoesNotMakeTheUserBusy(): void
    {
        $this->loginFixtureUser();
        $this->call(ScheduleProactionTool::class, 'Rappelle le pain', $this->tomorrowAt('08:30'));
        $day = (new \DateTimeImmutable('tomorrow', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');

        $data = $this->call(CheckConflictsTool::class, $day, '08:30', 30);

        self::assertFalse($data['hasConflicts']);
    }

    /** @return Proaction[] */
    private function proactions(): array
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->getRepository(Proaction::class)->findAll();
    }
}
