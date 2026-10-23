<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Enum\ScenarioStatus;
use Maggie\Finance\Mcp\Tool\ManageScenariosTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * manage_scenarios (MAG-550): every action, partial requests refused with
 * `current`, nothing completed by deduction, nothing recommended.
 *
 * Exemption: N/A — Elasticsearch, a scenario is not indexed (the trait only
 * resets the async transport).
 */
class ScenarioToolsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/scenario.yaml');

        $mine = $this->em()->find(Scenario::class, $this->id('mine'));
        $theirs = $this->em()->find(Scenario::class, $this->id('theirs'));
        \assert($mine instanceof Scenario && $theirs instanceof Scenario);
        $mine->setProjects([['projectId' => $this->id('car'), 'position' => 0], ['projectId' => $this->id('dentist'), 'position' => 1]]);
        $theirs->setProjects([['projectId' => $this->id('secret'), 'position' => 0]]);
        $this->em()->flush();

        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, mixed> */
    private function call(string $action, mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageScenariosTool::class);
        \assert($tool instanceof ManageScenariosTool);

        return json_decode($tool($action, ...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    /** @return list<Scenario> of the test user */
    private function stored(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Scenario::class)->findBy(['user' => $this->id('test_user')], ['id' => 'ASC']);
    }

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call('list');

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('scenarios', $data);
    }

    public function testAnUnknownActionIsRefused(): void
    {
        $this->loginFixtureUser();

        self::assertStringContainsString('Unknown action', $this->call('archive')['error']);
    }

    public function testListGivesTheUsersScenariosAndTheThreeAutomaticOnes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('list');

        self::assertSame([$this->id('mine'), 'auto-rapid', 'auto-balanced', 'auto-quality'], array_column($data['scenarios'], 'id'));
        self::assertSame(['user', 'auto', 'auto', 'auto'], array_column($data['scenarios'], 'kind'));
        self::assertSame([false, false, false, false], array_column($data['scenarios'], 'current'));
        self::assertSame(['projectsDoneBy', 'debtsEndDate', 'interestPaid', 'wellbeing'], array_keys($data['scenarios'][1]['metrics']));
    }

    public function testGetGivesTheDetailWithAYearlyCurveAndTheEvents(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('get', scenarioId: 'auto-quality');

        self::assertSame('auto-quality', $data['scenario']['id']);
        self::assertNotEmpty($data['scenario']['events']);
        foreach ($data['scenario']['curve'] as $point) {
            self::assertStringEndsWith('-01', $point['month']);
        }

        self::assertSame('scenarioId is required for get.', $this->call('get')['error']);
        self::assertSame('Scenario not found: '.$this->id('theirs'), $this->call('get', scenarioId: $this->id('theirs'))['error']);
    }

    public function testCreatePersistsAndPublishes(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Dentiste puis voiture', projectIds: [$this->id('dentist'), $this->id('car')], monthlySaving: 25000);

        self::assertTrue($data['success']);
        self::assertSame([$this->id('dentist'), $this->id('car')], array_column($data['scenario']['projects'], 'projectId'));
        self::assertSame(25000, $data['scenario']['monthlySaving']);
        self::assertArrayNotHasKey('events', $data['scenario']);

        $created = $this->em()->getRepository(Scenario::class)->findOneBy(['name' => 'Dentiste puis voiture']);
        self::assertInstanceOf(Scenario::class, $created);
        self::assertSame([ScenarioKind::User, ScenarioStatus::Saved, false], [$created->getKind(), $created->getStatus(), $created->isCurrent()]);
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$created->getId());
    }

    public function testAPartialCreateIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Sans projets');

        self::assertStringContainsString('projectIds', $data['error']);
        self::assertSame(['name' => 'Sans projets'], $data['current']);
        self::assertCount(1, $this->stored());
        $this->assertMercureUpdateCount(0);
    }

    public function testAWrongCreateIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('create', name: 'Piégé', projectIds: [$this->id('secret')]);

        self::assertStringContainsString('Project not found', $data['error']);
        self::assertSame('Piégé', $data['current']['name']);

        $data = $this->call('create', name: 'Remboursement', projectIds: [$this->id('car')], repayments: [['loanId' => $this->id('foreign'), 'month' => '2027-01', 'amountCents' => 100000]]);
        self::assertStringContainsString('Loan not found', $data['error']);
        self::assertCount(1, $this->stored());
    }

    public function testSuggestFilesAnAdvisorDraftThatIsNeverCurrent(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('suggest', name: 'Idée du conseiller', projectIds: [$this->id('dentist')]);

        self::assertTrue($data['success']);
        self::assertSame(['advisor', 'draft', false], [$data['scenario']['kind'], $data['scenario']['status'], $data['scenario']['current']]);
        self::assertStringContainsString('projectIds', $this->call('suggest', name: 'Incomplète')['error']);

        // A draft can be made current: it then becomes a saved scenario.
        $made = $this->call('make_current', scenarioId: $data['scenario']['id']);
        self::assertSame(['advisor', 'saved', true], [$made['scenario']['kind'], $made['scenario']['status'], $made['scenario']['current']]);
    }

    public function testDuplicateCopiesAndNamesTheCopy(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('duplicate', scenarioId: $this->id('mine'), name: 'Variante');

        self::assertTrue($data['success']);
        self::assertSame('Variante', $data['scenario']['name']);
        self::assertSame([$this->id('car'), $this->id('dentist')], array_column($data['scenario']['projects'], 'projectId'));
        self::assertCount(2, $this->stored());
        self::assertSame('scenarioId is required for duplicate.', $this->call('duplicate')['error']);
        self::assertStringContainsString('not found', $this->call('duplicate', scenarioId: $this->id('theirs'))['error']);
    }

    public function testUpdateChangesOnlyWhatIsGivenAndRefusalsCarryTheCurrentState(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('update', scenarioId: $this->id('mine'), name: 'Renommé');
        self::assertTrue($data['success']);
        self::assertSame('Renommé', $data['scenario']['name']);
        self::assertSame([$this->id('car'), $this->id('dentist')], array_column($data['scenario']['projects'], 'projectId'));
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$this->id('mine'));

        $this->call('update', scenarioId: $this->id('mine'), monthlySaving: 40000);
        self::assertSame(40000, $this->stored()[0]->getMonthlySaving());
        $this->call('update', scenarioId: $this->id('mine'), resetMonthlySaving: true);
        self::assertNull($this->stored()[0]->getMonthlySaving());

        $refused = $this->call('update', scenarioId: $this->id('mine'), projectIds: [$this->id('secret')]);
        self::assertStringContainsString('Project not found', $refused['error']);
        self::assertSame('Renommé', $refused['current']['name']);

        $nothing = $this->call('update', scenarioId: $this->id('mine'));
        self::assertStringContainsString('Nothing to change', $nothing['error']);
        self::assertSame('Renommé', $nothing['current']['name']);

        self::assertSame('scenarioId is required for update.', $this->call('update', name: 'X')['error']);
        self::assertStringContainsString('not found', $this->call('update', scenarioId: $this->id('theirs'), name: 'Volé')['error']);
        self::assertStringContainsString('duplicate', $this->call('update', scenarioId: 'auto-rapid', name: 'Mien')['error']);
    }

    public function testContradictoryOrOversizedRequestsAreRefused(): void
    {
        $this->loginFixtureUser();

        $both = $this->call('update', scenarioId: $this->id('mine'), monthlySaving: 100, resetMonthlySaving: true);
        self::assertStringContainsString('contradict', $both['error']);
        self::assertNull($this->stored()[0]->getMonthlySaving());

        $nested = $this->call('compare', scenarioIds: ['auto-rapid', ['auto-quality']]);
        self::assertStringContainsString('list of scenario ids', $nested['error']);

        $many = $this->call('compare', scenarioIds: ['auto-rapid', 'auto-balanced', 'auto-quality', $this->id('mine'), 'a', 'b', 'c']);
        self::assertStringContainsString('at most', $many['error']);
    }

    public function testMakeCurrentKeepsASingleOneAndClearCurrentLeavesNone(): void
    {
        $this->loginFixtureUser();

        $first = $this->call('make_current', scenarioId: 'auto-rapid');
        self::assertTrue($first['scenario']['current']);
        self::assertSame('Rapide', $first['scenario']['name']);

        $second = $this->call('make_current', scenarioId: $this->id('mine'));
        self::assertTrue($second['scenario']['current']);

        $currents = array_filter($this->call('list')['scenarios'], static fn (array $scenario): bool => $scenario['current']);
        self::assertSame([$this->id('mine')], array_column($currents, 'id'));
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$this->id('mine'));

        self::assertTrue($this->call('clear_current')['success']);
        self::assertSame([], array_filter($this->call('list')['scenarios'], static fn (array $scenario): bool => $scenario['current']));

        self::assertStringContainsString('not found', $this->call('make_current', scenarioId: $this->id('theirs'))['error']);
    }

    public function testDeleteRemovesTheScenario(): void
    {
        $this->loginFixtureUser();

        self::assertTrue($this->call('delete', scenarioId: $this->id('mine'))['success']);
        self::assertSame([], $this->stored());
        $this->assertMercureDeletePublished('/api/finance/scenarios/'.$this->id('mine'));

        self::assertSame('scenarioId is required for delete.', $this->call('delete')['error']);
        self::assertStringContainsString('not found', $this->call('delete', scenarioId: $this->id('theirs'))['error']);
        self::assertNotNull($this->em()->find(Scenario::class, $this->id('theirs')));
    }

    public function testCompareAnswersFiguresAndGapsWithoutAnyVerdict(): void
    {
        $this->loginFixtureUser();

        $data = $this->call('compare', scenarioIds: ['auto-rapid', 'auto-quality', $this->id('mine')]);

        self::assertSame('auto-rapid', $data['referenceId']);
        self::assertCount(3, $data['scenarios']);
        self::assertCount(2, $data['differences']);
        foreach ($data['scenarios'] as $scenario) {
            foreach ($scenario['curve'] as $point) {
                self::assertStringEndsWith('-01', $point['month']);
            }
        }
        $encoded = json_encode($data, JSON_THROW_ON_ERROR);
        self::assertDoesNotMatchRegularExpression('/recommand|meilleur|"best"/iu', $encoded);

        self::assertStringContainsString('at least two', $this->call('compare', scenarioIds: ['auto-rapid'])['error']);
        self::assertStringContainsString('scenarioIds is required', $this->call('compare')['error']);
        self::assertStringContainsString('not found', $this->call('compare', scenarioIds: ['auto-rapid', $this->id('theirs')])['error']);
    }
}
