<?php

namespace Maggie\Finance\Tests\Mcp;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Maggie\Finance\Entity\CategorizationRule;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Mcp\Tool\ManageTransactionsTool;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class TransactionsToSortToolTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    private function id(string $ref): string
    {
        return (string) $this->getFixture($ref)->getId();
    }

    /** @return array<string, mixed> */
    private function call(mixed ...$arguments): array
    {
        $tool = self::getContainer()->get(ManageTransactionsTool::class);

        return json_decode($tool(...$arguments), true, 512, JSON_THROW_ON_ERROR);
    }

    private function stored(string $ref): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($ref)->getId());
    }

    public function testWithoutAUserBothActionsAnswerWithAnError(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');

        self::assertArrayHasKey('error', $this->call('to_sort'));
        self::assertArrayHasKey('error', $this->call('sort', transactionId: $this->id('carrefour_new'), categoryId: $this->id('food')));
    }

    public function testToSortListsThePileWithTheSuggestionAndItsReason(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('to_sort');

        self::assertSame(9, $data['total']);
        self::assertSame($this->id('carrefour_new'), $data['transactions'][0]['id']);
        self::assertSame('Règle « contient CARREFOUR »', $data['transactions'][0]['suggestion']['reason']);
        self::assertSame('rule', $data['transactions'][0]['suggestion']['source']);
        self::assertSame('Dépenses courantes › Alimentation › Courses', $data['transactions'][0]['suggestion']['categoryPath']);
        self::assertCount(2, $this->call('to_sort', limit: 2)['transactions']);
    }

    public function testSortFilesTheLineByHandAndPublishes(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('sort', transactionId: $this->id('carrefour_new'), categoryId: $this->id('food'));

        self::assertTrue($data['success']);
        self::assertSame(8, $data['remaining']);
        $stored = $this->stored('carrefour_new');
        self::assertSame($this->id('food'), (string) $stored->getCategory()->getId());
        self::assertSame(CategorySource::Manual, $stored->getCategorySource());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testSortAlwaysForMerchantWritesTheRuleAndFilesTheOthers(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('sort', transactionId: $this->id('bakery_one'), categoryId: $this->id('food'), alwaysForMerchant: true);

        self::assertTrue($data['success']);
        self::assertTrue($data['ruleCreated']);
        self::assertSame('BOULANGERIE PAUL', $data['rule']['labelPattern']);
        self::assertSame(1, $data['alsoSorted']);
        self::assertSame($this->id('food'), (string) $this->stored('bakery_two')->getCategory()->getId());

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        self::assertCount(2, $em->getRepository(CategorizationRule::class)->findAll());
        $this->assertElasticsearchIndexDispatched(CategorizationRule::class);
    }

    public function testSortMarksAnInternalTransfer(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('sort', transactionId: $this->id('transfer_out'), transferKind: 'internal', counterpartId: $this->id('transfer_in'));

        self::assertTrue($data['success']);
        self::assertSame(7, $data['remaining']);
        self::assertSame(TransferKind::Internal, $this->stored('transfer_out')->getTransferKind());
        self::assertSame(TransferKind::Internal, $this->stored('transfer_in')->getTransferKind());
        $this->assertMercureUpdatePublished('/transactions/');
    }

    public function testSortRefusesWhatItCannotDoAndWritesNothing(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();
        $line = $this->id('carrefour_new');

        foreach ([
            'no transaction' => ['categoryId' => $this->id('food')],
            'neither' => ['transactionId' => $line],
            'both' => ['transactionId' => $line, 'categoryId' => $this->id('food'), 'transferKind' => 'internal'],
            'another marking' => ['transactionId' => $line, 'transferKind' => 'rejected'],
            'always on a transfer' => ['transactionId' => $line, 'transferKind' => 'internal', 'alwaysForMerchant' => true],
            'unknown category' => ['transactionId' => $line, 'categoryId' => '01ARZ3NDEKTSV4RRFFQ69G5FAV'],
            'category of someone else' => ['transactionId' => $line, 'categoryId' => $this->id('stranger_category')],
            'transaction of someone else' => ['transactionId' => $this->id('stranger_line'), 'categoryId' => $this->id('food')],
            'unknown transaction' => ['transactionId' => 'nope', 'categoryId' => $this->id('food')],
            'already categorized by hand' => ['transactionId' => $this->id('already_sorted'), 'categoryId' => $this->id('food')],
            'already a transfer' => ['transactionId' => $this->id('paired_transfer'), 'categoryId' => $this->id('food')],
            'counterpart of someone else' => ['transactionId' => $line, 'transferKind' => 'internal', 'counterpartId' => $this->id('stranger_line')],
        ] as $case => $arguments) {
            self::assertArrayHasKey('error', $this->call('sort', ...$arguments), $case);
        }

        self::assertNull($this->stored('carrefour_new')->getCategory());
        self::assertSame($this->id('leisure'), (string) $this->stored('already_sorted')->getCategory()->getId());
        self::assertNull($this->stored('stranger_line')->getCategory());
    }

    public function testToSortWeighsThePileAndFlagsWhatCanBeJudged(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('to_sort');

        self::assertGreaterThan(0, $data['totalCents']);
        $byId = array_column($data['transactions'], null, 'id');
        self::assertTrue($byId[$this->id('carrefour_new')]['judgeable']);
        self::assertFalse($byId[$this->id('transfer_in')]['judgeable']);
        self::assertSame('unrated', $byId[$this->id('carrefour_new')]['retrospect']);
    }

    public function testUpdateGivesASatisfactionToASpend(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('update', transactionId: $this->id('carrefour_new'), retrospect: 'avoidable');

        self::assertTrue($data['success']);
        self::assertSame('avoidable', $data['transaction']['retrospect']);
        self::assertSame(RetrospectVerdict::Avoidable, $this->stored('carrefour_new')->getRetrospect());
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }

    public function testUpdateRefusesASatisfactionOnAnIncomeAndOnATransfer(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        foreach (['recognised_income', 'paired_transfer'] as $ref) {
            $data = $this->call('update', transactionId: $this->id($ref), retrospect: 'keep');

            self::assertArrayHasKey('error', $data, $ref);
            self::assertSame(RetrospectVerdict::Unrated, $this->stored($ref)->getRetrospect(), $ref);
        }
    }

    public function testASpendMarkedAsATransferInTheSameUpdateCannotBeJudged(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        $data = $this->call('update', transactionId: $this->id('carrefour_new'), retrospect: 'keep', transferKind: 'internal');

        self::assertArrayHasKey('error', $data);
        self::assertSame(RetrospectVerdict::Unrated, $this->stored('carrefour_new')->getRetrospect());
    }

    public function testUpdateRefusesAnUnknownSatisfaction(): void
    {
        $this->loadFixtures('transactions_to_sort.yaml');
        $this->loginFixtureUser();

        self::assertArrayHasKey('error', $this->call('update', transactionId: $this->id('carrefour_new'), retrospect: 'great'));
    }
}
