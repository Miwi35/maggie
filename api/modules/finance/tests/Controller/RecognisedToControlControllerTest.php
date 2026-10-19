<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class RecognisedToControlControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
    }

    private function login(): void
    {
        $this->loadFixtures('recognised_to_control.yaml');

        // The operation behind a line is set by qualifying it, as the engine does.
        foreach (['carrefour_a', 'carrefour_b', 'carrefour_c', 'carrefour_checked', 'carrefour_september'] as $ref) {
            $line = $this->getFixture($ref);
            $line->qualify($line->getQualification(), $this->getFixture('rule_carrefour'));
        }
        $gym = $this->getFixture('gym_october');
        $gym->qualify($gym->getQualification(), $this->getFixture('gym'));
        $refund = $this->getFixture('refund');
        $refund->qualify($refund->getQualification(), $this->getFixture('rule_refund'));
        self::getContainer()->get('doctrine.orm.entity_manager')->flush();

        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function recognised(string $query = 'month=2026-10'): array
    {
        $this->client->request('GET', '/api/finance/review/recognised?'.$query, [], [], $this->authHeaders());

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function control(array $body): array
    {
        $this->client->request('POST', '/api/finance/review/controls', [], [], array_merge([
            'CONTENT_TYPE' => 'application/json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR) ?? [];
    }

    private function stored(string $ref): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($ref)->getId());
    }

    /** @param list<array<string, mixed>> $transactions */
    private function refs(array $transactions): array
    {
        $byId = [];
        foreach (['carrefour_a', 'carrefour_b', 'carrefour_c', 'carrefour_september', 'gym_october', 'refund', 'orphan'] as $ref) {
            $byId[$this->id($ref)] = $ref;
        }

        return array_map(static fn (array $item): string => $byId[$item['id']] ?? $item['id'], $transactions);
    }

    public function testListingUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/review/recognised?month=2026-10');

        self::assertResponseStatusCodeSame(401);
    }

    public function testControllingUnauthenticatedReturns401(): void
    {
        $this->client->request('POST', '/api/finance/review/controls', [], [], ['CONTENT_TYPE' => 'application/json'], '{"transactionIds":[]}');

        self::assertResponseStatusCodeSame(401);
    }

    public function testABadMonthReturns400(): void
    {
        $this->login();

        foreach (['month=2026-13', 'month=octobre'] as $query) {
            $this->recognised($query);
            self::assertResponseStatusCodeSame(400, $query);
        }
    }

    public function testTheLinesOfTheMonthAreGroupedByOperationOldestFirst(): void
    {
        $this->login();

        $data = $this->recognised();

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10', $data['month']);
        self::assertSame(6, $data['total']);
        self::assertSame(
            ['series:Salle de sport', 'rule:CARREFOUR', 'series:Règle ou série supprimée', 'rule:REMBOURSEMENT'],
            array_map(static fn (array $group): string => $group['operationType'].':'.$group['label'], $data['groups']),
        );

        $carrefour = $data['groups'][1];
        self::assertSame($this->id('rule_carrefour'), $carrefour['operationId']);
        self::assertSame(3, $carrefour['count']);
        self::assertSame(-8000, $carrefour['totalCents']);
        // The line judged already is not waiting for a satisfaction.
        self::assertSame(2, $carrefour['unratedCount']);
        self::assertSame(['carrefour_a', 'carrefour_b', 'carrefour_c'], $this->refs($carrefour['transactions']));
        self::assertTrue($carrefour['transactions'][0]['recognised']);
        self::assertSame('rule', $carrefour['transactions'][0]['qualifiedBy']);
        self::assertSame($this->id('gym'), $data['groups'][0]['operationId']);
    }

    public function testWhatIsCheckedByHandSeptemberOrSomeoneElsesStaysOut(): void
    {
        $this->login();

        $ids = [];
        foreach ($this->recognised()['groups'] as $group) {
            $ids = [...$ids, ...array_column($group['transactions'], 'id')];
        }

        foreach (['by_hand', 'carrefour_checked', 'carrefour_september', 'stranger_line'] as $ref) {
            self::assertNotContains($this->id($ref), $ids, $ref);
        }
        self::assertSame(['carrefour_september'], $this->refs($this->recognised('month=2026-09')['groups'][0]['transactions']));
    }

    public function testALineWhoseOperationWasDeletedStaysToCheckInAGroupOfItsOwn(): void
    {
        $this->login();

        $group = $this->recognised()['groups'][2];

        self::assertNull($group['operationId']);
        self::assertSame(['orphan'], $this->refs($group['transactions']));
    }

    public function testControllingAGroupKeepsItsLinesAndTheyDoNotComeBack(): void
    {
        $this->login();

        $result = $this->control(['transactionIds' => [$this->id('carrefour_a'), $this->id('carrefour_b'), $this->id('carrefour_c')]]);

        self::assertResponseIsSuccessful();
        self::assertSame(['controlledCount' => 3, 'judgedCount' => 0], $result);
        foreach (['carrefour_a', 'carrefour_b', 'carrefour_c'] as $ref) {
            self::assertNotNull($this->stored($ref)->getControlledAt(), $ref);
        }
        // A plain check gives no satisfaction.
        self::assertSame(RetrospectVerdict::Unrated, $this->stored('carrefour_a')->getRetrospect());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);

        $data = $this->recognised();
        self::assertSame(3, $data['total']);
        self::assertSame(['series', 'series', 'rule'], array_column($data['groups'], 'operationType'));
        self::assertNotContains('CARREFOUR', array_column($data['groups'], 'label'));
        $this->client->request('GET', '/api/finance/review/queue?month=2026-10', [], [], $this->authHeaders());
        $queued = array_column(json_decode($this->client->getResponse()->getContent(), true)['items'], 'id');
        self::assertNotContains($this->id('carrefour_a'), $queued);
        self::assertContains($this->id('gym_october'), $queued);
    }

    public function testKeepAllJudgesTheSpendsNotJudgedYetOnlyAndChecksTheGroup(): void
    {
        $this->login();

        $result = $this->control([
            'transactionIds' => [$this->id('carrefour_a'), $this->id('carrefour_b'), $this->id('carrefour_c')],
            'satisfaction' => 'keep',
        ]);

        self::assertResponseIsSuccessful();
        self::assertSame(['controlledCount' => 3, 'judgedCount' => 2], $result);
        self::assertSame(RetrospectVerdict::Keep, $this->stored('carrefour_a')->getRetrospect());
        // The verdict already given is not overridden.
        self::assertSame(RetrospectVerdict::Avoidable, $this->stored('carrefour_b')->getRetrospect());
        self::assertSame(RetrospectVerdict::Keep, $this->stored('carrefour_c')->getRetrospect());
        foreach (['carrefour_a', 'carrefour_b', 'carrefour_c'] as $ref) {
            self::assertNotNull($this->stored($ref)->getControlledAt(), $ref);
        }
        $this->assertMercureUpdatePublished('/transactions/');
    }

    public function testKeepAllNeverJudgesAnIncome(): void
    {
        $this->login();

        $result = $this->control(['transactionIds' => [$this->id('refund'), $this->id('gym_october')], 'satisfaction' => 'keep']);

        self::assertResponseIsSuccessful();
        self::assertSame(['controlledCount' => 2, 'judgedCount' => 1], $result);
        self::assertSame(RetrospectVerdict::Unrated, $this->stored('refund')->getRetrospect());
        self::assertNotNull($this->stored('refund')->getControlledAt());
        self::assertSame(RetrospectVerdict::Keep, $this->stored('gym_october')->getRetrospect());
    }

    public function testABadBodyReturns400AndWritesNothing(): void
    {
        $this->login();

        foreach ([
            [],
            ['transactionIds' => []],
            ['transactionIds' => 'abc'],
            ['transactionIds' => ['not-a-ulid']],
            ['transactionIds' => [$this->id('carrefour_a')], 'satisfaction' => 'avoidable'],
            ['transactionIds' => array_fill(0, 201, $this->id('carrefour_a'))],
        ] as $body) {
            $this->control($body);
            self::assertResponseStatusCodeSame(400, json_encode($body));
        }
        self::assertNull($this->stored('carrefour_a')->getControlledAt());
    }

    public function testALineNotRecognisedByAnOperationRefusesTheWholeCallAndWritesNothing(): void
    {
        $this->login();

        $this->control(['transactionIds' => [$this->id('carrefour_a'), $this->id('by_hand')]]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->stored('carrefour_a')->getControlledAt());
        self::assertSame(CategorySource::Manual, $this->stored('by_hand')->getCategorySource());
    }

    public function testSomeoneElsesLineReturns404(): void
    {
        $this->login();

        $this->control(['transactionIds' => [$this->id('stranger_line')]]);

        self::assertResponseStatusCodeSame(404);
        self::assertNull($this->stored('stranger_line')->getControlledAt());
    }

    public function testCorrectingALinePutsItByHandAndALaterChangeOfTheOperationLeavesItAlone(): void
    {
        $this->login();

        $this->client->request('PATCH', '/api/transactions/'.$this->id('carrefour_a'), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['category' => '/api/categories/'.$this->id('subscriptions')], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame(CategorySource::Manual, $this->stored('carrefour_a')->getCategorySource());
        $data = $this->recognised();
        $carrefour = array_values(array_filter($data['groups'], static fn (array $group): bool => 'CARREFOUR' === $group['label']))[0];
        self::assertSame(['carrefour_b', 'carrefour_c'], $this->refs($carrefour['transactions']));
    }
}
