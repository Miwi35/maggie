<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Repository\TransactionRepository;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\DetectRejections;
use Maggie\Notification\Entity\Notification;
use Maggie\Notification\Enum\NotificationType;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * A rejected payment is a payment that did not happen: its debit and the
 * credit that cancels it leave every figure, and neither is ever taken for a
 * transfer between two of the owner's accounts (MAG-350).
 */
class DetectRejectionsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private DetectRejections $detect;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->detect = self::getContainer()->get(DetectRejections::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('rejections.yaml');
    }

    private function user(string $ref = 'test_user'): User
    {
        /** @var User $user */
        $user = $this->getFixture($ref);

        return $user;
    }

    private function tx(string $ref): Transaction
    {
        /** @var Transaction $transaction */
        $transaction = $this->getFixture($ref);

        return $transaction;
    }

    private function add(
        int $amountCents,
        string $bookedAt,
        string $label,
        string $accountRef = 'checking',
        TransactionStatus $status = TransactionStatus::Spent,
    ): Transaction {
        /** @var Account $account */
        $account = $this->getFixture($accountRef);

        $transaction = new Transaction();
        $transaction->setUser($this->user());
        $transaction->setAccount($account);
        $transaction->setAmountCents($amountCents);
        $transaction->setCurrency('EUR');
        $transaction->setBookedAt(new \DateTimeImmutable($bookedAt));
        $transaction->setLabel($label);
        $transaction->setStatus($status);

        $this->em->persist($transaction);
        $this->em->flush();

        return $transaction;
    }

    private function refresh(Transaction $transaction): Transaction
    {
        $this->em->refresh($transaction);

        return $transaction;
    }

    private function assertRejectedPair(Transaction $debit, Transaction $credit): void
    {
        $this->refresh($debit);
        $this->refresh($credit);

        self::assertSame(TransferKind::Rejected, $debit->getTransferKind(), $debit->getLabel());
        self::assertSame(TransferKind::Rejected, $credit->getTransferKind(), $credit->getLabel());
        self::assertSame(TransferSource::Auto, $credit->getTransferSource());
        self::assertSame((string) $credit->getId(), (string) $debit->getCounterpart()?->getId());
        self::assertSame((string) $debit->getId(), (string) $credit->getCounterpart()?->getId());
    }

    /** @return list<Notification> */
    private function rejectionNotifications(): array
    {
        return $this->em->getRepository(Notification::class)->findBy([
            'user' => $this->user(),
            'type' => NotificationType::Finance,
        ]);
    }

    public function testTheProductionRejectionsArePairedAsRejectionsAndNeverAsTransfers(): void
    {
        $report = $this->detect->execute($this->user());

        self::assertSame(3, $report['matched']);
        $this->assertRejectedPair($this->tx('edf_debit'), $this->tx('edf_rejection'));
        $this->assertRejectedPair($this->tx('assurance_debit'), $this->tx('assurance_rejection'));
        $this->assertRejectedPair($this->tx('ael_debit'), $this->tx('ael_rejection'));

        // The internal-transfer pass that follows leaves them alone.
        self::getContainer()->get(DetectInternalTransfers::class)->execute($this->user());

        $this->assertRejectedPair($this->tx('edf_debit'), $this->tx('edf_rejection'));
        self::assertSame(TransferKind::None, $this->refresh($this->tx('savings_debit'))->getTransferKind());
    }

    public function testTheInternalTransferPassNeverPairsARejectionCreditEvenWhenItRunsFirst(): void
    {
        // The EDF rejection is the exact opposite of a Livret debit of the
        // same day: an unguarded detection would call it a transfer.
        self::getContainer()->get(DetectInternalTransfers::class)->execute($this->user());

        self::assertSame(TransferKind::None, $this->refresh($this->tx('edf_rejection'))->getTransferKind());
        self::assertSame(TransferKind::None, $this->refresh($this->tx('savings_debit'))->getTransferKind());

        $this->detect->execute($this->user());

        $this->assertRejectedPair($this->tx('edf_debit'), $this->tx('edf_rejection'));
    }

    public function testNeitherLegCountsInAnyAggregate(): void
    {
        /** @var TransactionRepository $repository */
        $repository = self::getContainer()->get(TransactionRepository::class);
        $from = new \DateTimeImmutable('2026-10-01');
        $until = new \DateTimeImmutable('2026-11-01');

        $this->detect->execute($this->user());

        // Only the Livret debit is left: 206 € out, nothing in.
        self::assertSame(20600, $repository->sumConsumedBetween($this->user(), $from, $until));
        self::assertSame(
            ['2026-10' => ['incomeCents' => 0, 'expenseCents' => 20600]],
            $repository->sumMonthlyFlowsBetween($this->user(), $from, $until),
        );
    }

    /** @return iterable<string, array{string}> */
    public static function rejectionLabels(): iterable
    {
        yield 'rejected direct debit' => ['REJET PRLV ELECTRICITE DE FRANCE'];
        yield 'rejected transfer' => ['REJET VIREMENT WEB ELECTRICITE DE FRANCE'];
        yield 'unpaid' => ['IMPAYE PRLV ELECTRICITE DE FRANCE'];
        yield 'returned direct debit' => ['RETOUR PRLV ELECTRICITE DE FRANCE'];
        yield 'lower case' => ['Rejet prlv Electricité de France'];
    }

    #[DataProvider('rejectionLabels')]
    public function testTheUsualWordingsOfARejectionAreRecognised(string $label): void
    {
        self::assertTrue(DetectRejections::isRejectionLabel($label));
    }

    public function testAnOrdinaryCreditIsNotARejection(): void
    {
        self::assertFalse(DetectRejections::isRejectionLabel('VIREMENT EN VOTRE FAVEUR EDF REMBOURSEMENT'));
        self::assertFalse(DetectRejections::isRejectionLabel('REJETON SARL'));
    }

    public function testARejectionWithoutItsDebitStaysOrdinaryAndIsReported(): void
    {
        $orphan = $this->add(4500, '2026-10-06', 'REJET PRLV BOUYGUES TELECOM');

        $report = $this->detect->execute($this->user());

        self::assertSame(TransferKind::None, $this->refresh($orphan)->getTransferKind());
        self::assertContains((string) $orphan->getId(), array_column($report['unmatched'], 'transactionId'));
    }

    public function testADebitOfAnotherPayeeIsNotTheOneRejected(): void
    {
        $debit = $this->add(-4500, '2026-10-05', 'PRELEVEMENT SFR');
        $credit = $this->add(4500, '2026-10-06', 'REJET PRLV BOUYGUES TELECOM');

        $this->detect->execute($this->user());

        self::assertSame(TransferKind::None, $this->refresh($debit)->getTransferKind());
        self::assertSame(TransferKind::None, $this->refresh($credit)->getTransferKind());
    }

    public function testADebitOnAnotherAccountIsNotTheOneRejected(): void
    {
        $debit = $this->add(-4500, '2026-10-05', 'PRELEVEMENT BOUYGUES TELECOM', 'savings');
        $credit = $this->add(4500, '2026-10-06', 'REJET PRLV BOUYGUES TELECOM');

        $this->detect->execute($this->user());

        self::assertSame(TransferKind::None, $this->refresh($debit)->getTransferKind());
        self::assertSame(TransferKind::None, $this->refresh($credit)->getTransferKind());
    }

    public function testTheDebitIsAtMostTenDaysBeforeAndNeverAfter(): void
    {
        $tooOld = $this->add(-4500, '2026-09-25', 'PRELEVEMENT BOUYGUES TELECOM');
        $after = $this->add(-4500, '2026-10-07', 'PRELEVEMENT BOUYGUES TELECOM');
        $credit = $this->add(4500, '2026-10-06', 'REJET PRLV BOUYGUES TELECOM');

        $this->detect->execute($this->user());

        self::assertSame(TransferKind::None, $this->refresh($credit)->getTransferKind());
        self::assertSame(TransferKind::None, $this->refresh($tooOld)->getTransferKind());
        self::assertSame(TransferKind::None, $this->refresh($after)->getTransferKind());

        $tenDays = $this->add(-4500, '2026-09-26', 'PRELEVEMENT BOUYGUES TELECOM');
        $this->detect->execute($this->user());

        $this->assertRejectedPair($tenDays, $credit);
    }

    public function testADebitAlreadyPairedIsNotReusedAndTheClosestOneWins(): void
    {
        $older = $this->add(-4500, '2026-10-01', 'PRELEVEMENT BOUYGUES TELECOM');
        $closer = $this->add(-4500, '2026-10-04', 'PRELEVEMENT BOUYGUES TELECOM');
        $first = $this->add(4500, '2026-10-06', 'REJET PRLV BOUYGUES TELECOM');
        $second = $this->add(4500, '2026-10-06', 'REJET PRLV BOUYGUES TELECOM');
        $third = $this->add(4500, '2026-10-07', 'REJET PRLV BOUYGUES TELECOM');

        $this->detect->execute($this->user());

        $paired = array_filter(
            [$this->refresh($first), $this->refresh($second), $this->refresh($third)],
            static fn (Transaction $t) => TransferKind::Rejected === $t->getTransferKind(),
        );
        self::assertCount(2, $paired);
        self::assertSame(TransferKind::Rejected, $this->refresh($older)->getTransferKind());
        self::assertSame(TransferKind::Rejected, $this->refresh($closer)->getTransferKind());
        self::assertSame(TransferKind::None, $third->getTransferKind());
    }

    public function testALineJudgedByHandIsNeverRequalified(): void
    {
        $debit = $this->tx('edf_debit');
        $debit->setTransferSource(TransferSource::Manual);
        $this->em->flush();

        $this->detect->execute($this->user());

        self::assertSame(TransferKind::None, $this->refresh($debit)->getTransferKind());
        self::assertSame(TransferKind::None, $this->refresh($this->tx('edf_rejection'))->getTransferKind());
    }

    public function testADryRunReportsThePairsWithoutWritingThem(): void
    {
        $report = $this->detect->execute($this->user(), dryRun: true);

        self::assertTrue($report['dryRun']);
        self::assertSame(3, $report['matched']);
        self::assertSame(TransferKind::None, $this->refresh($this->tx('edf_rejection'))->getTransferKind());
        self::assertSame([], $this->rejectionNotifications());
    }

    public function testTwoPassesGiveTheSameResult(): void
    {
        $this->detect->execute($this->user());
        $second = $this->detect->execute($this->user());

        self::assertSame(0, $second['matched']);
        $this->assertRejectedPair($this->tx('edf_debit'), $this->tx('edf_rejection'));
    }

    public function testOneNotificationPerRecentRejectionAndNoneAgainOnTheNextPass(): void
    {
        $day = new \DateTimeImmutable('-3 days');
        $this->add(-20600, $day->modify('-1 day')->format('Y-m-d'), 'PRELEVEMENT ELECTRICITE DE FRANCE');
        $credit = $this->add(20600, $day->format('Y-m-d'), 'REJET PRLV ELECTRICITE DE FRANCE');

        $this->detect->execute($this->user());
        $this->detect->execute($this->user());

        // The 5-6 Oct. fixtures are old by the time this runs years later, but
        // not today: only the line added above is sure to be recent.
        $mine = array_values(array_filter(
            $this->rejectionNotifications(),
            static fn (Notification $n) => $n->getRelatedEntityIri() === '/api/transactions/'.$credit->getId(),
        ));

        self::assertCount(1, $mine);
        self::assertSame(
            sprintf('Prélèvement ELECTRICITE DE FRANCE de 206 € rejeté le %s', DetectRejections::shortDate($day)),
            $mine[0]->getTitle(),
        );
    }

    public function testAnOldRejectionCaughtUpOnIsPairedWithoutANotification(): void
    {
        $this->add(-4500, '2024-03-04', 'PRELEVEMENT BOUYGUES TELECOM');
        $credit = $this->add(4500, '2024-03-05', 'REJET PRLV BOUYGUES TELECOM');

        $this->detect->execute($this->user());

        self::assertSame(TransferKind::Rejected, $this->refresh($credit)->getTransferKind());
        foreach ($this->rejectionNotifications() as $notification) {
            self::assertNotSame('/api/transactions/'.$credit->getId(), $notification->getRelatedEntityIri());
        }
    }

    public function testDetectForFindsTheRejectedDebitOfANewCredit(): void
    {
        $credit = $this->tx('edf_rejection');

        self::assertSame((string) $this->tx('edf_debit')->getId(), (string) $this->detect->detectFor($credit)?->getId());
        self::assertNull($this->detect->detectFor($this->tx('edf_debit')));
        self::assertNull($this->detect->detectFor($this->tx('savings_debit')));
    }
}
