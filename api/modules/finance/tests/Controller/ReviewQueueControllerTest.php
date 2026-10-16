<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

class ReviewQueueControllerTest extends WebTestCase
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
    private function queue(string $query = 'month=2026-10'): array
    {
        $this->client->request('GET', '/api/finance/review/queue?'.$query, [], [], $this->authHeaders());

        return json_decode($this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $ref, array $body): void
    {
        $this->client->request('PATCH', '/api/transactions/'.$this->id($ref), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    private function retrospectOf(string $ref): RetrospectVerdict
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();

        return $em->find(Transaction::class, $this->getFixture($ref)->getId())->getRetrospect();
    }

    /** @param list<array<string, mixed>> $items */
    private function refs(array $items): array
    {
        $byId = [];
        foreach (['carrefour_new', 'unknown', 'netflix', 'bakery_one', 'carrefour_old', 'transfer_out', 'transfer_in', 'bakery_two', 'emptied_by_hand', 'recognised_by_rule', 'recognised_by_series', 'recognised_income', 'recognised_last_month'] as $ref) {
            $byId[$this->id($ref)] = $ref;
        }

        return array_map(fn (array $item): string => $byId[$item['id']] ?? $item['id'], $items);
    }

    public function testUnauthenticatedReturns401(): void
    {
        $this->client->request('GET', '/api/finance/review/queue?month=2026-10');

        self::assertResponseStatusCodeSame(401);
    }

    public function testABadMonthOrLimitReturns400(): void
    {
        $this->login();

        foreach (['month=2026-13', 'month=octobre', 'month=2026-10&limit=0', 'month=2026-10&limit=abc'] as $query) {
            $this->queue($query);
            self::assertResponseStatusCodeSame(400, $query);
        }
    }

    public function testTheUnrecognisedComeFirstThenTheRecognisedOfTheMonthEachOldestFirst(): void
    {
        $this->login();

        $data = $this->queue();

        self::assertResponseIsSuccessful();
        self::assertSame('2026-10', $data['month']);
        self::assertSame(
            [
                // The pile, oldest first — the line emptied by hand included.
                'emptied_by_hand', 'bakery_two', 'transfer_in', 'transfer_out', 'carrefour_old', 'bakery_one', 'netflix', 'unknown', 'carrefour_new',
                // Then what an operation recognised in October: not September's, nobody else's, no transfer.
                'recognised_by_series', 'recognised_by_rule', 'recognised_income',
            ],
            $this->refs($data['items']),
        );
        self::assertSame(
            [false, false, false, false, false, false, false, false, false, true, true, true],
            array_column($data['items'], 'recognised'),
        );
        self::assertSame(9, $data['unrecognisedCount']);
        self::assertSame(3, $data['recognisedCount']);
    }

    public function testThePileIsWeighedWithoutTheSignOfItsAmounts(): void
    {
        $this->login();

        $data = $this->queue();

        // 4599 + 1200 (netflix) + the rest of the pile, whatever the direction.
        $expected = array_sum(array_map(
            static fn (array $item): int => abs($item['amountCents']),
            array_filter($data['items'], static fn (array $item): bool => !$item['recognised']),
        ));
        self::assertSame($expected, $data['unrecognisedCents']);
        self::assertGreaterThan(0, $data['unrecognisedCents']);
    }

    public function testARecognisedLineNamesWhoRecognisedItAndTheCategoryItGot(): void
    {
        $this->login();

        $data = $this->queue();

        $series = $data['items'][9];
        self::assertSame('series', $series['qualifiedBy']);
        self::assertSame($this->id('streaming'), $series['categoryId']);
        self::assertSame('TV & streaming', $series['categoryPath']);
        self::assertSame('rule', $data['items'][10]['qualifiedBy']);
        self::assertNull($series['suggestion']);
        // A line of the pile has a suggestion slot too.
        self::assertArrayHasKey('suggestion', $data['items'][0]);
    }

    public function testOnlyASpendCanBeJudged(): void
    {
        $this->login();

        $items = array_column($this->queue()['items'], null, 'id');

        self::assertTrue($items[$this->id('carrefour_new')]['judgeable']);
        self::assertTrue($items[$this->id('recognised_by_rule')]['judgeable']);
        self::assertFalse($items[$this->id('recognised_income')]['judgeable']);
        self::assertFalse($items[$this->id('transfer_in')]['judgeable']);
        self::assertSame('unrated', $items[$this->id('carrefour_new')]['retrospect']);
    }

    public function testTheLimitCutsTheQueueAfterThePile(): void
    {
        $this->login();

        $data = $this->queue('month=2026-10&limit=10');

        self::assertCount(10, $data['items']);
        self::assertSame(1, $data['recognisedCount']);
        self::assertSame(9, $data['unrecognisedCount']);
    }

    public function testAnotherMonthHoldsNoRecognisedLineOfOctober(): void
    {
        $this->login();

        $data = $this->queue('month=2026-09');

        self::assertSame(['recognised_last_month'], array_slice($this->refs($data['items']), 9));
    }

    public function testJudgingASpendStoresTheSatisfactionAndPublishes(): void
    {
        $this->login();

        $this->patch('carrefour_new', ['retrospect' => 'avoidable']);

        self::assertResponseIsSuccessful();
        self::assertSame(RetrospectVerdict::Avoidable, $this->retrospectOf('carrefour_new'));
        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);

        $this->patch('carrefour_new', ['retrospect' => 'keep']);
        self::assertResponseIsSuccessful();
        self::assertSame(RetrospectVerdict::Keep, $this->retrospectOf('carrefour_new'));
    }

    public function testJudgingAnIncomeOrATransferIsRefusedAndWritesNothing(): void
    {
        $this->login();

        foreach (['recognised_income', 'paired_transfer', 'rejected_debit'] as $ref) {
            $this->patch($ref, ['retrospect' => 'keep']);

            self::assertResponseStatusCodeSame(400, $ref);
            self::assertSame(RetrospectVerdict::Unrated, $this->retrospectOf($ref), $ref);
        }
    }

    public function testResendingTheUnratedVerdictOnAnIncomeIsNotAJudgement(): void
    {
        $this->login();

        $this->patch('recognised_income', ['retrospect' => 'unrated', 'note' => 'un remboursement']);

        self::assertResponseIsSuccessful();
    }
}
