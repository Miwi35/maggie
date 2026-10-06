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
use Maggie\Finance\UseCase\DetectInternalTransfers;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * What the detection pairs, and above all what it refuses to pair: a wrong
 * pairing takes a real expense out of the lifestyle, which is worse than a
 * transfer left counted twice because the owner can see the second one.
 */
class DetectInternalTransfersTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private DetectInternalTransfers $detect;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->detect = self::getContainer()->get(DetectInternalTransfers::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('internal_transfers.yaml');
    }

    private function user(string $ref = 'test_user'): User
    {
        /** @var User $user */
        $user = $this->getFixture($ref);

        return $user;
    }

    private function account(string $ref): Account
    {
        /** @var Account $account */
        $account = $this->getFixture($ref);

        return $account;
    }

    private function tx(string $ref): Transaction
    {
        /** @var Transaction $transaction */
        $transaction = $this->getFixture($ref);

        return $transaction;
    }

    /** Adds one movement, flushed, so a query can see it. */
    private function add(
        string $accountRef,
        int $amountCents,
        string $bookedAt,
        string $label = 'Mouvement',
        TransactionStatus $status = TransactionStatus::Spent,
        string $currency = 'EUR',
        string $userRef = 'test_user',
    ): Transaction {
        $transaction = new Transaction();
        $transaction->setUser($this->user($userRef));
        $transaction->setAccount($this->account($accountRef));
        $transaction->setAmountCents($amountCents);
        $transaction->setCurrency($currency);
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

    public function testAnExactPairIsMarkedOnBothSidesPointingAtEachOther(): void
    {
        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');

        $report = $this->detect->execute($this->user());

        self::assertSame(1, $report['matched']);
        self::assertFalse($report['dryRun']);

        self::assertTrue($this->refresh($out)->isInternalTransfer());
        self::assertTrue($this->refresh($in)->isInternalTransfer());
        self::assertSame(TransferKind::Internal, $out->getTransferKind());
        self::assertSame(TransferSource::Auto, $out->getTransferSource());
        self::assertSame((string) $in->getId(), (string) $out->getCounterpart()?->getId());
        self::assertSame((string) $out->getId(), (string) $in->getCounterpart()?->getId());
    }

    public function testAnOrdinaryExpenseOfTheSameMonthStaysAnExpense(): void
    {
        $groceries = $this->tx('groceries');

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($groceries)->isInternalTransfer());
    }

    public function testAmountsThatAreNotExactlyOppositeAreNotPaired(): void
    {
        $out = $this->add('savings', -150000, '2026-08-10');
        $in = $this->add('checking', 149900, '2026-08-11');

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    public function testTwoCurrenciesNeverPair(): void
    {
        $out = $this->add('savings', -150000, '2026-08-10', currency: 'EUR');
        $in = $this->add('checking', 150000, '2026-08-11', currency: 'CHF');

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    public function testFourDaysApartPairsAndFiveDoesNot(): void
    {
        $near = $this->add('savings', -111100, '2026-08-10');
        $nearIn = $this->add('checking', 111100, '2026-08-14');
        $far = $this->add('savings', -222200, '2026-08-10');
        $farIn = $this->add('checking', 222200, '2026-08-15');

        $this->detect->execute($this->user());

        self::assertTrue($this->refresh($near)->isInternalTransfer());
        self::assertTrue($this->refresh($nearIn)->isInternalTransfer());
        self::assertFalse($this->refresh($far)->isInternalTransfer());
        self::assertFalse($this->refresh($farIn)->isInternalTransfer());
    }

    public function testTwoLinesOfTheSameAccountNeverPair(): void
    {
        $out = $this->add('checking', -120000, '2026-08-10');
        $in = $this->add('checking', 120000, '2026-08-11');

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    public function testAZeroAmountIsNotATransfer(): void
    {
        $out = $this->add('savings', 0, '2026-08-10');
        $in = $this->add('checking', 0, '2026-08-11');

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    /**
     * @return iterable<string, array{TransactionStatus}>
     */
    public static function unconsumedStatuses(): iterable
    {
        yield 'planned' => [TransactionStatus::Planned];
        yield 'to_arbitrate' => [TransactionStatus::ToArbitrate];
    }

    #[DataProvider('unconsumedStatuses')]
    public function testAMovementThatHasNotHappenedYetIsNotPaired(TransactionStatus $status): void
    {
        $out = $this->add('savings', -133300, '2026-08-10', status: $status);
        $in = $this->add('checking', 133300, '2026-08-11', status: $status);

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    public function testALineAlreadyPairedIsNotRepaired(): void
    {
        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');
        // Inside the window of the debit, but farther than the real leg.
        $decoy = $this->add('checking', 300000, '2026-09-16', 'Autre crédit de 3 000 €');

        $this->detect->execute($this->user());
        $this->detect->execute($this->user());

        self::assertSame((string) $out->getId(), (string) $this->refresh($in)->getCounterpart()?->getId());
        self::assertFalse($this->refresh($decoy)->isInternalTransfer());
    }

    public function testADecisionMadeByHandIsNeverRequalified(): void
    {
        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');

        // The owner said « this one is not a transfer ».
        $out->releaseInternalTransfer(TransferSource::Manual);
        $this->em->flush();

        $report = $this->detect->execute($this->user());

        self::assertSame(0, $report['matched']);
        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    public function testTheClosestDateWinsAmongThreeCandidatesOfTheSameAmount(): void
    {
        $out = $this->add('savings', -50000, '2026-08-10', 'Virement sortant');
        $farther = $this->add('checking', 50000, '2026-08-07', 'Crédit du 7');
        $closest = $this->add('checking', 50000, '2026-08-09', 'Crédit du 9');
        $alsoFar = $this->add('checking', 50000, '2026-08-13', 'Crédit du 13');

        $this->detect->execute($this->user());

        self::assertSame((string) $closest->getId(), (string) $this->refresh($out)->getCounterpart()?->getId());
        self::assertSame((string) $out->getId(), (string) $this->refresh($closest)->getCounterpart()?->getId());
        self::assertFalse($this->refresh($farther)->isInternalTransfer());
        self::assertFalse($this->refresh($alsoFar)->isInternalTransfer());
    }

    public function testTwoCatchUpPassesGiveTheSameResult(): void
    {
        $this->add('savings', -50000, '2026-08-10');
        $this->add('checking', 50000, '2026-08-09');
        $this->add('checking', 50000, '2026-08-11');

        $first = $this->detect->execute($this->user());
        $second = $this->detect->execute($this->user());

        self::assertSame(0, $second['matched'], 'the second pass has nothing left to pair');

        $this->purgeDatabase();
        $this->loadFixtures('internal_transfers.yaml');
        $this->add('savings', -50000, '2026-08-10');
        $this->add('checking', 50000, '2026-08-09');
        $this->add('checking', 50000, '2026-08-11');

        $again = $this->detect->execute($this->user());

        self::assertSame(
            array_map(static fn (array $pair) => [$pair['bookedAt'], $pair['counterpartBookedAt'], $pair['amountCents']], $first['pairs']),
            array_map(static fn (array $pair) => [$pair['bookedAt'], $pair['counterpartBookedAt'], $pair['amountCents']], $again['pairs']),
            'the same history must pair the same way',
        );
    }

    public function testSomeoneElsesMovementIsNeverTheCounterpart(): void
    {
        $mine = $this->add('savings', -170000, '2026-08-10');
        $theirs = $this->add('other_checking', 170000, '2026-08-11', userRef: 'other_user');

        $this->detect->execute($this->user());

        self::assertFalse($this->refresh($mine)->isInternalTransfer());
        self::assertFalse($this->refresh($theirs)->isInternalTransfer());
    }

    public function testADryRunReportsThePairWithoutWritingIt(): void
    {
        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');

        $report = $this->detect->execute($this->user(), null, true);

        self::assertTrue($report['dryRun']);
        self::assertSame(1, $report['matched']);
        self::assertSame((string) $in->getId(), $report['pairs'][0]['counterpartId']);

        self::assertFalse($this->refresh($out)->isInternalTransfer());
        self::assertFalse($this->refresh($in)->isInternalTransfer());
    }

    public function testADryRunNeverPairsTheSameLineTwice(): void
    {
        $debit = $this->add('savings', -50000, '2026-08-10');
        $this->add('checking', 50000, '2026-08-09');
        $this->add('checking', 50000, '2026-08-11');

        $report = $this->detect->execute($this->user(), null, true);

        $legs = [];
        foreach ($report['pairs'] as $pair) {
            $legs[] = $pair['transactionId'];
            $legs[] = $pair['counterpartId'];
        }

        self::assertSame(
            array_values(array_unique($legs)),
            $legs,
            'nothing is written, so the pass itself must remember what it claimed',
        );
        self::assertSame(1, \count(array_keys($legs, (string) $debit->getId(), true)));
    }

    public function testLimitDaysLeavesTheOlderHistoryAlone(): void
    {
        $old = $this->tx('transfer_out');
        $recentOut = $this->add('savings', -90000, (new \DateTimeImmutable('midnight -2 days'))->format('Y-m-d'));
        $recentIn = $this->add('checking', 90000, (new \DateTimeImmutable('midnight -1 day'))->format('Y-m-d'));

        $report = $this->detect->execute($this->user(), 7);

        self::assertSame(1, $report['matched']);
        self::assertTrue($this->refresh($recentOut)->isInternalTransfer());
        self::assertTrue($this->refresh($recentIn)->isInternalTransfer());
        self::assertFalse($this->refresh($old)->isInternalTransfer());
    }

    public function testDetectForFindsTheOtherLegOfASingleLine(): void
    {
        $out = $this->tx('transfer_out');
        $in = $this->tx('transfer_in');

        self::assertSame((string) $in->getId(), (string) $this->detect->detectFor($out)?->getId());
        self::assertNull($this->detect->detectFor($this->tx('groceries')));
    }
}
