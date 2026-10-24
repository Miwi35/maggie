<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Scenario;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/**
 * The 20-year frise (MAG-554): the current scenario's events, a compared
 * scenario beside it, the assumptions line, the gap of N in facts. Read on
 * 2026-10-21, M0 is October 2026.
 *
 * Exemption: N/A — read-only endpoint: nothing is written, so no Mercure
 * update and no Elasticsearch dispatch (the traits only reset the transports).
 */
class FinanceTimelineControllerTest extends WebTestCase
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
        $this->loadFixtures(__DIR__.'/../Api/fixtures/timeline.yaml');

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $mine = $em->find(Scenario::class, $this->id('mine'));
        \assert($mine instanceof Scenario);
        $mine->setProjects([
            ['projectId' => $this->id('trip'), 'position' => 0],
            ['projectId' => $this->id('car'), 'position' => 1],
        ])->setPlacements([['placementId' => $this->id('pea')]])->setMonthlySaving(150000);
        $em->flush();

        $this->resetMercure();
        $this->resetAsyncTransport();
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
    }

    private function id(string $fixture): string
    {
        return (string) $this->getFixture($fixture)->getId();
    }

    /** @return array<string, mixed> */
    private function timeline(string $query = '', bool $authenticated = true): array
    {
        $this->client->request('GET', '/api/finance/timeline'.$query, [], [], $authenticated ? $this->authHeaders() : []);

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $line */
    private static function eventMonths(array $line, string $type): array
    {
        return array_column(array_filter($line['events'], static fn (array $e): bool => $type === $e['type']), 'month', 'id');
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->timeline(authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testWithoutACurrentScenarioTheFriseDrawsTheUsersPlanning(): void
    {
        $this->login();

        $data = $this->timeline();

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10', $data['startMonth']);
        self::assertSame(240, $data['horizonMonths']);
        self::assertSame('Hypothèses : matelas intact, revenus stables, sans vacance locative ni imprévu', $data['assumptions']);
        self::assertNull($data['current']['scenarioId']);
        self::assertSame('Planning sans scénario', $data['current']['name']);
        self::assertNull($data['compared']);
        self::assertNull($data['difference']);
        self::assertSame(
            ['projet' => '#B8DE6F', 'dette' => '#8FB8FF', 'investissement' => '#F2C46D', 'indépendance' => '#7FD1AE', 'fiscal' => '#C7B5FF', 'matelas' => '#ABA6B8'],
            array_column($data['categories'], 'color', 'key'),
        );

        self::assertSame(
            ['project_purchase:'.$this->id('car') => '2027-01', 'project_purchase:'.$this->id('trip') => '2027-02'],
            self::eventMonths($data['current'], 'project_purchase'),
        );
        self::assertSame(['debt_end:loan:'.$this->id('consumer') => '2027-08'], self::eventMonths($data['current'], 'debt_end'));
    }

    public function testTheCurrentScenarioIsTheOneDrawnWithItsEventsOnTheirMonths(): void
    {
        $this->login();
        $this->client->request('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current', [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        $data = $this->timeline();

        self::assertSame($this->id('mine'), $data['current']['scenarioId']);
        self::assertSame('Mon plan', $data['current']['name']);
        self::assertSame(
            ['project_purchase:'.$this->id('trip') => '2026-11', 'project_purchase:'.$this->id('car') => '2027-03'],
            self::eventMonths($data['current'], 'project_purchase'),
        );
        self::assertSame(['debt_end:loan:'.$this->id('consumer') => '2027-08'], self::eventMonths($data['current'], 'debt_end'));
        self::assertSame(['placement_milestone:'.$this->id('pea') => '2036-10'], self::eventMonths($data['current'], 'placement_milestone'));
        self::assertSame(
            ['independence_milestone:25' => '2026-10', 'independence_milestone:50' => '2030-09', 'independence_milestone:75' => '2038-06'],
            self::eventMonths($data['current'], 'independence_milestone'),
        );
    }

    public function testIndependenceAppearsOnTheDateNOfTheCurrentScenario(): void
    {
        $this->login();
        $this->client->request('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current', [], [], $this->authHeaders());

        $data = $this->timeline();

        self::assertSame('2046-03', $data['current']['nDate']);
        self::assertSame(['independence_reached' => '2046-03'], self::eventMonths($data['current'], 'independence_reached'));
        $reached = array_values(array_filter($data['current']['events'], static fn (array $e): bool => 'independence_reached' === $e['type']))[0];
        self::assertSame('indépendance', $reached['category']);
    }

    public function testAComparedScenarioComesBesideTheCurrentOneWithTheGapOfNInFacts(): void
    {
        $this->login();
        $this->client->request('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current', [], [], $this->authHeaders());

        $data = $this->timeline('?compare=auto-quality');

        self::assertResponseIsSuccessful();
        self::assertSame('auto-quality', $data['compared']['scenarioId']);
        self::assertSame('Qualité de vie', $data['compared']['name']);
        self::assertSame('2043-09', $data['compared']['nDate']);
        self::assertSame(['independence_reached' => '2043-09'], self::eventMonths($data['compared'], 'independence_reached'));
        self::assertSame('2046-03', $data['current']['nDate']);
        self::assertSame(-30, $data['difference']['months']);
        self::assertSame('N 2 ans 6 mois plus tôt que « Mon plan »', $data['difference']['sentence']);
        self::assertStringNotContainsStringIgnoringCase('meilleur', $data['difference']['sentence']);
        self::assertStringNotContainsStringIgnoringCase('recommand', $data['difference']['sentence']);
    }

    public function testNoEventPastTwentyYears(): void
    {
        $this->login();

        $data = $this->timeline('?compare=auto-rapid');

        foreach ([$data['current'], $data['compared']] as $line) {
            self::assertNotSame([], $line['events']);
            foreach ($line['events'] as $event) {
                self::assertLessThanOrEqual('2046-09', $event['month']);
            }
        }
    }

    public function testComparingTheCurrentScenarioWithItselfIs400(): void
    {
        $this->login();
        $this->client->request('POST', '/api/finance/scenarios/'.$this->id('mine').'/make-current', [], [], $this->authHeaders());

        $this->timeline('?compare='.$this->id('mine'));

        self::assertResponseStatusCodeSame(400);
    }

    public function testAnUnknownOrForeignScenarioIs404(): void
    {
        $this->login();

        foreach (['auto-nope', $this->id('theirs'), '01ARZ3NDEKTSV4RRFFQ69G5FAV'] as $id) {
            $this->timeline('?compare='.$id);
            self::assertResponseStatusCodeSame(404, $id);
        }
    }

    public function testAnEmptyCompareIs400(): void
    {
        $this->login();

        $this->timeline('?compare=');

        self::assertResponseStatusCodeSame(400);
    }
}
