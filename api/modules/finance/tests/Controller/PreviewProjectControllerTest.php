<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\Scenario;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * POST /api/finance/projects/preview (MAG-553): what adding a project changes
 * in the current scenario, computed without writing anything. Read on
 * 2026-10-21, M0 is October 2026.
 */
class PreviewProjectControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        Clock::set(new MockClock('2026-10-21 12:00:00 UTC'));
        $this->loadFixtures(__DIR__.'/../Api/fixtures/project_preview.yaml');

        $theirs = $this->em()->find(Scenario::class, $this->id('theirs'));
        \assert($theirs instanceof Scenario);
        $theirs->setProjects([['projectId' => $this->id('secret'), 'position' => 0]]);
        $this->em()->flush();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function login(): void
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array<string, mixed> */
    private function call(string $method, string $uri, mixed $body = null, ?string $raw = null): array
    {
        $this->client->request(
            $method,
            $uri,
            [],
            [],
            array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()),
            $raw ?? (null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR)),
        );

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function preview(mixed $body): array
    {
        return $this->call('POST', '/api/finance/projects/preview', $body);
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

    private function makeCurrent(string $fixture, array $projects): void
    {
        $scenario = $this->em()->find(Scenario::class, $this->id($fixture));
        \assert($scenario instanceof Scenario);
        $scenario->setProjects(array_map(fn (string $project, int $position): array => ['projectId' => $this->id($project), 'position' => $position], $projects, array_keys($projects)));
        $scenario->setCurrent(true);
        $this->em()->flush();
    }

    /** @return array<string, string|null> project name → month it is bought, as GET /project-planning gives it */
    private function planned(): array
    {
        $planning = $this->call('GET', '/api/finance/project-planning');

        return array_column($planning['projects'], 'purchaseMonth', 'name');
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/finance/projects/preview', [], [], ['CONTENT_TYPE' => 'application/json'], '{"name":"Box","amountCents":1800000}');

        self::assertResponseStatusCodeSame(401);
    }

    /** @return iterable<string, array{mixed, string}> */
    public static function badBodies(): iterable
    {
        yield 'no amount' => [['name' => 'Box'], 'amountCents'];
        yield 'no name' => [['amountCents' => 1800000], 'name'];
        yield 'amount as a string' => [['name' => 'Box', 'amountCents' => '1800000'], 'amountCents'];
        yield 'zero amount' => [['name' => 'Box', 'amountCents' => 0], 'amountCents'];
        yield 'blank name' => [['name' => ' ', 'amountCents' => 100], 'name'];
        yield 'unknown field' => [['name' => 'Box', 'amountCents' => 100, 'color' => 'red'], 'Unknown field'];
        yield 'unknown wellbeing' => [['name' => 'Box', 'amountCents' => 100, 'wellbeing' => 'huge'], 'wellbeing'];
        yield 'fixed without a date' => [['name' => 'Box', 'amountCents' => 100, 'dateMode' => 'fixed'], 'targetOn'];
        yield 'a date with no mode' => [['name' => 'Box', 'amountCents' => 100, 'targetOn' => '2027-03-01'], 'targetOn'];
        yield 'not a day' => [['name' => 'Box', 'amountCents' => 100, 'dateMode' => 'fixed', 'targetOn' => '2027-02-30'], 'Not a day'];
        yield 'dependencies not a list' => [['name' => 'Box', 'amountCents' => 100, 'dependsOn' => 'abc'], 'dependsOn'];
        yield 'a list, not an object' => [[1, 2], 'JSON object'];
    }

    #[DataProvider('badBodies')]
    public function testABadBodyReturns400(mixed $body, string $message): void
    {
        $this->login();

        $data = $this->preview($body);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString($message, $data['error']);
    }

    public function testABodyThatIsNotJsonReturns400(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/projects/preview', null, '{nope');

        self::assertResponseStatusCodeSame(400);
        self::assertSame('The body is not JSON.', $data['error']);
    }

    public function testItNeverWritesAnything(): void
    {
        $this->login();
        $projects = $this->em()->getRepository(Project::class)->count([]);
        $scenarios = $this->em()->getRepository(Scenario::class)->count([]);
        $this->em()->clear();

        $this->preview(['name' => 'Box à louer', 'amountCents' => 1800000, 'wellbeing' => 'high', 'dependsOn' => [$this->id('car')]]);

        self::assertResponseIsSuccessful();
        self::assertSame($projects, $this->em()->getRepository(Project::class)->count([]));
        self::assertSame($scenarios, $this->em()->getRepository(Scenario::class)->count([]));
        self::assertNull($this->em()->getRepository(Project::class)->findOneBy(['name' => 'Box à louer']));
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Project::class);
    }

    public function testWithNoCurrentScenarioTheUsersListIsPlannedInPriorityOrder(): void
    {
        $this->login();
        $now = $this->planned();

        $data = $this->preview(['name' => 'Box à louer', 'amountCents' => 1800000]);

        self::assertResponseIsSuccessful();
        self::assertSame(['scenario', 'project', 'dates', 'nDate', 'metrics'], array_keys($data));
        self::assertNull($data['scenario']);
        self::assertSame(['name' => 'Box à louer', 'amountCents' => 1800000, 'currency' => 'EUR'], $data['project']);
        self::assertSame(['Dentiste', 'Voiture', 'Cuisine', 'Voyage au Japon'], array_column($data['dates'], 'name'));
        self::assertSame(array_values($now), array_column($data['dates'], 'before'));
        self::assertSame(1800000, $data['metrics']['realCost']);
    }

    public function testTheOthersDatesAndTheNewOneAreWhatTheRealCreationGives(): void
    {
        $this->login();
        $preview = $this->preview(['name' => 'Box à louer', 'amountCents' => 1800000]);
        self::assertResponseIsSuccessful();

        // An 18 000 € project comes last: the others do not move, and it is bought after them.
        $this->call('POST', '/api/finance/projects', ['name' => 'Box à louer', 'amountCents' => 1800000]);
        self::assertResponseStatusCodeSame(201);
        $real = $this->planned();

        foreach ($preview['dates'] as $row) {
            self::assertSame($real[$row['name']], $row['after'], $row['name']);
        }
        self::assertSame($real['Box à louer'], $preview['metrics']['availableAt']);
    }

    public function testAProjectWithAFixedDateShiftsTheOthers(): void
    {
        $this->login();
        $now = $this->planned();

        $preview = $this->preview(['name' => 'Mariage', 'amountCents' => 1800000, 'dateMode' => 'fixed', 'targetOn' => '2027-06-15']);
        self::assertResponseIsSuccessful();

        $this->call('POST', '/api/finance/projects', ['name' => 'Mariage', 'amountCents' => 1800000, 'dateMode' => 'fixed', 'targetOn' => '2027-06-15']);
        $real = $this->planned();

        $moved = array_filter($preview['dates'], static fn (array $row): bool => $row['before'] !== $row['after']);
        self::assertNotSame([], $moved, 'A fixed date takes its share of every month: something moves.');
        foreach ($preview['dates'] as $row) {
            self::assertSame($now[$row['name']], $row['before'], $row['name']);
            self::assertSame($real[$row['name']], $row['after'], $row['name']);
        }
        self::assertSame('2027-06', $preview['metrics']['availableAt']);
    }

    public function testItIsComputedInTheCurrentScenario(): void
    {
        $this->makeCurrent('mine', ['car', 'dentist']);
        $this->login();

        $data = $this->preview(['name' => 'Box à louer', 'amountCents' => 1800000]);

        self::assertResponseIsSuccessful();
        self::assertSame(['id' => $this->id('mine'), 'name' => 'Mon plan', 'editable' => true], $data['scenario']);
        self::assertSame(['Voiture', 'Dentiste'], array_column($data['dates'], 'name'));
        self::assertSame(array_values($this->planned()), array_column($data['dates'], 'before'));
    }

    public function testItCanBePreviewedInAnotherScenarioOfTheUser(): void
    {
        $this->makeCurrent('mine', ['car']);
        $this->login();

        $data = $this->preview(['name' => 'Box à louer', 'amountCents' => 100000, 'scenarioId' => 'auto-quality']);

        self::assertResponseIsSuccessful();
        self::assertSame(['id' => 'auto-quality', 'name' => 'Qualité de vie', 'editable' => false], $data['scenario']);
        self::assertContains('Dentiste', array_column($data['dates'], 'name'));
    }

    public function testAnotherUsersScenarioIsNotFound(): void
    {
        $this->login();

        $data = $this->preview(['name' => 'Box', 'amountCents' => 100000, 'scenarioId' => $this->id('theirs')]);

        self::assertResponseStatusCodeSame(404);
        self::assertStringContainsString('not found', $data['error']);
        self::assertStringNotContainsString('secret', json_encode($data, JSON_THROW_ON_ERROR));
    }

    public function testAnotherUsersProjectCannotBeADependency(): void
    {
        $this->login();

        $data = $this->preview(['name' => 'Box', 'amountCents' => 100000, 'dependsOn' => [$this->id('secret')]]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('Project not found', $data['error']);
    }

    public function testAnotherUsersProjectsNeverAppear(): void
    {
        $this->login();

        $data = $this->preview(['name' => 'Box', 'amountCents' => 100000]);

        self::assertNotContains('Projet secret', array_column($data['dates'], 'name'));
        self::assertNotContains($this->id('secret'), array_column($data['dates'], 'projectId'));
    }

    public function testTheDependencyKeepsTheNewProjectAfterIt(): void
    {
        $this->makeCurrent('mine', ['japan']);
        $this->login();

        $data = $this->preview(['name' => 'Box', 'amountCents' => 100000, 'dependsOn' => [$this->id('japan')]]);

        self::assertResponseIsSuccessful();
        $japan = array_values(array_filter($data['dates'], static fn (array $row): bool => 'Voyage au Japon' === $row['name']))[0];
        self::assertNotNull($data['metrics']['availableAt']);
        self::assertGreaterThanOrEqual($japan['after'], $data['metrics']['availableAt']);
    }

    public function testNImpactIsTheGapBetweenTheTwoDates(): void
    {
        $this->login();

        $data = $this->preview(['name' => 'Box', 'amountCents' => 1800000]);

        $before = $data['nDate']['before'];
        $after = $data['nDate']['after'];
        if (null === $before || null === $after) {
            self::assertNull($data['metrics']['nImpactMonths']);

            return;
        }
        [$by, $bm] = array_map('intval', explode('-', $before));
        [$ay, $am] = array_map('intval', explode('-', $after));
        self::assertSame(($ay * 12 + $am) - ($by * 12 + $bm), $data['metrics']['nImpactMonths']);
    }
}
