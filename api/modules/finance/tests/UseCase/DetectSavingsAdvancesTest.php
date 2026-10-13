<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Message\DetectInternalTransferCommand;
use Maggie\Finance\UseCase\DetectInternalTransfers;
use Maggie\Finance\UseCase\DetectSavingsAdvances;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * An advance is an internal transfer from savings or investment to checking,
 * booked while the salary is late (shape decision 12) — and nothing else is.
 *
 * The salary is expected on the 25th and none came in September: from
 * 26 September on, the salary is late.
 */
class DetectSavingsAdvancesTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use SavingsAdvancesWorld;

    private const string DURING_DELAY = '2026-10-10';

    private DetectInternalTransfers $detectTransfers;
    private DetectSavingsAdvances $detectAdvances;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->detectTransfers = self::getContainer()->get(DetectInternalTransfers::class);
        $this->detectAdvances = self::getContainer()->get(DetectSavingsAdvances::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('savings_advances.yaml');
    }

    private function kindOf(Transaction $transaction): TransferKind
    {
        $this->em->refresh($transaction);

        return $transaction->getTransferKind();
    }

    /**
     * Both legs written, not paired yet, then the catch-up pass.
     *
     * @return array{Transaction, Transaction}
     */
    private function movedAndCaughtUp(string $fromRef, string $toRef, string $bookedAt, string $userRef = 'test_user'): array
    {
        $debit = $this->add($fromRef, -150000, $bookedAt, label: 'Virement émis');
        $credit = $this->add($toRef, 150000, $bookedAt, label: 'Virement reçu');

        $this->detectTransfers->execute($this->user($userRef));

        return [$debit, $credit];
    }

    public function testSavingsToCheckingDuringADelayIsAnAdvanceOnBothLegs(): void
    {
        $debit = $this->add('livret', -150000, self::DURING_DELAY);
        $credit = $this->add('checking', 150000, self::DURING_DELAY);

        $report = $this->detectTransfers->execute($this->user());

        self::assertSame(1, $report['advances']);
        self::assertSame('advance', $report['pairs'][0]['transferKind']);
        self::assertSame(TransferKind::Advance, $this->kindOf($debit));
        self::assertSame(TransferKind::Advance, $this->kindOf($credit));
        self::assertSame(TransferSource::Auto, $debit->getTransferSource());
        self::assertSame((string) $credit->getId(), (string) $debit->getCounterpart()?->getId());
    }

    /** On creation: the second leg arriving pairs the transfer as an advance at once. */
    public function testTheSecondLegArrivingMarksTheAdvanceOnCreation(): void
    {
        $debit = $this->add('livret', -150000, self::DURING_DELAY);
        $credit = $this->add('checking', 150000, self::DURING_DELAY);

        self::getContainer()->get(MessageBusInterface::class)->dispatch(new DetectInternalTransferCommand((string) $credit->getId()));

        self::assertSame(TransferKind::Advance, $this->kindOf($debit));
        self::assertSame(TransferKind::Advance, $this->kindOf($credit));
    }

    public function testAnInvestmentAccountIsASourceToo(): void
    {
        [$debit] = $this->movedAndCaughtUp('pea', 'checking', self::DURING_DELAY);

        self::assertSame(TransferKind::Advance, $this->kindOf($debit));
    }

    public function testOutsideADelayTheTransferStaysANeutralInternalTransfer(): void
    {
        $this->salary(495000, '2026-09-25');

        [$debit, $credit] = $this->movedAndCaughtUp('livret', 'checking', self::DURING_DELAY);

        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertSame(TransferKind::Internal, $this->kindOf($credit));
    }

    /** The day expected is not late yet: the delay opens the day after. */
    public function testOnTheExpectedDayItselfNothingIsAnAdvance(): void
    {
        $this->salary(495000, '2026-08-25');

        [$debit] = $this->movedAndCaughtUp('livret', 'checking', '2026-09-25');

        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testCheckingToSavingsIsNeverAnAdvance(): void
    {
        [$debit] = $this->movedAndCaughtUp('checking', 'livret', self::DURING_DELAY);

        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testCheckingToCheckingIsNeverAnAdvance(): void
    {
        [$debit] = $this->movedAndCaughtUp('joint', 'checking', self::DURING_DELAY);

        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    /** A pair made before the delay was known is requalified by the catch-up pass. */
    public function testAnInternalTransferAlreadyPairedIsRequalified(): void
    {
        [$debit, $credit] = $this->transfer('livret', 'checking', 150000, self::DURING_DELAY);

        $report = $this->detectAdvances->execute($this->user());

        self::assertSame(1, $report['matched']);
        self::assertSame(150000, $report['advances'][0]['amountCents']);
        self::assertSame(TransferKind::Advance, $this->kindOf($debit));
        self::assertSame(TransferKind::Advance, $this->kindOf($credit));
    }

    public function testAMarkingMadeByHandIsNeverRequalified(): void
    {
        [$debit] = $this->transfer('livret', 'checking', 150000, self::DURING_DELAY, source: TransferSource::Manual);

        $report = $this->detectTransfers->execute($this->user());

        self::assertSame(0, $report['advances']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
        self::assertSame(TransferSource::Manual, $debit->getTransferSource());
    }

    public function testADryRunWritesNothing(): void
    {
        [$debit] = $this->transfer('livret', 'checking', 150000, self::DURING_DELAY);

        $report = $this->detectAdvances->execute($this->user(), dryRun: true);

        self::assertSame(1, $report['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testWithoutAnExpectedDayNoAdvanceIsDetected(): void
    {
        [$debit] = $this->movedAndCaughtUp('bare_livret', 'bare_checking', self::DURING_DELAY, 'bare_user');

        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }

    public function testAnotherUsersTransfersAreLeftAlone(): void
    {
        [$debit] = $this->transfer('other_livret', 'other_checking', 150000, self::DURING_DELAY);

        $report = $this->detectAdvances->execute($this->user());

        self::assertSame(0, $report['matched']);
        self::assertSame(TransferKind::Internal, $this->kindOf($debit));
    }
}
