<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Mcp\Tool\PreviewProjectTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * preview_project (MAG-553): the same numbers as the REST preview, partial
 * requests refused with `current`, nothing written, nothing of another user.
 */
class PreviewProjectToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_preview.yaml');

        $theirs = $this->em()->find(Scenario::class, $this->id('theirs'));
        \assert($theirs instanceof Scenario);
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
    private function call(mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(PreviewProjectTool::class);
        \assert($tool instanceof PreviewProjectTool);

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
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

    public function testWithoutAUserItAnswersAnError(): void
    {
        $data = $this->call(name: 'Box à louer', amountCents: 1800000);

        self::assertArrayHasKey('error', $data);
        self::assertArrayNotHasKey('dates', $data);
    }

    public function testItGivesTheDatesTheNDateAndTheMetrics(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(name: 'Box à louer', amountCents: 1800000, wellbeing: 'high');

        self::assertArrayNotHasKey('error', $data);
        self::assertSame(['scenario', 'project', 'dates', 'nDate', 'metrics'], array_keys($data));
        self::assertSame(['Dentiste', 'Voiture', 'Cuisine', 'Voyage au Japon'], array_column($data['dates'], 'name'));
        self::assertSame(['before', 'after'], array_keys($data['nDate']));
        self::assertSame(['realCost', 'availableAt', 'nImpactMonths'], array_keys($data['metrics']));
        self::assertSame(1800000, $data['metrics']['realCost']);
    }

    public function testItCanPreviewInAnotherScenarioThanTheCurrentOne(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(name: 'Box à louer', amountCents: 1800000, scenarioId: $this->id('mine'));

        self::assertSame(['id' => $this->id('mine'), 'name' => 'Mon plan', 'editable' => true], $data['scenario']);

        $auto = $this->call(name: 'Box à louer', amountCents: 1800000, scenarioId: 'auto-balanced');
        self::assertFalse($auto['scenario']['editable']);
    }

    public function testAPartialRequestIsRefusedWithWhatWasGiven(): void
    {
        $this->loginFixtureUser();

        $data = $this->call(name: 'Box à louer');

        self::assertStringContainsString('name and amountCents are required', $data['error']);
        self::assertSame(['name' => 'Box à louer'], $data['current']);
        self::assertArrayNotHasKey('dates', $data);
    }

    public function testADateModeAndItsDateGoTogether(): void
    {
        $this->loginFixtureUser();

        $fixedWithoutDate = $this->call(name: 'Box', amountCents: 1800000, dateMode: 'fixed');
        self::assertStringContainsString('needs a targetOn', $fixedWithoutDate['error']);
        self::assertSame('fixed', $fixedWithoutDate['current']['dateMode']);

        $dateWithoutMode = $this->call(name: 'Box', amountCents: 1800000, targetOn: '2027-06-01');
        self::assertStringContainsString('needs dateMode', $dateWithoutMode['error']);
    }

    public function testABadInputIsRefusedAsAtCreation(): void
    {
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->call(name: 'Box', amountCents: 0));
        self::assertArrayHasKey('error', $this->call(name: ' ', amountCents: 1000));
        self::assertArrayHasKey('error', $this->call(name: 'Box', amountCents: 1000, wellbeing: 'luxury'));
        self::assertArrayHasKey('error', $this->call(name: 'Box', amountCents: 1000, dateMode: 'someday', targetOn: '2027-06-01'));
    }

    public function testAnotherUsersScenarioOrProjectIsNotFoundAndLeaksNothing(): void
    {
        $this->loginFixtureUser();

        $scenario = $this->call(name: 'Box', amountCents: 1800000, scenarioId: $this->id('theirs'));
        self::assertArrayHasKey('error', $scenario);
        self::assertStringNotContainsString('Plan de l\'autre', json_encode($scenario, JSON_THROW_ON_ERROR));

        $dependency = $this->call(name: 'Box', amountCents: 1800000, dependsOn: [$this->id('secret')]);
        self::assertArrayHasKey('error', $dependency);
        self::assertStringNotContainsString('Projet secret', json_encode($dependency, JSON_THROW_ON_ERROR));
    }

    public function testItNeverWritesAnything(): void
    {
        $this->loginFixtureUser();
        $projects = $this->em()->getRepository(Project::class)->count([]);
        $scenarios = $this->em()->getRepository(Scenario::class)->count([]);
        $this->em()->clear();

        $this->call(name: 'Box à louer', amountCents: 1800000, dependsOn: [$this->id('car')]);

        self::assertSame($projects, $this->em()->getRepository(Project::class)->count([]));
        self::assertSame($scenarios, $this->em()->getRepository(Scenario::class)->count([]));
        self::assertNull($this->em()->getRepository(Project::class)->findOneBy(['name' => 'Box à louer']));
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Project::class);
    }
}
