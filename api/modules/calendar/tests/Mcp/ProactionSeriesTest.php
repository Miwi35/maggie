<?php

declare(strict_types=1);

namespace Maggie\Calendar\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\DriverManager;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Proaction;
use Maggie\Calendar\Enum\EventStatus;
use Maggie\Calendar\Enum\ProactionStatus;
use Maggie\Calendar\Mcp\Tool\CancelProactionTool;
use Maggie\Calendar\Mcp\Tool\ClaimDueProactionsTool;
use Maggie\Calendar\Mcp\Tool\ListProactionsTool;
use Maggie\Calendar\Mcp\Tool\RecordProactionResultTool;
use Maggie\Calendar\Mcp\Tool\ScheduleProactionTool;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** A proaction that repeats: a series, and one exception instance per pass (ADR-012-1, MAG-974). */
class ProactionSeriesTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const FRIDAY = '2037-01-16T18:00:00+01:00';
    private const NEXT_FRIDAY = '2037-01-23T18:00:00+01:00';

    private ?Connection $rival = null;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2037-01-16 18:01:00 Europe/Paris'));
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('ProactionSeriesTest.yaml');
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        if (null !== $this->rival) {
            if ($this->rival->isTransactionActive()) {
                $this->rival->rollBack();
            }
            $this->rival->close();
            $this->rival = null;
        }
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $tool, mixed ...$args): array
    {
        return json_decode(self::getContainer()->get($tool)(...$args), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** @return list<Proaction> */
    private function instancesOf(string $ref): array
    {
        $em = $this->em();
        $em->clear();

        return $em->getRepository(Proaction::class)->findBy(['recurringEvent' => $this->getFixture($ref)->getId()], ['originalStartAt' => 'ASC']);
    }

    private function stored(string $ref): Proaction
    {
        $em = $this->em();
        $em->clear();

        return $em->find(Proaction::class, $this->getFixture($ref)->getId());
    }

    public function testScheduleWithAnRruleFilesOneSeriesInTheMaggieAgenda(): void
    {
        $this->loginFixtureUser();
        $before = $this->em()->getRepository(Proaction::class)->count([]);

        $data = $this->call(ScheduleProactionTool::class, 'Fais le point des courses', '2037-01-23T18:00:00+01:00', null, null, 'FREQ=WEEKLY;BYDAY=FR');

        self::assertTrue($data['success'], json_encode($data));
        self::assertSame('FREQ=WEEKLY;BYDAY=FR', $data['proaction']['rrule']);
        self::assertSame($before + 1, $this->em()->getRepository(Proaction::class)->count([]));

        $this->em()->clear();
        $series = $this->em()->find(Proaction::class, $data['proaction']['id']);
        self::assertSame('FREQ=WEEKLY;BYDAY=FR', $series->getRrule());
        self::assertSame('agent', $series->getAgenda()->getModule());
        self::assertSame(ProactionStatus::Pending, $series->getExecutionStatus());
        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    /** @return iterable<string, array{string}> */
    public static function brokenRules(): iterable
    {
        yield 'prefixed' => ['RRULE:FREQ=WEEKLY;BYDAY=FR'];
        yield 'unknown frequency' => ['FREQ=NEVER'];
        yield 'minutely' => ['FREQ=MINUTELY'];
        yield 'too long' => ['FREQ=WEEKLY;BYDAY=FR;X='.str_repeat('A', 490)];
    }

    #[DataProvider('brokenRules')]
    public function testABrokenRruleIsRefusedAndNothingIsStored(string $rrule): void
    {
        $this->loginFixtureUser();
        $before = $this->em()->getRepository(Proaction::class)->count([]);

        $data = $this->call(ScheduleProactionTool::class, 'Fais le point', self::NEXT_FRIDAY, null, null, $rrule);

        self::assertArrayHasKey('error', $data);
        self::assertSame($before, $this->em()->getRepository(Proaction::class)->count([]));
        $this->assertMercureUpdateCount(0);
    }

    public function testScheduledAtOffTheRuleIsRefusedWithItsFirstOccurrence(): void
    {
        $this->loginFixtureUser();
        $before = $this->em()->getRepository(Proaction::class)->count([]);

        $data = $this->call(ScheduleProactionTool::class, 'Fais le point', '2037-01-22T18:00:00+01:00', null, null, 'FREQ=WEEKLY;BYDAY=FR');

        self::assertStringContainsString('2037-01-23T18:00:00+01:00', $data['error']);
        self::assertStringContainsString('Friday', $data['error']);
        self::assertSame($before, $this->em()->getRepository(Proaction::class)->count([]));
    }

    public function testTheSameSeriesAskedTwiceIsScheduledOnce(): void
    {
        $this->loginFixtureUser();

        $first = $this->call(ScheduleProactionTool::class, 'Fais le point des courses', self::NEXT_FRIDAY, null, null, 'FREQ=WEEKLY;BYDAY=FR');
        $second = $this->call(ScheduleProactionTool::class, 'Fais le point des courses', self::NEXT_FRIDAY, null, null, 'FREQ=WEEKLY;BYDAY=FR');

        self::assertSame($first['proaction']['id'], $second['proaction']['id']);
        self::assertCount(1, $this->em()->getRepository(Proaction::class)->findBy(['prompt' => 'Fais le point des courses']));
    }

    public function testTheSeriesItselfIsNeverClaimed(): void
    {
        foreach (['2037-01-05 12:00:00 Europe/Paris', '2037-01-16 18:01:00 Europe/Paris'] as $now) {
            Clock::set(new MockClock($now));
            $data = $this->call(ClaimDueProactionsTool::class, null, $this->id('series'));
            self::assertNotContains($this->id('series'), array_column($data['proactions'], 'id'));
            $data = $this->call(ClaimDueProactionsTool::class);
            self::assertNotContains($this->id('series'), array_column($data['proactions'], 'id'));
        }

        self::assertSame(ProactionStatus::Pending, $this->stored('series')->getExecutionStatus());
    }

    public function testADueOccurrenceIsClaimedAsAnExceptionInstance(): void
    {
        $data = $this->call(ClaimDueProactionsTool::class);

        self::assertSame(1, $data['count']);
        $claimed = $data['proactions'][0];
        self::assertNotSame($this->id('series'), $claimed['id']);
        self::assertSame($this->id('series'), $claimed['seriesId']);
        self::assertEquals(new \DateTimeImmutable(self::FRIDAY), new \DateTimeImmutable($claimed['occurrence']));
        self::assertSame('running', $claimed['status']);
        self::assertSame('Fais le point budget', $claimed['prompt']);
        self::assertSame('check', $claimed['kind']);
        self::assertSame('Point budget', $claimed['summary']);

        $instances = $this->instancesOf('series');
        self::assertCount(1, $instances);
        self::assertSame(ProactionStatus::Running, $instances[0]->getExecutionStatus());
        $this->assertMercureUpdatePublished('/proactions/');
        $this->assertElasticsearchIndexDispatched(Proaction::class);
    }

    public function testAnOccurrenceIsClaimedOnce(): void
    {
        self::assertSame(1, $this->call(ClaimDueProactionsTool::class)['count']);
        self::assertSame(0, $this->call(ClaimDueProactionsTool::class)['count']);
        self::assertCount(1, $this->instancesOf('series'));
    }

    public function testTwoSimultaneousClaimsMaterializeOneInstance(): void
    {
        $connection = $this->em()->getConnection();
        $this->rival = DriverManager::getConnection($connection->getParams(), $connection->getConfiguration());
        $this->rival->beginTransaction();
        $series = $this->getFixture('series')->getId()->toRfc4122();
        self::assertCount(1, $this->rival->fetchFirstColumn('SELECT id FROM proaction WHERE id = ? FOR UPDATE', [$series]));

        self::assertSame(0, $this->call(ClaimDueProactionsTool::class)['count']);
        self::assertCount(0, $this->instancesOf('series'));

        $this->rival->commit();

        self::assertSame(1, $this->call(ClaimDueProactionsTool::class)['count']);
        self::assertCount(1, $this->instancesOf('series'));
    }

    public function testAnOccurrenceMissedForMoreThanADayIsNotRun(): void
    {
        Clock::set(new MockClock('2037-01-18 12:00:00 Europe/Paris'));
        self::assertSame(0, $this->call(ClaimDueProactionsTool::class)['count']);

        Clock::set(new MockClock('2037-01-23 18:01:00 Europe/Paris'));
        $data = $this->call(ClaimDueProactionsTool::class);

        self::assertSame(1, $data['count']);
        self::assertEquals(new \DateTimeImmutable(self::NEXT_FRIDAY), new \DateTimeImmutable($data['proactions'][0]['occurrence']));
    }

    public function testRecordingWritesOnTheInstanceAndLeavesTheSeriesPending(): void
    {
        $claimed = $this->call(ClaimDueProactionsTool::class)['proactions'][0];

        $data = $this->call(RecordProactionResultTool::class, $claimed['id'], 'completed', 'Budget OK');

        self::assertArrayNotHasKey('error', $data, json_encode($data));
        $instances = $this->instancesOf('series');
        self::assertSame(ProactionStatus::Completed, $instances[0]->getExecutionStatus());
        self::assertSame('Budget OK', $instances[0]->getResponse());
        $series = $this->stored('series');
        self::assertSame(ProactionStatus::Pending, $series->getExecutionStatus());
        self::assertNull($series->getResponse());
        $this->assertMercureUpdatePublished('/proactions/');
    }

    public function testAFailedOccurrenceKeepsItsErrorAndTheNextOneStillRuns(): void
    {
        $claimed = $this->call(ClaimDueProactionsTool::class)['proactions'][0];
        $this->call(RecordProactionResultTool::class, $claimed['id'], 'failed', null, 'Le modèle ne répond pas');

        $instances = $this->instancesOf('series');
        self::assertSame(ProactionStatus::Failed, $instances[0]->getExecutionStatus());
        self::assertSame('Le modèle ne répond pas', $instances[0]->getError());

        Clock::set(new MockClock('2037-01-23 18:01:00 Europe/Paris'));
        self::assertSame(1, $this->call(ClaimDueProactionsTool::class)['count']);
        self::assertCount(2, $this->instancesOf('series'));
    }

    public function testListShowsTheSeriesAndItsComingOccurrencesWithinTheWindow(): void
    {
        $this->loginFixtureUser();
        Clock::set(new MockClock('2037-01-16 17:01:00 Europe/Paris'));

        $data = $this->call(ListProactionsTool::class, '2037-01-16', '2037-01-23');

        self::assertContains($this->id('series'), array_column($data['series'], 'id'));
        self::assertNotContains($this->id('series'), array_column($data['proactions'], 'id'));
        self::assertCount(2, array_filter($data['occurrences'], fn (array $o) => $o['seriesId'] === $this->id('series')));
        self::assertArrayNotHasKey('id', $data['occurrences'][0]);
        self::assertSame('Fais le point budget', $data['occurrences'][0]['prompt']);
    }

    public function testListWithoutAWindowShowsTheSeriesOnce(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(ListProactionsTool::class);

        self::assertArrayNotHasKey('occurrences', $data);
        self::assertCount(1, array_keys(array_filter(array_column($data['series'], 'id'), fn (string $id) => $id === $this->id('series'))));
    }

    public function testCancellingAnOccurrenceDetachesItAndTheOthersStillRun(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(CancelProactionTool::class, $this->id('series'), self::FRIDAY);

        self::assertTrue($data['success'], json_encode($data));
        $instances = $this->instancesOf('series');
        self::assertCount(1, $instances);
        self::assertSame(EventStatus::Cancelled, $instances[0]->getStatus());
        $this->assertMercureUpdatePublished('/proactions/');

        self::assertSame(0, $this->call(ClaimDueProactionsTool::class)['count']);
        Clock::set(new MockClock('2037-01-23 18:01:00 Europe/Paris'));
        $claimed = $this->call(ClaimDueProactionsTool::class);
        self::assertSame(1, $claimed['count']);
        self::assertEquals(new \DateTimeImmutable(self::NEXT_FRIDAY), new \DateTimeImmutable($claimed['proactions'][0]['occurrence']));
    }

    public function testAnOccurrenceNotOnTheRuleCannotBeCancelled(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(CancelProactionTool::class, $this->id('series'), '2037-01-15T18:00:00+01:00');

        self::assertArrayHasKey('error', $data);
        self::assertCount(0, $this->instancesOf('series'));
    }

    public function testDeletingTheSeriesStopsEveryRunAndKeepsThePastOnes(): void
    {
        $this->loginFixtureUser();
        $this->resetAsyncTransport();

        $data = $this->call(CancelProactionTool::class, $this->id('future_series'));

        self::assertTrue($data['success'], json_encode($data));
        $em = $this->em();
        $em->clear();
        self::assertNull($em->find(Proaction::class, $this->getFixture('future_series')->getId()));
        self::assertNull($em->find(Proaction::class, $this->getFixture('coming_instance')->getId()));
        $past = $em->find(Proaction::class, $this->getFixture('done_instance')->getId());
        self::assertNotNull($past);
        self::assertNull($past->getRecurringEvent());
        $this->assertElasticsearchDeleteDispatched('proactions');

        Clock::set(new MockClock('2037-02-20 12:00:00 Europe/Paris'));
        self::assertNotContains($this->id('future_series'), array_column($this->call(ClaimDueProactionsTool::class)['proactions'], 'seriesId'));
    }

    public function testAnotherUserNeitherSeesNorCancelsMySeries(): void
    {
        $this->loginFixtureUser('other_user');

        $listed = $this->call(ListProactionsTool::class);
        self::assertNotContains($this->id('series'), array_column($listed['series'], 'id'));

        $data = $this->call(CancelProactionTool::class, $this->id('series'));
        self::assertArrayHasKey('error', $data);
        self::assertNotNull($this->stored('series'));
    }
}
