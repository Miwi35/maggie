<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Scenario;
use Maggie\Finance\Enum\ScenarioKind;
use Maggie\Finance\Enum\ScenarioStatus;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The scenarios API (MAG-550): the three automatic ones, the user's own, the
 * one current scenario, the comparison. Read on 2026-10-21, M0 is October 2026.
 *
 * Exemption: N/A — Elasticsearch, a scenario is not indexed (the trait only
 * resets the async transport).
 */
class ScenarioControllerTest extends WebTestCase
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
        $this->loadFixtures(__DIR__.'/../Api/fixtures/scenario.yaml');

        $em = $this->em();
        $mine = $this->scenario('mine');
        $mine->setProjects($this->rows(['car', 'dentist']));
        $theirs = $this->scenario('theirs');
        $theirs->setProjects($this->rows(['secret']));
        $em->flush();

        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    private function login(string $as = 'test_user'): void
    {
        $user = $this->getFixture($as);
        \assert($user instanceof User);
        $this->authenticateAsUser($user);
    }

    /** @return array<string, mixed> */
    private function call(string $method, string $uri, mixed $body = null, bool $authenticated = true, ?string $raw = null): array
    {
        $headers = ['CONTENT_TYPE' => 'application/json'];
        $this->client->request(
            $method,
            $uri,
            [],
            [],
            $authenticated ? array_merge($headers, $this->authHeaders()) : $headers,
            $raw ?? (null === $body ? null : json_encode($body, JSON_THROW_ON_ERROR)),
        );

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
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

    private function scenario(string $fixture): Scenario
    {
        $scenario = $this->em()->find(Scenario::class, $this->id($fixture));
        \assert($scenario instanceof Scenario);

        return $scenario;
    }

    /**
     * @param list<string> $fixtures
     *
     * @return list<array{projectId: string, position: int}>
     */
    private function rows(array $fixtures): array
    {
        return array_map(fn (string $fixture, int $position): array => ['projectId' => $this->id($fixture), 'position' => $position], $fixtures, array_keys($fixtures));
    }

    /** @return list<Scenario> of the test user */
    private function stored(): array
    {
        $this->em()->clear();

        return $this->em()->getRepository(Scenario::class)->findBy(['user' => $this->id('test_user')], ['id' => 'ASC']);
    }

    /** @return list<string> */
    private function currents(string $as = 'test_user'): array
    {
        $this->em()->clear();

        return array_map(
            static fn (Scenario $scenario): string => $scenario->getName(),
            $this->em()->getRepository(Scenario::class)->findBy(['user' => $this->id($as), 'current' => true]),
        );
    }

    /** @return iterable<string, array{string, string}> */
    public static function protectedRoutes(): iterable
    {
        yield 'list' => ['GET', '/api/finance/scenarios'];
        yield 'get' => ['GET', '/api/finance/scenarios/auto-rapid'];
        yield 'create' => ['POST', '/api/finance/scenarios'];
        yield 'update' => ['PATCH', '/api/finance/scenarios/auto-rapid'];
        yield 'delete' => ['DELETE', '/api/finance/scenarios/auto-rapid'];
        yield 'duplicate' => ['POST', '/api/finance/scenarios/auto-rapid/duplicate'];
        yield 'make current' => ['POST', '/api/finance/scenarios/auto-rapid/make-current'];
        yield 'clear current' => ['DELETE', '/api/finance/current-scenario'];
        yield 'compare' => ['GET', '/api/finance/scenario-comparison?ids=auto-rapid,auto-quality'];
    }

    #[\PHPUnit\Framework\Attributes\DataProvider('protectedRoutes')]
    public function testUnauthenticatedReturns401(string $method, string $uri): void
    {
        $this->call($method, $uri, ['name' => 'X'], authenticated: false);

        self::assertResponseStatusCodeSame(401);
        self::assertCount(1, $this->stored());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheListOffersTheThreeAutomaticDraftsAndTheUsersOwn(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/scenarios');

        self::assertResponseIsSuccessful();
        self::assertSame(
            [$this->id('mine'), 'auto-rapid', 'auto-balanced', 'auto-quality'],
            array_column($data['scenarios'], 'id'),
        );
        self::assertSame(['user', 'auto', 'auto', 'auto'], array_column($data['scenarios'], 'kind'));
        self::assertSame(['saved', 'draft', 'draft', 'draft'], array_column($data['scenarios'], 'status'));
        self::assertSame(['Rapide', 'Équilibré', 'Qualité de vie'], array_slice(array_column($data['scenarios'], 'name'), 1));
        self::assertSame([false, false, false, false], array_column($data['scenarios'], 'current'), 'nothing is current by default');

        foreach ($data['scenarios'] as $scenario) {
            self::assertSame(
                ['id', 'name', 'kind', 'status', 'current', 'projects', 'repayments', 'placements', 'monthlySaving', 'nDate', 'metrics'],
                array_keys($scenario),
            );
            self::assertSame(['projectsDoneBy', 'debtsEndDate', 'interestPaid', 'wellbeing'], array_keys($scenario['metrics']));
        }

        // The Essential project comes first in every automatic scenario.
        foreach (array_slice($data['scenarios'], 1) as $auto) {
            self::assertCount(4, $auto['projects']);
            self::assertSame(['projectId' => $this->id('dentist'), 'position' => 0], $auto['projects'][0]);
        }
        self::assertSame(
            [['projectId' => $this->id('car'), 'position' => 0], ['projectId' => $this->id('dentist'), 'position' => 1]],
            $data['scenarios'][0]['projects'],
        );
    }

    public function testAnotherUsersScenarioIsNeverServed(): void
    {
        $this->login();

        self::assertNotContains($this->id('theirs'), array_column($this->call('GET', '/api/finance/scenarios')['scenarios'], 'id'));

        foreach ([
            ['GET', '/api/finance/scenarios/'.$this->id('theirs'), null],
            ['PATCH', '/api/finance/scenarios/'.$this->id('theirs'), ['name' => 'Volé']],
            ['DELETE', '/api/finance/scenarios/'.$this->id('theirs'), null],
            ['POST', '/api/finance/scenarios/'.$this->id('theirs').'/duplicate', []],
            ['POST', '/api/finance/scenarios/'.$this->id('theirs').'/make-current', null],
        ] as [$method, $uri, $body]) {
            $this->call($method, $uri, $body);
            self::assertResponseStatusCodeSame(404, "{$method} {$uri}");
        }

        $this->call('GET', '/api/finance/scenario-comparison?ids='.$this->id('mine').','.$this->id('theirs'));
        self::assertResponseStatusCodeSame(404);

        $this->em()->clear();
        self::assertSame("Plan de l'autre", $this->scenario('theirs')->getName());
        self::assertTrue($this->scenario('theirs')->isCurrent());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheDetailAddsTheCurveTheScheduleAndTheTimelineEvents(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/scenarios/auto-rapid');

        self::assertResponseIsSuccessful();
        self::assertSame('auto-rapid', $data['id']);
        foreach (['curve', 'milestones', 'schedule', 'repaymentEffects', 'placementFigures', 'events'] as $key) {
            self::assertArrayHasKey($key, $data);
        }
        self::assertNotEmpty($data['events']);
        self::assertSame(['id', 'month', 'type', 'category', 'label', 'sublabel', 'amount_eur', 'monthly_impact_eur', 'n_impact_months', 'detail'], array_keys($data['events'][0]));
    }

    public function testAnUnknownScenarioIs404(): void
    {
        $this->login();

        foreach (['auto-nope', 'not-an-id', '01ARZ3NDEKTSV4RRFFQ69G5FAV'] as $id) {
            $this->call('GET', "/api/finance/scenarios/{$id}");
            self::assertResponseStatusCodeSame(404, $id);
        }
    }

    public function testCreatePersistsPublishesAndKeepsTheOrderGiven(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/scenarios', [
            'name' => 'Japon d\'abord',
            'projectIds' => [$this->id('japan'), $this->id('dentist')],
            'placementIds' => [$this->id('pea')],
            'monthlySaving' => 30000,
        ]);

        self::assertResponseStatusCodeSame(201);
        self::assertTrue($data['success']);
        $scenario = $data['scenario'];
        self::assertSame('Japon d\'abord', $scenario['name']);
        self::assertSame(['user', 'saved', false], [$scenario['kind'], $scenario['status'], $scenario['current']]);
        self::assertSame([$this->id('japan'), $this->id('dentist')], array_column($scenario['projects'], 'projectId'));
        self::assertSame([['placementId' => $this->id('pea')]], $scenario['placements']);
        self::assertSame(30000, $scenario['monthlySaving']);
        self::assertCount(1, $scenario['placements']);

        $created = $this->em()->getRepository(Scenario::class)->findOneBy(['name' => 'Japon d\'abord']);
        self::assertInstanceOf(Scenario::class, $created);
        self::assertSame($this->id('test_user'), (string) $created->getUser()->getId());
        self::assertSame(ScenarioKind::User, $created->getKind());
        self::assertSame(ScenarioStatus::Saved, $created->getStatus());
        self::assertSame($scenario['id'], (string) $created->getId());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$created->getId());
        self::assertCount(2, $this->stored());
    }

    public function testWithoutProjectIdsACreateTakesEveryProjectInTheUsersOrder(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/scenarios', ['name' => 'Tout']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame(
            [$this->id('dentist'), $this->id('car'), $this->id('kitchen'), $this->id('japan')],
            array_column($data['scenario']['projects'], 'projectId'),
        );
        self::assertNotContains($this->id('secret'), array_column($data['scenario']['projects'], 'projectId'));
    }

    /** @return iterable<string, array{array<string, mixed>|string}> */
    public static function refusedBodies(): iterable
    {
        yield 'no name' => [['projectIds' => []]];
        yield 'blank name' => [['name' => '   ']];
        yield 'name too long' => [['name' => str_repeat('x', 121)]];
        yield 'unknown field' => [['name' => 'X', 'current' => true]];
        yield 'projects not a list' => [['name' => 'X', 'projectIds' => 'abc']];
        yield 'project twice' => [['name' => 'X', 'projectIds' => ['@car', '@car']]];
        yield 'unknown project' => [['name' => 'X', 'projectIds' => ['01ARZ3NDEKTSV4RRFFQ69G5FAV']]];
        yield 'another user\'s project' => [['name' => 'X', 'projectIds' => ['@secret']]];
        yield 'another user\'s placement' => [['name' => 'X', 'placementIds' => ['@secret_placement']]];
        yield 'negative saving' => [['name' => 'X', 'monthlySaving' => -1]];
        yield 'saving as text' => [['name' => 'X', 'monthlySaving' => '300']];
        yield 'repayment without a loan' => [['name' => 'X', 'repayments' => [['month' => '2027-01']]]];
        yield 'repayment without a month' => [['name' => 'X', 'repayments' => [['loanId' => '@consumer']]]];
        yield 'repayment on an unknown loan' => [['name' => 'X', 'repayments' => [['loanId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'month' => '2027-01', 'amountCents' => 100000]]]];
        yield 'repayment on another user\'s loan' => [['name' => 'X', 'repayments' => [['loanId' => '@foreign', 'month' => '2027-01', 'amountCents' => 100000]]]];
        yield 'repayment above the capital' => [['name' => 'X', 'repayments' => [['loanId' => '@consumer', 'month' => '2027-01', 'amountCents' => 99999999]]]];
        yield 'two repayments of a loan the same month' => [['name' => 'X', 'repayments' => [
            ['loanId' => '@consumer', 'month' => '2027-01', 'amountCents' => 100000],
            ['loanId' => '@consumer', 'month' => '2027-01', 'amountCents' => 100000],
        ]]];
        yield 'not JSON' => ['{nope'];
        yield 'not an object' => ['[1, 2]'];
    }

    /** @param array<string, mixed>|string $body */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusedBodies')]
    public function testAWrongCreateIs400AndWritesNothing(array|string $body): void
    {
        $this->login();

        if (\is_string($body)) {
            $this->call('POST', '/api/finance/scenarios', raw: $body);
        } else {
            $this->call('POST', '/api/finance/scenarios', $this->resolve($body));
        }

        self::assertResponseStatusCodeSame(400);
        self::assertCount(1, $this->stored());
        $this->assertMercureUpdateCount(0);
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function resolve(array $body): array
    {
        array_walk_recursive($body, function (mixed &$value): void {
            if (\is_string($value) && str_starts_with($value, '@')) {
                $value = $this->id(substr($value, 1));
            }
        });

        return $body;
    }

    public function testARepaymentIsKeptWithAnIdAndSimulated(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/scenarios', [
            'name' => 'Je rembourse',
            'repayments' => [['loanId' => $this->id('consumer'), 'month' => '2027-04', 'amountCents' => 300000]],
        ]);

        self::assertResponseStatusCodeSame(201);
        $repayment = $data['scenario']['repayments'][0];
        self::assertSame([$this->id('consumer'), '2027-04', 300000], [$repayment['loanId'], $repayment['month'], $repayment['amountCents']]);
        self::assertNotEmpty($repayment['id']);

        $detail = $this->call('GET', '/api/finance/scenarios/'.$data['scenario']['id']);
        self::assertCount(1, $detail['repaymentEffects']);
        self::assertSame(300000, $detail['repaymentEffects'][0]['amountCents']);
        self::assertGreaterThan(0, $detail['repaymentEffects'][0]['interestSavedCents']);
    }

    public function testUpdateChangesOnlyTheFieldsGiven(): void
    {
        $this->login();

        $data = $this->call('PATCH', '/api/finance/scenarios/'.$this->id('mine'), ['name' => 'Renommé', 'monthlySaving' => 20000]);

        self::assertResponseIsSuccessful();
        self::assertSame('Renommé', $data['scenario']['name']);
        self::assertSame(20000, $data['scenario']['monthlySaving']);
        self::assertSame([$this->id('car'), $this->id('dentist')], array_column($data['scenario']['projects'], 'projectId'));

        $mine = $this->stored()[0];
        self::assertSame('Renommé', $mine->getName());
        self::assertSame(20000, $mine->getMonthlySaving());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$this->id('mine'));

        $this->call('PATCH', '/api/finance/scenarios/'.$this->id('mine'), ['projectIds' => [$this->id('dentist')], 'monthlySaving' => null]);
        self::assertResponseIsSuccessful();
        $mine = $this->stored()[0];
        self::assertSame('Renommé', $mine->getName());
        self::assertNull($mine->getMonthlySaving());
        self::assertSame([['projectId' => $this->id('dentist'), 'position' => 0]], $mine->getProjects());
    }

    public function testAWrongUpdateIs400AndChangesNothing(): void
    {
        $this->login();

        foreach ([['name' => ''], ['projectIds' => [$this->id('secret')]], ['monthlySaving' => -5], ['placementIds' => [$this->id('secret_placement')]]] as $body) {
            $this->call('PATCH', '/api/finance/scenarios/'.$this->id('mine'), $body);
            self::assertResponseStatusCodeSame(400);
        }

        $mine = $this->stored()[0];
        self::assertSame('Mon plan', $mine->getName());
        self::assertSame([$this->id('car'), $this->id('dentist')], array_column($mine->getProjects(), 'projectId'));
        $this->assertMercureUpdateCount(0);
    }

    public function testAnAutomaticScenarioIsNotEditedNorDeleted(): void
    {
        $this->login();

        $this->call('PATCH', '/api/finance/scenarios/auto-rapid', ['name' => 'Mien']);
        self::assertResponseStatusCodeSame(400);

        $this->call('DELETE', '/api/finance/scenarios/auto-rapid');
        self::assertResponseStatusCodeSame(400);

        self::assertCount(1, $this->stored());
        $this->assertMercureUpdateCount(0);
    }

    public function testDuplicateMakesAnOwnCopyThatIsNeverCurrent(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/scenarios/'.$this->id('mine').'/duplicate', []);

        self::assertResponseStatusCodeSame(201);
        self::assertSame('Mon plan (copie)', $data['scenario']['name']);
        self::assertSame([$this->id('car'), $this->id('dentist')], array_column($data['scenario']['projects'], 'projectId'));
        self::assertFalse($data['scenario']['current']);
        self::assertNotSame($this->id('mine'), $data['scenario']['id']);
        self::assertCount(2, $this->stored());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$data['scenario']['id']);

        $named = $this->call('POST', '/api/finance/scenarios/'.$this->id('mine').'/duplicate', ['name' => 'Variante']);
        self::assertSame('Variante', $named['scenario']['name']);
    }

    public function testDuplicatingAnAutomaticScenarioGivesAUserScenarioAndStoresNoOffer(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/scenarios/auto-quality/duplicate', ['name' => 'Ma qualité']);

        self::assertResponseStatusCodeSame(201);
        self::assertSame(['user', 'saved', 'Ma qualité'], [$data['scenario']['kind'], $data['scenario']['status'], $data['scenario']['name']]);
        $autoQuality = $this->call('GET', '/api/finance/scenarios/auto-quality');
        self::assertSame(array_column($autoQuality['projects'], 'projectId'), array_column($data['scenario']['projects'], 'projectId'));

        $ids = array_column($this->call('GET', '/api/finance/scenarios')['scenarios'], 'id');
        self::assertContains('auto-quality', $ids, 'the offer stays');
        self::assertCount(2, $this->stored());
    }

    public function testOnlyOneScenarioIsCurrentAndMakingAnotherLetsTheFirstGo(): void
    {
        $this->login();

        $first = $this->call('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current');
        self::assertResponseIsSuccessful();
        self::assertTrue($first['scenario']['current']);
        self::assertSame(['Mon plan'], $this->currents());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$this->id('mine'));

        $copy = $this->call('POST', '/api/finance/scenarios/'.$this->id('mine').'/duplicate', [])['scenario'];
        $this->resetMercure();
        $second = $this->call('POST', '/api/finance/scenarios/'.$copy['id'].'/make-current');

        self::assertResponseIsSuccessful();
        self::assertTrue($second['scenario']['current']);
        self::assertSame(['Mon plan (copie)'], $this->currents());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$this->id('mine'));
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$copy['id']);

        $list = $this->call('GET', '/api/finance/scenarios')['scenarios'];
        self::assertSame(1, \count(array_filter($list, static fn (array $scenario): bool => $scenario['current'])));

        // Making the current one current again changes nothing.
        $this->resetMercure();
        $this->call('POST', '/api/finance/scenarios/'.$copy['id'].'/make-current');
        self::assertResponseIsSuccessful();
        self::assertSame(['Mon plan (copie)'], $this->currents());
        $this->assertMercureUpdateCount(0);

        self::assertSame(['Plan de l\'autre'], $this->currents('other_user'), 'another user\'s current scenario is theirs');
    }

    public function testMakingAnAutomaticScenarioCurrentStoresItAndHidesItsOffer(): void
    {
        $this->login();

        $data = $this->call('POST', '/api/finance/scenarios/auto-balanced/make-current');

        self::assertResponseIsSuccessful();
        $scenario = $data['scenario'];
        self::assertSame(['auto', 'saved', true, 'Équilibré'], [$scenario['kind'], $scenario['status'], $scenario['current'], $scenario['name']]);
        self::assertNotSame('auto-balanced', $scenario['id'], 'a stored scenario has its own id');
        self::assertCount(4, $scenario['projects']);
        self::assertSame(['Équilibré'], $this->currents());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$scenario['id']);

        $ids = array_column($this->call('GET', '/api/finance/scenarios')['scenarios'], 'id');
        self::assertNotContains('auto-balanced', $ids);
        self::assertContains($scenario['id'], $ids);
        self::assertContains('auto-rapid', $ids);

        // The stored auto scenario is read through its offer's id too, and is not edited.
        self::assertSame($scenario['id'], $this->call('GET', '/api/finance/scenarios/auto-balanced')['id']);
        $this->call('PATCH', '/api/finance/scenarios/'.$scenario['id'], ['name' => 'Autre']);
        self::assertResponseStatusCodeSame(400);

        // Deleting it brings the offer back and leaves nothing current.
        $this->call('DELETE', '/api/finance/scenarios/'.$scenario['id']);
        self::assertResponseIsSuccessful();
        self::assertSame([], $this->currents());
        self::assertContains('auto-balanced', array_column($this->call('GET', '/api/finance/scenarios')['scenarios'], 'id'));
    }

    public function testClearCurrentLeavesNoneCurrent(): void
    {
        $this->login();
        $this->call('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current');
        $this->resetMercure();

        $data = $this->call('DELETE', '/api/finance/current-scenario');

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertSame([], $this->currents());
        $this->assertMercureUpdatePublished('/api/finance/scenarios/'.$this->id('mine'));

        // And it is harmless when there is none.
        $this->call('DELETE', '/api/finance/current-scenario');
        self::assertResponseIsSuccessful();
    }

    public function testDeleteRemovesAndPublishesTheDeletion(): void
    {
        $this->login();

        $data = $this->call('DELETE', '/api/finance/scenarios/'.$this->id('mine'));

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertSame([], $this->stored());
        $this->assertMercureDeletePublished('/api/finance/scenarios/'.$this->id('mine'));
        self::assertTrue($this->scenario('theirs')->isCurrent());
    }

    public function testTheDatabaseRefusesASecondCurrentScenarioForAUser(): void
    {
        $em = $this->em();
        $this->scenario('mine')->setCurrent(true);
        $em->flush();

        $second = (new Scenario())
            ->setUser($this->getFixture('test_user'))
            ->setName('Deuxième')
            ->setKind(ScenarioKind::User)
            ->setStatus(ScenarioStatus::Saved)
            ->setCurrent(true);
        $em->persist($second);

        $this->expectException(\Doctrine\DBAL\Exception\UniqueConstraintViolationException::class);
        $em->flush();
    }

    public function testThePlanningFollowsTheCurrentScenarioAndOnlyIt(): void
    {
        $this->login();
        $names = fn (): array => array_column($this->call('GET', '/api/finance/project-planning')['projects'], 'name');

        self::assertSame(['Dentiste', 'Voiture', 'Cuisine', 'Voyage au Japon'], $names(), 'no current scenario: every project, in the user\'s order');

        $this->call('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current');
        $followed = $names();
        self::assertSame(['Voiture', 'Dentiste'], $followed, 'the current scenario: its projects, in its order');

        $this->call('DELETE', '/api/finance/current-scenario');
        self::assertSame(['Dentiste', 'Voiture', 'Cuisine', 'Voyage au Japon'], $names());
    }

    public function testTheComparisonGivesCurvesMetricsAndGapsAgainstTheFirst(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/scenario-comparison?ids=auto-rapid,auto-quality,'.$this->id('mine'));

        self::assertResponseIsSuccessful();
        self::assertSame('auto-rapid', $data['referenceId']);
        self::assertSame(['auto-rapid', 'auto-quality', $this->id('mine')], array_column($data['scenarios'], 'id'));
        foreach ($data['scenarios'] as $scenario) {
            self::assertArrayHasKey('curve', $scenario);
            self::assertArrayHasKey('metrics', $scenario);
            self::assertArrayNotHasKey('events', $scenario);
        }

        self::assertSame(['auto-quality', $this->id('mine')], array_column($data['differences'], 'scenarioId'));
        foreach ($data['differences'] as $difference) {
            self::assertSame('auto-rapid', $difference['againstId']);
            self::assertMatchesRegularExpression('/^N (\d+ (an|ans|mois)( \d+ mois)? (plus tôt|plus tard) que|à la même date que|non atteinte|atteinte)/u', $difference['sentence']);
            self::assertStringNotContainsStringIgnoringCase('meilleur', $difference['sentence']);
            self::assertStringNotContainsStringIgnoringCase('recommand', $difference['sentence']);
        }
        self::assertArrayNotHasKey('recommended', $data);
        self::assertArrayNotHasKey('best', $data);
    }

    public function testAComparisonNeedsAtLeastTwoDistinctScenarios(): void
    {
        $this->login();

        foreach (['', 'auto-rapid', 'auto-rapid,auto-rapid'] as $ids) {
            $this->call('GET', "/api/finance/scenario-comparison?ids={$ids}");
            self::assertResponseStatusCodeSame(400, $ids);
        }

        $this->call('GET', '/api/finance/scenario-comparison?ids=auto-rapid,auto-nope');
        self::assertResponseStatusCodeSame(404);
    }
}
