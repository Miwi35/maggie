<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\DetectInternalTransferCommand;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\GetSavingsAdvances;
use Maggie\Finance\UseCase\ReconcileAdvanceRepayments;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Only the exact amount of an open advance, going back to its own source,
 * gives it back (shape decisions 14 and 15). The regular top-up of the savings
 * account goes the same way: nothing but its amount, or the owner's `funding`
 * marking, keeps it from being eaten as a repayment.
 */
class ReconcileAdvanceRepaymentsTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SavingsAdvancesWorld;

    private ReconcileAdvanceRepayments $reconcile;
    private GetSavingsAdvances $advances;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->reconcile = self::getContainer()->get(ReconcileAdvanceRepayments::class);
        $this->advances = self::getContainer()->get(GetSavingsAdvances::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('savings_advances.yaml');
    }

    private function kindOf(Transaction $transaction): TransferKind
    {
        $this->em->refresh($transaction);

        return $transaction->getTransferKind();
    }

    private function repaysOf(Transaction $transaction): ?string
    {
        $this->em->refresh($transaction);

        return null === $transaction->getRepays() ? null : (string) $transaction->getRepays()->getId();
    }

    /** Both legs of a transfer reached the live lists and the index, once each. */
    private function assertLegsPublishedAndIndexed(Transaction ...$legs): void
    {
        foreach ($legs as $leg) {
            $this->assertMercurePublishedOnce('/api/transactions/'.$leg->getId());
            $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $leg->getId());
        }
    }

    private function assertNothingWritten(): void
    {
        $this->assertMercureUpdateCount(0);
        $this->assertNoElasticsearchIndexDispatched(Transaction::class);
    }

    /** An advance of the given amount on a source, already marked. */
    private function advance(string $sourceRef, int $amountCents, string $bookedAt): Transaction
    {
        return $this->transfer($sourceRef, 'checking', $amountCents, $bookedAt, TransferKind::Advance)[0];
    }

    public function testTheExactAmountBackToTheSourceIsARepaymentAndSettlesTheAdvance(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        [$debit, $credit] = $this->transfer('checking', 'livret', 150000, '2026-10-27');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $report = $this->reconcile->execute($this->user());

        $this->assertLegsPublishedAndIndexed($debit, $credit);
        self::assertSame(1, $report['matched']);
        self::assertSame((string) $advance->getId(), $report['repayments'][0]['advanceId']);
        self::assertSame((string) $debit->getId(), $report['repayments'][0]['transactionId']);
        self::assertSame(TransferKind::Repayment, $this->kindOf($debit));
        self::assertSame(TransferKind::Repayment, $this->kindOf($credit));
        self::assertSame(TransferSource::Auto, $debit->getTransferSource());
        self::assertSame((string) $advance->getId(), $this->repaysOf($debit));
        self::assertNull($this->repaysOf($credit));

        $data = $this->advances->execute($this->user());
        self::assertTrue($data['advances'][0]['isClosed']);
        self::assertSame(150000, $data['advances'][0]['repaidCents']);
        self::assertSame(0, $data['totalOutstandingCents']);
    }

    public function testAPartialAmountReconcilesNothing(): void
    {
        $this->advance('livret', 50000, '2026-10-01');
        [$debit] = $this->transfer('checking', 'livret', 30000, '2026-10-27');
        $this->resetMercure();
        $this->resetAsyncTransport();

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        $this->assertNothingWritten();
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertNull($this->repaysOf($debit));
        self::assertSame(50000, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    /** The owner's case: a regular top-up during a delay is never a repayment. */
    public function testARegularTopUpOfAnotherAmountStaysANeutralInternalTransfer(): void
    {
        $this->advance('livret', 150000, '2026-10-01');
        [$debit, $credit] = $this->transfer('checking', 'livret', 20000, '2026-10-27');

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertSame(TransferKind::Internal, $this->kindOf($credit));
        self::assertSame(150000, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    /** Same amount as an advance, but the owner said it is the top-up: the marking is the hard guarantee. */
    public function testALineMarkedFundingIsNeverLookedAtWhateverItsAmount(): void
    {
        $this->advance('livret', 20000, '2026-10-01');
        [$debit, $credit] = $this->transfer('checking', 'livret', 20000, '2026-10-27', TransferKind::Funding, TransferSource::Manual);

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Funding, $this->kindOf($debit));
        self::assertSame(TransferKind::Funding, $this->kindOf($credit));
        self::assertNull($this->repaysOf($debit));
        self::assertSame(20000, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    public function testAFundingLegIsRefusedEvenWhenTheTransferIsAskedDirectly(): void
    {
        $this->advance('livret', 20000, '2026-10-01');
        [$debit, $credit] = $this->transfer('checking', 'livret', 20000, '2026-10-27', TransferKind::Funding, TransferSource::Auto);

        self::assertNull($this->reconcile->advanceRepaidBy($debit, $credit));
        self::assertSame(20000, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    public function testATransferToTheWrongSourceReconcilesNothing(): void
    {
        $this->advance('livret', 150000, '2026-10-01');
        [$debit] = $this->transfer('checking', 'pea', 150000, '2026-10-27');

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertSame(150000, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    public function testAMarkingMadeByHandIsNeverRequalified(): void
    {
        $this->advance('livret', 150000, '2026-10-01');
        [$debit] = $this->transfer('checking', 'livret', 150000, '2026-10-27', TransferKind::Internal, TransferSource::Manual);

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertSame(TransferSource::Manual, $debit->getTransferSource());
    }

    public function testAnAlreadySettledAdvanceTakesNothingMore(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        $this->repay($advance, 'livret', 150000, '2026-10-20');
        [$debit] = $this->transfer('checking', 'livret', 150000, '2026-10-27');

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testWhatIsLeftAfterAPartRepaymentIsTheAmountToMatch(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        $this->repay($advance, 'livret', 100000, '2026-10-20');
        [$whole] = $this->transfer('checking', 'livret', 150000, '2026-10-26');
        [$rest] = $this->transfer('checking', 'livret', 50000, '2026-10-27');

        self::assertSame(1, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($whole));
        self::assertSame(TransferKind::Repayment, $this->kindOf($rest));
        self::assertSame((string) $advance->getId(), $this->repaysOf($rest));
    }

    public function testATransferBookedBeforeTheAdvanceCannotGiveItBack(): void
    {
        $this->advance('livret', 150000, '2026-10-10');
        [$debit] = $this->transfer('checking', 'livret', 150000, '2026-10-05');

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testTwoAdvancesOfTheSameBalanceTheOldestIsRepaidFirstAndTwoRunsAgree(): void
    {
        $older = $this->advance('livret', 50000, '2026-10-01');
        $newer = $this->advance('livret', 50000, '2026-10-03');
        [$first] = $this->transfer('checking', 'livret', 50000, '2026-10-27');

        $report = $this->reconcile->execute($this->user());
        self::assertSame(1, $report['matched']);
        self::assertSame((string) $older->getId(), $this->repaysOf($first));

        $again = $this->reconcile->execute($this->user());
        self::assertSame(0, $again['matched']);
        self::assertSame((string) $older->getId(), $this->repaysOf($first));

        // The second transfer of the same amount takes the other one.
        [$second] = $this->transfer('checking', 'livret', 50000, '2026-11-02');
        self::assertSame(1, $this->reconcile->execute($this->user())['matched']);
        self::assertSame((string) $newer->getId(), $this->repaysOf($second));
        self::assertSame(0, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    public function testTwoTransfersAndTwoAdvancesOfTheSameBalanceAreMatchedOldestToOldestInOnePass(): void
    {
        $older = $this->advance('livret', 50000, '2026-10-01');
        $newer = $this->advance('livret', 50000, '2026-10-03');
        [$first] = $this->transfer('checking', 'livret', 50000, '2026-10-27');
        [$second] = $this->transfer('checking', 'livret', 50000, '2026-10-28');

        self::assertSame(2, $this->reconcile->execute($this->user())['matched']);
        self::assertSame((string) $older->getId(), $this->repaysOf($first));
        self::assertSame((string) $newer->getId(), $this->repaysOf($second));
    }

    public function testADryRunReportsWithoutWriting(): void
    {
        $this->advance('livret', 150000, '2026-10-01');
        [$debit] = $this->transfer('checking', 'livret', 150000, '2026-10-27');

        $this->resetMercure();
        $this->resetAsyncTransport();

        $report = $this->reconcile->execute($this->user(), null, true);

        self::assertSame(1, $report['matched']);
        $this->assertNothingWritten();
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertNull($this->repaysOf($debit));
    }

    public function testAnotherUsersAdvancesAreNeverRepaid(): void
    {
        $this->transfer('other_livret', 'other_checking', 150000, '2026-10-01', TransferKind::Advance);
        [$debit] = $this->transfer('checking', 'livret', 150000, '2026-10-27');

        self::assertSame(0, $this->reconcile->execute($this->user())['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testTheSecondLegArrivingMarksTheRepaymentOnCreation(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        $debit = $this->add('checking', -150000, '2026-10-27', label: 'Virement émis');
        $credit = $this->add('livret', 150000, '2026-10-27', label: 'Virement reçu');
        $this->resetMercure();
        $this->resetAsyncTransport();

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DetectInternalTransferCommand((string) $credit->getId()));

        $this->assertLegsPublishedAndIndexed($debit, $credit);
        self::assertSame(TransferKind::Repayment, $this->kindOf($debit));
        self::assertSame(TransferKind::Repayment, $this->kindOf($credit));
        self::assertSame((string) $advance->getId(), $this->repaysOf($debit));
        self::assertNull($this->repaysOf($credit));
        self::assertSame(0, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    public function testTheDebitArrivingLastMarksTheRepaymentOnItsOwnLeg(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        $credit = $this->add('livret', 150000, '2026-10-27', label: 'Virement reçu');
        $debit = $this->add('checking', -150000, '2026-10-27', label: 'Virement émis');

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DetectInternalTransferCommand((string) $debit->getId()));

        self::assertSame((string) $advance->getId(), $this->repaysOf($debit));
        self::assertNull($this->repaysOf($credit));
    }

    public function testAMonthlyTopUpArrivingOnCreationStaysInternalDuringTheDelay(): void
    {
        $this->advance('livret', 150000, '2026-10-01');
        $debit = $this->add('checking', -20000, '2026-10-27', label: 'Virement épargne');
        $credit = $this->add('livret', 20000, '2026-10-27', label: 'Virement épargne');

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DetectInternalTransferCommand((string) $credit->getId()));

        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertNull($this->repaysOf($debit));
        self::assertSame(150000, $this->advances->execute($this->user())['totalOutstandingCents']);
    }

    public function testTheCatchUpPassPairsAndReconcilesLinesNotPairedYet(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        $debit = $this->add('checking', -150000, '2026-10-27', label: 'Virement émis');
        $this->add('livret', 150000, '2026-10-27', label: 'Virement reçu');
        $topUpDebit = $this->add('checking', -20000, '2026-10-28', label: 'Virement épargne');
        $this->add('livret', 20000, '2026-10-28', label: 'Virement épargne');

        $report = self::getContainer()->get(DetectInternalTransfers::class)->execute($this->user());

        self::assertSame(2, $report['matched']);
        self::assertSame(1, $report['repayments']);
        self::assertSame(TransferKind::Repayment, $this->kindOf($debit));
        self::assertSame((string) $advance->getId(), $this->repaysOf($debit));
        self::assertSame(TransferKind::Internal, $this->kindOf($topUpDebit));
    }

    public function testTheCatchUpPassDryRunSaysRepaymentWithoutWritingAnything(): void
    {
        $this->advance('livret', 150000, '2026-10-01');
        $debit = $this->add('checking', -150000, '2026-10-27', label: 'Virement émis');
        $this->add('livret', 150000, '2026-10-27', label: 'Virement reçu');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $report = self::getContainer()->get(DetectInternalTransfers::class)->execute($this->user(), null, true);

        $this->assertNothingWritten();
        self::assertSame(1, $report['repayments']);
        self::assertSame('repayment', $report['pairs'][0]['transferKind']);
        self::assertSame(TransferKind::None, $this->kindOf($debit));
    }

    public function testTheCatchUpPassRequalifiesAnInternalPairAlreadyMade(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        [$debit, $credit] = $this->transfer('checking', 'livret', 150000, '2026-10-27');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $report = self::getContainer()->get(DetectInternalTransfers::class)->execute($this->user());

        $this->assertLegsPublishedAndIndexed($debit, $credit);
        self::assertSame(0, $report['matched']);
        self::assertSame(1, $report['repayments']);
        self::assertSame((string) $advance->getId(), $this->repaysOf($debit));
    }

    public function testAnAdvanceGivenBackOnceIsNotGivenBackAgainByAnIdenticalTransferOnCreation(): void
    {
        $advance = $this->advance('livret', 150000, '2026-10-01');
        $bus = self::getContainer()->get(MessageBusInterface::class);

        $firstDebit = $this->add('checking', -150000, '2026-10-26', label: 'Virement émis');
        $firstCredit = $this->add('livret', 150000, '2026-10-26', label: 'Virement reçu');
        $bus->dispatch(new DetectInternalTransferCommand((string) $firstCredit->getId()));

        $secondDebit = $this->add('checking', -150000, '2026-10-27', label: 'Virement émis');
        $secondCredit = $this->add('livret', 150000, '2026-10-27', label: 'Virement reçu');
        $bus->dispatch(new DetectInternalTransferCommand((string) $secondCredit->getId()));

        self::assertSame((string) $advance->getId(), $this->repaysOf($firstDebit));
        self::assertSame(TransferKind::Internal, $this->kindOf($secondDebit));
        self::assertNull($this->repaysOf($secondDebit));
        self::assertSame(150000, $this->advances->execute($this->user())['advances'][0]['repaidCents']);
    }
}
