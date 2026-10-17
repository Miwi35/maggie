<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/** `GET /api/finance/accounts/{id}/timeline`: an account over a month, day by day (MAG-422). */
final class AccountTimelineControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('account_timeline.yaml');
    }

    public function testWithoutATokenItIsRefused(): void
    {
        $this->client->request('GET', $this->path('checking'));

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnInvalidMonthIsABadRequest(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach (['2026-13', '2026-00', '2026-5', 'octobre', '2026-10-01', ''] as $month) {
            $this->client->request('GET', $this->path('checking').'?month='.$month, [], [], $this->authHeaders());
            self::assertResponseStatusCodeSame(400, $month);
        }
    }

    public function testAnotherUsersOrUnknownAccountIsReportedMissing(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        foreach ([(string) $this->account('stranger_account')->getId(), '01ARZ3NDEKTSV4RRFFQ69G5FAV', 'not-a-ulid'] as $id) {
            $this->client->request('GET', "/api/finance/accounts/$id/timeline?month=2026-10", [], [], $this->authHeaders());
            self::assertResponseStatusCodeSame(404, $id);
        }
    }

    public function testWithoutMonthItReadsTheCurrentOne(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        self::assertSame((new \DateTimeImmutable('today'))->format('Y-m'), $this->timeline('checking', null)['month']);
    }

    public function testDaysCarryTheRealStatusAndTheAccountOppositeATransfer(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $out = $this->line('checking', -50000, '2026-10-05', 'VIR LIVRET', TransactionStatus::Spent);
        $in = $this->line('savings', 50000, '2026-10-05', 'VIR DEPUIS COURANT', TransactionStatus::Spent);
        $out->markAsInternalTransfer($in, TransferSource::Auto);
        $this->line('checking', -4500, '2026-10-12', 'ASSURANCE', TransactionStatus::Planned);
        $this->line('checking', -1200, '2026-10-12', 'CINEMA', TransactionStatus::ToArbitrate);
        $this->line('checking', -3000, '2026-09-30', 'COURSES SEPTEMBRE', TransactionStatus::Spent);
        $this->flush();

        $timeline = $this->timeline('checking', '2026-10');

        self::assertSame('2026-10', $timeline['month']);
        self::assertSame('2026-09', $timeline['previousMonth']);
        self::assertSame('Courant', $timeline['account']['name']);
        self::assertSame(125000, $timeline['account']['balanceCents']);
        self::assertTrue($timeline['account']['isManual']);
        self::assertSame(['2026-10-12', '2026-10-05'], array_column($timeline['days'], 'date'));

        $statuses = array_column($timeline['days'][0]['transactions'], 'status', 'label');
        self::assertEqualsCanonicalizing(['ASSURANCE' => 'planned', 'CINEMA' => 'to_arbitrate'], $statuses);
        self::assertSame('planned', $statuses['ASSURANCE']);

        $transfer = $timeline['days'][1]['transactions'][0];
        self::assertSame((string) $out->getId(), $transfer['id']);
        self::assertSame('internal', $transfer['transferKind']);
        self::assertSame(['id' => (string) $this->account('savings')->getId(), 'name' => 'Livret'], $transfer['counterpartAccount']);
        self::assertSame(-50000, $transfer['amountCents']);
    }

    public function testTheOtherAccountsLinesAreNotMixedIn(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->line('savings', 700, '2026-10-02', 'INTERETS', TransactionStatus::Spent);
        $this->flush();

        self::assertSame([], $this->timeline('checking', '2026-10')['days']);
        self::assertCount(1, $this->timeline('savings', '2026-10')['days']);
    }

    public function testRejectedPaymentsStayInTheirOwnList(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $debit = $this->line('checking', -2000, '2026-10-03', 'PRLV REJETE', TransactionStatus::Spent);
        $credit = $this->line('checking', 2000, '2026-10-04', 'REJET PRLV', TransactionStatus::Spent);
        $debit->markAsRejection($credit, TransferSource::Auto);
        $this->flush();

        self::assertSame([], $this->timeline('checking', '2026-10')['days']);
    }

    public function testAMissedDueDateAppearsOnItsDay(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        // A monthly series that began 20 days ago: its only due date is past the 5 days of tolerance.
        $first = (new \DateTimeImmutable('-20 days'))->setTime(0, 0);
        $rent = (new RecurringOperation())
            ->setLabel('Loyer')
            ->setCategory($this->getFixture('housing'))
            ->setAccount($this->account('checking'))
            ->setPeriod(RecurrencePeriod::Monthly)
            ->setAnchorOn($first)
            ->setReferenceAmountCents(-90000)
            ->setUser($this->getFixture('test_user'));
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->persist($rent);
        $this->flush();

        $timeline = $this->timeline('checking', $first->format('Y-m'));

        self::assertSame($first->format('Y-m-d'), $timeline['days'][0]['date']);
        self::assertSame(
            [['recurringOperationId' => (string) $rent->getId(), 'label' => 'Loyer', 'expectedOn' => $first->format('Y-m-d'), 'amountCents' => -90000]],
            $timeline['days'][0]['missedOccurrences'],
        );
    }

    private function path(string $accountRef): string
    {
        return '/api/finance/accounts/'.$this->account($accountRef)->getId().'/timeline';
    }

    private function account(string $ref): Account
    {
        /** @var Account $account */
        $account = $this->getFixture($ref);

        return $account;
    }

    private function line(string $accountRef, int $cents, string $day, string $label, TransactionStatus $status): Transaction
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        /** @var User $user */
        $user = $this->getFixture('test_user');

        $transaction = (new Transaction())
            ->setUser($user)
            ->setAccount($this->account($accountRef))
            ->setAmountCents($cents)
            ->setCurrency('EUR')
            ->setBookedAt(new \DateTimeImmutable($day))
            ->setLabel($label)
            ->setStatus($status);
        $em->persist($transaction);

        return $transaction;
    }

    private function flush(): void
    {
        $this->flushWithoutTransactionEffects(self::getContainer()->get('doctrine.orm.entity_manager'));
    }

    /** @return array<string, mixed> */
    private function timeline(string $accountRef, ?string $month): array
    {
        $this->client->request('GET', $this->path($accountRef).(null === $month ? '' : '?month='.$month), [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);
    }
}
