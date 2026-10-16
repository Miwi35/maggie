<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\MatchType;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class TransactionsToSortControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function login(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function call(string $method, string $path, ?array $body = null): array
    {
        $this->client->request(
            $method,
            $path,
            [],
            [],
            $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'],
            null === $body ? '' : json_encode($body, JSON_THROW_ON_ERROR),
        );

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @return array<string, mixed> */
    private function sort(string $ref, array $body): array
    {
        return $this->call('POST', sprintf('/api/finance/transactions/%s/sort', $this->id($ref)), $body);
    }

    private function stored(string $ref): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($ref)->getId());
    }

    public function testUnauthenticatedReturns401OnBothRoutes(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $id = $this->id('carrefour_new');

        $this->client->request('GET', '/api/finance/transactions/to-sort');
        self::assertResponseStatusCodeSame(401);

        $this->client->request('POST', "/api/finance/transactions/$id/sort");
        self::assertResponseStatusCodeSame(401);
    }

    public function testThePileHoldsOnlyTheLinesWithNoCategoryAndNoMarking(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/to-sort');

        self::assertResponseIsSuccessful();
        $ids = array_column($data['transactions'], 'id');
        // Newest first; the sorted, paired, rejected and foreign lines are not in it.
        self::assertSame(
            ['carrefour_new', 'unknown', 'netflix', 'bakery_one', 'carrefour_old', 'transfer_out', 'transfer_in', 'bakery_two', 'emptied_by_hand'],
            array_map(fn (string $id): string => $this->refOf($id), $ids),
        );
        self::assertSame(9, $data['total']);
    }

    private function refOf(string $id): string
    {
        foreach (['carrefour_new', 'unknown', 'netflix', 'bakery_one', 'carrefour_old', 'transfer_out', 'transfer_in', 'bakery_two', 'emptied_by_hand'] as $ref) {
            if ($this->id($ref) === $id) {
                return $ref;
            }
        }

        return $id;
    }

    public function testARuleSuggestionCarriesItsCategoryPathAndItsReason(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/to-sort');

        $line = $data['transactions'][0];
        self::assertSame('CARREFOUR MARKET 1234', $line['label']);
        self::assertSame(-4599, $line['amountCents']);
        self::assertSame('2026-10-12', $line['bookedAt']);
        self::assertSame('Courant', $line['accountName']);
        self::assertSame([
            'categoryId' => $this->id('food'),
            'categoryPath' => 'Dépenses courantes › Alimentation › Courses',
            'source' => 'rule',
            'reason' => 'Règle « contient CARREFOUR »',
        ], $line['suggestion']);
    }

    public function testAKnownMerchantIsSuggestedWithItsReasonAndAnUnknownOneWithNothing(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/to-sort');
        $byLabel = array_column($data['transactions'], null, 'label');

        $netflix = $byLabel['PRELEVEMENT NETFLIX.COM 09/10']['suggestion'];
        self::assertSame($this->id('streaming'), $netflix['categoryId']);
        self::assertSame('merchant', $netflix['source']);
        self::assertStringStartsWith('Marchand connu : Netflix', $netflix['reason']);

        self::assertNull($byLabel['DUPONT MARTIN']['suggestion']);
        // The dictionary names a heading the owner does not have: no suggestion rather than a wrong one.
        self::assertNull($byLabel['BOULANGERIE PAUL 4521 PARIS']['suggestion']);
    }

    public function testTheLimitNarrowsThePageButNotTheTotal(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/to-sort?limit=2');

        self::assertCount(2, $data['transactions']);
        self::assertSame(9, $data['total']);

        $this->call('GET', '/api/finance/transactions/to-sort?limit=abc');
        self::assertResponseStatusCodeSame(400);
    }

    public function testValidatingTheSuggestionFilesTheLineByHand(): void
    {
        $this->login();

        $data = $this->sort('carrefour_new', ['categoryId' => $this->id('food')]);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['success']);
        self::assertNull($data['rule']);
        self::assertSame(0, $data['alsoSorted']);
        self::assertSame(8, $data['remaining']);

        $stored = $this->stored('carrefour_new');
        self::assertSame($this->id('food'), (string) $stored->getCategory()->getId());
        self::assertSame(CategorySource::Manual, $stored->getCategorySource());
        // The other Carrefour line is left alone: no rule was asked for.
        self::assertNull($this->stored('carrefour_old')->getCategory());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAnotherCategoryIsAccepted(): void
    {
        $this->login();

        $this->sort('carrefour_new', ['categoryId' => $this->id('leisure')]);

        self::assertResponseIsSuccessful();
        self::assertSame($this->id('leisure'), (string) $this->stored('carrefour_new')->getCategory()->getId());
    }

    public function testAlwaysForThisMerchantWritesTheRuleAndFilesTheOtherLines(): void
    {
        $this->login();

        $data = $this->sort('bakery_one', ['categoryId' => $this->id('food'), 'alwaysForMerchant' => true]);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['ruleCreated']);
        self::assertSame('BOULANGERIE PAUL', $data['rule']['labelPattern']);
        self::assertSame(1, $data['alsoSorted']);
        self::assertSame(7, $data['remaining']);

        self::assertSame(CategorySource::Manual, $this->stored('bakery_one')->getCategorySource());
        $other = $this->stored('bakery_two');
        self::assertSame($this->id('food'), (string) $other->getCategory()->getId());
        self::assertSame(CategorySource::Rule, $other->getCategorySource());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        /** @var RecurringOperation $rule */
        $rule = $em->find(RecurringOperation::class, $data['rule']['id']);
        self::assertFalse($rule->hasRecurrence());
        self::assertSame(MatchType::Contains, $rule->getMatchType());
        self::assertSame($this->id('food'), (string) $rule->getCategory()->getId());
        self::assertSame($this->id('test_user'), (string) $rule->getUser()->getId());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
        $this->assertElasticsearchIndexDispatched(RecurringOperation::class);
    }

    public function testAlwaysWithARuleThatAlreadySaysItOnlyAppliesIt(): void
    {
        $this->login();

        $data = $this->sort('carrefour_new', ['categoryId' => $this->id('food'), 'alwaysForMerchant' => true]);

        self::assertResponseIsSuccessful();
        self::assertFalse($data['ruleCreated']);
        self::assertSame($this->id('rule_carrefour'), $data['rule']['id']);
        self::assertSame(1, $data['alsoSorted']);
        self::assertSame($this->id('food'), (string) $this->stored('carrefour_old')->getCategory()->getId());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(1, $em->getRepository(RecurringOperation::class)->findAll());
    }

    public function testAlwaysForAnotherCategoryOutranksTheRuleThatClaimedTheLine(): void
    {
        $this->login();

        $data = $this->sort('carrefour_new', ['categoryId' => $this->id('leisure'), 'alwaysForMerchant' => true]);

        self::assertResponseIsSuccessful();
        self::assertTrue($data['ruleCreated']);
        self::assertSame($this->id('leisure'), (string) $this->stored('carrefour_old')->getCategory()->getId());
    }

    public function testAnInternalTransferIsMarkedByHandWithItsCounterpart(): void
    {
        $this->login();

        $data = $this->sort('transfer_out', ['internalTransfer' => ['counterpartId' => $this->id('transfer_in')]]);

        self::assertResponseIsSuccessful();
        // Both legs leave the pile.
        self::assertSame(7, $data['remaining']);

        $out = $this->stored('transfer_out');
        self::assertSame(TransferKind::Internal, $out->getTransferKind());
        self::assertSame(TransferSource::Manual, $out->getTransferSource());
        self::assertSame($this->id('transfer_in'), (string) $out->getCounterpart()->getId());
        self::assertSame(TransferKind::Internal, $this->stored('transfer_in')->getTransferKind());

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testAnInternalTransferWithoutCounterpartMarksASingleLeg(): void
    {
        $this->login();

        $this->sort('unknown', ['internalTransfer' => []]);

        self::assertResponseIsSuccessful();
        $stored = $this->stored('unknown');
        self::assertSame(TransferKind::Internal, $stored->getTransferKind());
        self::assertNull($stored->getCounterpart());
    }

    public function testAnotherUsersOrUnknownTransactionIsReportedMissing(): void
    {
        $this->login();

        foreach ([$this->id('stranger_line'), '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid'] as $id) {
            $this->call('POST', "/api/finance/transactions/$id/sort", ['categoryId' => $this->id('food')]);
            self::assertResponseStatusCodeSame(404, $id);
        }

        self::assertNull($this->stored('stranger_line')->getCategory());
    }

    public function testABadBodyReturns400AndWritesNothing(): void
    {
        $this->login();
        $id = $this->id('carrefour_new');

        foreach ([
            'neither' => [],
            'both' => ['categoryId' => $this->id('food'), 'internalTransfer' => []],
            'unknown category' => ['categoryId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV'],
            'not an id' => ['categoryId' => 'nope'],
            'category of someone else' => ['categoryId' => $this->id('stranger_category')],
            'category not a string' => ['categoryId' => 12],
            'always not a boolean' => ['categoryId' => $this->id('food'), 'alwaysForMerchant' => 'yes'],
            'always on a transfer' => ['internalTransfer' => [], 'alwaysForMerchant' => true],
            'transfer not an object' => ['internalTransfer' => 'oui'],
            'counterpart not an id' => ['internalTransfer' => ['counterpartId' => 'nope']],
            'counterpart of someone else' => ['internalTransfer' => ['counterpartId' => $this->id('stranger_line')]],
            'counterpart is itself' => ['internalTransfer' => ['counterpartId' => $id]],
        ] as $case => $body) {
            $this->sort('carrefour_new', $body);
            self::assertResponseStatusCodeSame(400, $case);
        }

        $this->client->request('POST', "/api/finance/transactions/$id/sort", [], [], $this->authHeaders() + ['CONTENT_TYPE' => 'application/json'], '{broken');
        self::assertResponseStatusCodeSame(400);

        $stored = $this->stored('carrefour_new');
        self::assertNull($stored->getCategory());
        self::assertSame(TransferKind::None, $stored->getTransferKind());
    }

    public function testALineAlreadyCategorizedByHandIsRefused(): void
    {
        $this->login();

        $data = $this->sort('already_sorted', ['categoryId' => $this->id('food')]);

        self::assertResponseStatusCodeSame(400);
        self::assertStringContainsString('not waiting to be sorted', $data['error']);
        self::assertSame($this->id('leisure'), (string) $this->stored('already_sorted')->getCategory()->getId());

        $this->sort('paired_transfer', ['categoryId' => $this->id('food')]);
        self::assertResponseStatusCodeSame(400);
    }

    public function testAlwaysOnALabelWithNoMerchantIsRefusedBeforeAnythingIsWritten(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $line = $em->find(Transaction::class, $this->getFixture('unknown')->getId());
        $line->setLabel('');
        $em->flush();

        $this->sort('unknown', ['categoryId' => $this->id('food'), 'alwaysForMerchant' => true]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->stored('unknown')->getCategory());
    }

    public function testThePileCanBeWalkedOldestFirstAndTellsWhatItWeighs(): void
    {
        $this->login();

        $data = $this->call('GET', '/api/finance/transactions/to-sort?order=oldest');

        self::assertResponseIsSuccessful();
        self::assertSame('emptied_by_hand', $this->refOf($data['transactions'][0]['id']));
        self::assertSame('carrefour_new', $this->refOf($data['transactions'][8]['id']));
        self::assertSame(
            array_sum(array_map(static fn (array $line): int => abs($line['amountCents']), $data['transactions'])),
            $data['totalCents'],
        );

        $this->call('GET', '/api/finance/transactions/to-sort?order=sideways');
        self::assertResponseStatusCodeSame(400);
    }

    public function testEachLineSaysWhetherASatisfactionCanBeGiven(): void
    {
        $this->login();

        $lines = array_column($this->call('GET', '/api/finance/transactions/to-sort')['transactions'], null, 'id');

        self::assertTrue($lines[$this->id('carrefour_new')]['judgeable']);
        self::assertFalse($lines[$this->id('transfer_in')]['judgeable']);
        self::assertSame('unrated', $lines[$this->id('carrefour_new')]['retrospect']);
    }
}
