<?php

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\ReviewProgress;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RetrospectVerdict;
use Maggie\Finance\Enum\ReviewStep;
use Maggie\Finance\Enum\TransactionStatus;
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

        foreach ([
            'month=2026-13', 'month=octobre', 'month=2026-10&limit=0', 'month=2026-10&limit=abc',
            'month=2026-10&period=semaine', 'month=2026-10&period=', 'month=2026-10&state=tout', 'month=2026-10&period[]=all',
        ] as $query) {
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
        // The count is the month's total, not the page's.
        self::assertSame(3, $data['recognisedCount']);
        self::assertSame(9, $data['unrecognisedCount']);
        self::assertSame(12, $data['total']);
    }

    public function testAnotherMonthHoldsNoRecognisedLineOfOctober(): void
    {
        $this->login();

        $data = $this->queue('month=2026-09');

        self::assertSame(['recognised_last_month'], array_slice($this->refs($data['items']), 9));
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    /** Adds unrecognised lines of the owner, one per day from $from, to go past the pile's old cap. */
    private function addUnrecognised(int $count, string $from): void
    {
        $em = $this->em();
        $user = $em->find(User::class, $this->getFixture('test_user')->getId());
        $account = $em->find(Account::class, $this->getFixture('checking')->getId());
        $day = new \DateTimeImmutable($from);
        for ($i = 0; $i < $count; ++$i) {
            $em->persist(
                (new Transaction())
                    ->setUser($user)
                    ->setAccount($account)
                    ->setAmountCents(-100)
                    ->setCurrency('EUR')
                    ->setBookedAt($day->modify(sprintf('+%d days', $i % 100)))
                    ->setLabel('LIGNE EN ATTENTE '.$i)
                    ->setStatus(TransactionStatus::Spent)
                    ->setIsExceptional(false),
            );
        }
        $em->flush();
        $em->clear();
    }

    private function completeReview(string $month, string $at): void
    {
        $em = $this->em();
        $user = $em->find(User::class, $this->getFixture('test_user')->getId());
        $em->persist(
            (new ReviewProgress())
                ->setUser($user)
                ->setMonth(new \DateTimeImmutable($month))
                ->setStep(ReviewStep::Done)
                ->setUpdatedAt(new \DateTimeImmutable($at)),
        );
        $em->flush();
        $em->clear();
    }

    public function testTheLegacyCallKeepsItsShapeAndAddsTheNewFields(): void
    {
        $this->login();

        $data = $this->queue();

        self::assertNull($data['period']);
        self::assertNull($data['sinceReviewDate']);
        self::assertSame(12, $data['total']);
        foreach ($data['items'] as $item) {
            foreach (['recognised', 'categoryId', 'categoryPath', 'qualifiedBy', 'qualifiedBySeriesId', 'qualifiedByRuleId', 'operationLabel'] as $key) {
                self::assertArrayHasKey($key, $item);
            }
        }
    }

    public function testTheWholePileIsReturnedPastTwoHundredLines(): void
    {
        $this->login();
        $this->addUnrecognised(250, '2026-01-01');

        $data = $this->queue('period=all&state=unrecognised&limit=1000');

        self::assertResponseIsSuccessful();
        self::assertSame(259, $data['unrecognisedCount']);
        self::assertSame(259, $data['total']);
        self::assertCount(259, $data['items']);
        self::assertSame('all', $data['period']);
        self::assertSame(array_fill(0, 259, false), array_column($data['items'], 'recognised'));
        $dates = array_column($data['items'], 'bookedAt');
        $sorted = $dates;
        sort($sorted);
        self::assertSame($sorted, $dates, 'oldest first');
        // Legacy: the default limit still holds.
        self::assertCount(100, $this->queue()['items']);
    }

    public function testALimitAboveTheMaximumIsClampedNotRefused(): void
    {
        $this->login();

        $data = $this->queue('period=all&limit=5000');

        self::assertResponseIsSuccessful();
        self::assertCount(13, $data['items'], 'the whole queue, September\'s recognised line included');
    }

    public function testThePeriodMonthBoundsBothGroups(): void
    {
        $this->login();
        $this->addUnrecognised(1, '2026-03-01');

        $legacy = $this->queue();
        $month = $this->queue('period=month&month=2026-10');

        self::assertSame(10, $legacy['unrecognisedCount'], 'legacy: the old line still waits');
        self::assertSame(9, $month['unrecognisedCount'], 'the old line is out of the month');
        self::assertSame(3, $month['recognisedCount']);
        self::assertSame(12, $month['total']);
        self::assertSame('month', $month['period']);
        self::assertNull($month['sinceReviewDate']);
        self::assertNotContains('LIGNE EN ATTENTE 0', array_column($month['items'], 'label'));

        $september = $this->queue('period=month&month=2026-09');
        self::assertSame(['recognised_last_month'], $this->refs($september['items']));
        self::assertSame(0, $september['unrecognisedCount']);
        self::assertSame(0, $september['unrecognisedCents']);
    }

    public function testSinceReviewWithoutACompletedReviewHasNoLowerBound(): void
    {
        $this->login();
        $this->addUnrecognised(1, '2026-03-01');

        // A review under way is not a completed one.
        $em = $this->em();
        $em->persist(
            (new ReviewProgress())
                ->setUser($em->find(User::class, $this->getFixture('test_user')->getId()))
                ->setMonth(new \DateTimeImmutable('2026-09-01'))
                ->setStep(ReviewStep::Balance)
                ->setUpdatedAt(new \DateTimeImmutable('2026-10-06 10:00:00')),
        );
        $em->flush();
        $em->clear();

        $data = $this->queue('period=since_review&month=2026-10');

        self::assertNull($data['sinceReviewDate']);
        self::assertSame(10, $data['unrecognisedCount']);
        self::assertSame(4, $data['recognisedCount'], 'September\'s recognised line is in too');
    }

    public function testSinceReviewKeepsWhatCameStrictlyAfterTheLastCompletedReview(): void
    {
        $this->login();
        $this->addUnrecognised(1, '2026-03-01');
        $this->completeReview('2026-08-01', '2026-09-25 18:30:00');
        $this->completeReview('2026-09-01', '2026-10-05 09:00:00');

        $data = $this->queue('period=since_review&month=2026-10');

        self::assertSame('2026-10-05', $data['sinceReviewDate']);
        self::assertSame('since_review', $data['period']);
        // Strictly after the 5th: the lines of the 5th (carrefour_old, recognised_by_rule) are out.
        self::assertSame(
            ['bakery_one', 'netflix', 'unknown', 'carrefour_new', 'recognised_income'],
            $this->refs($data['items']),
        );
        self::assertSame(4, $data['unrecognisedCount']);
        self::assertSame(1, $data['recognisedCount']);
        self::assertSame(5, $data['total']);
    }

    public function testTheStateKeepsOneGroupWhileTheCountsStayThoseOfThePeriod(): void
    {
        $this->login();

        $recognised = $this->queue('period=month&month=2026-10&state=recognised');
        $unrecognised = $this->queue('period=month&month=2026-10&state=unrecognised');

        self::assertSame(['recognised_by_series', 'recognised_by_rule', 'recognised_income'], $this->refs($recognised['items']));
        self::assertSame(3, $recognised['total']);
        self::assertSame(9, $recognised['unrecognisedCount']);
        self::assertSame(3, $recognised['recognisedCount']);
        self::assertGreaterThan(0, $recognised['unrecognisedCents']);

        self::assertCount(9, $unrecognised['items']);
        self::assertSame([false], array_unique(array_column($unrecognised['items'], 'recognised')));
        self::assertSame(9, $unrecognised['total']);
        self::assertSame(3, $unrecognised['recognisedCount']);
        self::assertSame($recognised['unrecognisedCents'], $unrecognised['unrecognisedCents']);
    }

    public function testTheLimitTruncatesTheStateInTheOrderOfTheList(): void
    {
        $this->login();

        $data = $this->queue('period=month&month=2026-10&limit=2');

        self::assertSame(['emptied_by_hand', 'bakery_two'], $this->refs($data['items']));
        self::assertSame(12, $data['total'], 'total is what matches the state, not the page');
    }

    public function testARecognisedLineNamesTheOperationThatRecognisedIt(): void
    {
        $this->login();
        $em = $this->em();
        $rule = $em->find(RecurringOperation::class, $this->getFixture('rule_carrefour')->getId());
        $line = $em->find(Transaction::class, $this->getFixture('recognised_by_rule')->getId());
        $property = new \ReflectionProperty(Transaction::class, 'qualifiedByRule');
        $property->setValue($line, $rule);
        $em->flush();
        $em->clear();

        $items = array_column($this->queue('period=month&month=2026-10')['items'], null, 'id');

        self::assertSame('CARREFOUR', $items[$this->id('recognised_by_rule')]['operationLabel']);
        self::assertSame($this->id('rule_carrefour'), $items[$this->id('recognised_by_rule')]['qualifiedByRuleId']);
        // The operation of the series line was deleted: no label.
        self::assertNull($items[$this->id('recognised_by_series')]['operationLabel']);
        self::assertNull($items[$this->id('carrefour_new')]['operationLabel']);
        self::assertArrayHasKey('suggestion', $items[$this->id('carrefour_new')]);
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
