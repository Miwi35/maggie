<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\RecurringLinkSource;
use Maggie\Finance\Enum\ReferenceAmountSource;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Exception\RecurringAttachmentException;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\Message\CreateTransactionCommand;
use Maggie\Finance\UseCase\AttachRecurringTransactions;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Decision 4 of the recurring operations spec: the counterparty says who the
 * money is from, not whether the line belongs to the series. Attached when the
 * date and the amount both hold on a free occurrence, proposed when exactly
 * one breaks, a one-off otherwise — and nothing but an attachment is written.
 *
 * The world: a gym, 30 € on the 1st of every month, ±5 days, ±20 %.
 */
class AttachRecurringTransactionsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private AttachRecurringTransactions $attach;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->attach = self::getContainer()->get(AttachRecurringTransactions::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('recurring_attachment.yaml');
    }

    private function user(): User
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');

        return $user;
    }

    private function gym(): RecurringOperation
    {
        /** @var RecurringOperation $gym */
        $gym = $this->getFixture('gym');

        return $gym;
    }

    private function line(int $amountCents, string $bookedAt, string $account = 'checking', string $counterparty = 'Club Forme'): Transaction
    {
        /** @var Account $onAccount */
        $onAccount = $this->getFixture($account);

        // By reference: a test that cleared the manager holds detached fixtures.
        $transaction = (new Transaction())
            ->setUser($this->em->getReference(User::class, $this->user()->getId()))
            ->setAccount($this->em->getReference(Account::class, $onAccount->getId()))
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel('CB '.mb_strtoupper($counterparty).' '.$bookedAt)
            ->setCounterpartyName($counterparty);

        $this->em->persist($transaction);
        $this->em->flush();

        return $transaction;
    }

    private function reload(Transaction $transaction): Transaction
    {
        $this->em->clear();

        /** @var Transaction $fresh */
        $fresh = $this->em->find(Transaction::class, $transaction->getId());

        return $fresh;
    }

    private function reloadGym(): RecurringOperation
    {
        $this->em->clear();

        /** @var RecurringOperation $gym */
        $gym = $this->em->find(RecurringOperation::class, $this->gym()->getId());

        return $gym;
    }

    /**
     * @param array<string, mixed> $report
     *
     * @return array<string, mixed>|null
     */
    private function proposalFor(array $report, Transaction $transaction): ?array
    {
        foreach ($report['proposals'] as $proposal) {
            if ($proposal['transactionId'] === (string) $transaction->getId()) {
                return $proposal;
            }
        }

        return null;
    }

    public function testALineWhoseDateAndAmountHoldIsAttachedToItsOccurrenceWithTheSeriesCategory(): void
    {
        /** @var Transaction $line */
        $line = $this->getFixture('feb_gym');

        $report = $this->attach->execute($this->user());

        self::assertSame(1, $report['attached']);
        $stored = $this->reload($line);
        self::assertSame((string) $this->gym()->getId(), (string) $stored->getRecurringOperation()?->getId());
        self::assertSame('2027-02-01', $stored->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertSame(RecurringLinkSource::Auto, $stored->getRecurringSource());
        self::assertSame('Abonnements', $stored->getCategory()?->getName());
        self::assertSame(CategorySource::Series, $stored->getCategorySource());
    }

    public function testACategorySetByHandIsNotOverwritten(): void
    {
        $line = $this->line(-3000, '2027-05-02');
        /** @var Category $sport */
        $sport = $this->getFixture('sport');
        $line->assignCategory($sport, CategorySource::Manual);
        $this->em->flush();

        $this->attach->execute($this->user());

        $stored = $this->reload($line);
        self::assertNotNull($stored->getRecurringOperation());
        self::assertSame('Sport', $stored->getCategory()?->getName());
        self::assertSame(CategorySource::Manual, $stored->getCategorySource());
    }

    public function testFiveDaysLateIsAttachedAndSixIsOnlyProposed(): void
    {
        $fiveDays = $this->line(-3000, '2027-05-06');
        $sixDays = $this->line(-3000, '2027-06-07');

        $report = $this->attach->execute($this->user());

        self::assertNotNull($this->reload($fiveDays)->getRecurringOperation());
        self::assertNull($this->reload($sixDays)->getRecurringOperation());

        $proposal = $this->proposalFor($report, $sixDays);
        self::assertNotNull($proposal);
        self::assertSame('late', $proposal['reason']);
        self::assertSame(6, $proposal['dateGapDays']);
        self::assertSame('2027-06-01', $proposal['occurrenceOn']);
    }

    public function testAnEarlyLineIsProposedAsEarly(): void
    {
        $early = $this->line(-3000, '2027-05-24');

        $proposal = $this->proposalFor($this->attach->execute($this->user()), $early);

        self::assertNotNull($proposal);
        self::assertSame('early', $proposal['reason']);
        self::assertSame(-8, $proposal['dateGapDays']);
    }

    public function testNineteenPercentMoreIsAttached(): void
    {
        $nineteen = $this->line(-3570, '2027-05-01');

        $this->attach->execute($this->user());

        self::assertNotNull($this->reload($nineteen)->getRecurringOperation());
    }

    public function testThirtyThreePercentMoreIsAPriceRiseWithNothingWritten(): void
    {
        /** @var Transaction $dearer */
        $dearer = $this->getFixture('apr_gym_dearer');

        $report = $this->attach->execute($this->user());

        $stored = $this->reload($dearer);
        self::assertNull($stored->getRecurringOperation());
        self::assertSame(CategorySource::None, $stored->getCategorySource());

        $proposal = $this->proposalFor($report, $dearer);
        self::assertNotNull($proposal);
        self::assertSame('amount_up', $proposal['reason']);
        self::assertSame(33, $proposal['amountChangePercent']);
        self::assertSame(-3000, $proposal['referenceAmountCents']);
        self::assertSame((string) $this->gym()->getId(), $proposal['recurringOperationId']);
    }

    public function testACheaperLineIsProposedAsAmountDown(): void
    {
        $cheaper = $this->line(-2000, '2027-05-01');

        $proposal = $this->proposalFor($this->attach->execute($this->user()), $cheaper);

        self::assertNotNull($proposal);
        self::assertSame('amount_down', $proposal['reason']);
        self::assertSame(-33, $proposal['amountChangePercent']);
    }

    public function testALineOutOfBothTolerancesIsAOneOff(): void
    {
        /** @var Transaction $shop */
        $shop = $this->getFixture('mar_gym_shop');

        $report = $this->attach->execute($this->user());

        self::assertNull($this->reload($shop)->getRecurringOperation());
        self::assertNull($this->proposalFor($report, $shop));
    }

    public function testASecondDebitOfTheSameMonthStaysAOneOff(): void
    {
        $first = $this->line(-3000, '2027-05-02');
        $second = $this->line(-3000, '2027-05-03');

        $report = $this->attach->execute($this->user());

        self::assertSame('2027-05-01', $this->reload($first)->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertNull($this->reload($second)->getRecurringOperation());
        self::assertNull($this->proposalFor($report, $second));
    }

    public function testTheClosestOfTwoLinesWinsTheOccurrenceWhicheverComesFirst(): void
    {
        $later = $this->line(-3000, '2027-05-04');
        $closer = $this->line(-3000, '2027-05-01');

        $this->attach->execute($this->user());

        self::assertNotNull($this->reload($closer)->getRecurringOperation());
        self::assertNull($this->reload($later)->getRecurringOperation());
    }

    public function testTwoSeriesClaimingTheSameLineAreAmbiguousAndNothingIsWritten(): void
    {
        /** @var Category $sport */
        $sport = $this->getFixture('sport');
        $twin = (new RecurringOperation())
            ->setLabel('Cours collectifs')
            ->setCounterpartyName('Club Forme')
            ->setCategory($sport)
            ->setAccount($this->gym()->getAccount())
            ->setPeriod(RecurrencePeriod::Monthly)
            ->setAnchorOn(new \DateTimeImmutable('2027-01-02'))
            ->setReferenceAmountCents(-3200)
            ->setUser($this->user());
        $this->em->persist($twin);
        $this->em->flush();

        $line = $this->line(-3100, '2027-05-01');

        $proposal = $this->proposalFor($this->attach->execute($this->user()), $line);

        self::assertNull($this->reload($line)->getRecurringOperation());
        self::assertNotNull($proposal);
        self::assertSame('ambiguous', $proposal['reason']);
        self::assertCount(2, $proposal['candidates']);
    }

    public function testNeutralArbitratedForeignAndOppositeLinesAreLeftAlone(): void
    {
        $toArbitrate = $this->line(-3000, '2027-05-01')->setStatus(TransactionStatus::ToArbitrate);
        $transfer = $this->line(-3000, '2027-06-01')->setTransferKind(TransferKind::Internal);
        $otherAccount = $this->line(-3000, '2027-07-01', 'savings');
        $otherCurrency = $this->line(-3000, '2027-08-01')->setCurrency('USD');
        $refund = $this->line(3000, '2027-09-01');
        $this->em->flush();

        $report = $this->attach->execute($this->user());

        foreach ([$toArbitrate, $transfer, $otherAccount, $otherCurrency, $refund] as $line) {
            self::assertNull($this->reload($line)->getRecurringOperation(), $line->getLabel());
            self::assertNull($this->proposalFor($report, $line), $line->getLabel());
        }
    }

    public function testALineDetachedByHandIsNeverAttachedAgain(): void
    {
        $line = $this->line(-3000, '2027-05-01');
        $this->attach->attachByHand($line, $this->gym());
        $this->attach->detachByHand($line);
        $this->em->flush();

        $this->attach->execute($this->user());

        $stored = $this->reload($line);
        self::assertNull($stored->getRecurringOperation());
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());
    }

    public function testTwoPassesGiveExactlyTheSameResult(): void
    {
        $this->line(-3000, '2027-05-02');
        $this->line(-3000, '2027-05-03');
        $this->line(-3300, '2027-06-01');
        $this->line(-3000, '2027-07-09');
        $this->line(-3400, '2027-08-01');
        $this->em->clear();

        $first = $this->attach->execute($this->user());
        $state = $this->snapshot();
        $this->em->clear();

        $second = $this->attach->execute($this->user());

        self::assertGreaterThan(0, $first['attached']);
        self::assertSame(0, $second['attached']);
        self::assertSame($first['proposals'], $second['proposals']);
        self::assertSame($state, $this->snapshot());
    }

    /** @return array<string, mixed> every attachment and the reference amount, as stored */
    private function snapshot(): array
    {
        $this->em->clear();

        $lines = [];
        foreach ($this->em->getRepository(Transaction::class)->findBy([], ['id' => 'ASC']) as $transaction) {
            $lines[(string) $transaction->getId()] = [
                (string) $transaction->getRecurringOperation()?->getId(),
                $transaction->getRecurringOccurrenceOn()?->format('Y-m-d'),
                $transaction->getRecurringSource()->value,
                $transaction->getCategorySource()->value,
            ];
        }

        return ['lines' => $lines, 'reference' => $this->reloadGym()->getReferenceAmountCents()];
    }

    public function testADryRunWritesNothing(): void
    {
        /** @var Transaction $line */
        $line = $this->getFixture('feb_gym');

        $report = $this->attach->execute($this->user(), dryRun: true);

        self::assertTrue($report['dryRun']);
        self::assertSame(1, $report['attached']);
        self::assertNull($this->reload($line)->getRecurringOperation());
    }

    public function testAMeasuredReferenceFollowsTheAverageOfTheLastThreeAttachments(): void
    {
        $this->em->remove($this->getFixture('feb_gym'));
        $this->em->flush();
        $this->line(-3000, '2027-05-01');
        $this->line(-3300, '2027-06-01');
        $this->line(-3500, '2027-07-01');
        $this->line(-3200, '2027-08-01');

        $this->attach->execute($this->user());

        // (3300 + 3500 + 3200) / 3, the May line falls out of the window.
        self::assertSame(-3333, $this->reloadGym()->getReferenceAmountCents());
    }

    public function testADeclaredReferenceNeverMoves(): void
    {
        $this->gym()->setReferenceSource(ReferenceAmountSource::Declared);
        $this->em->flush();
        $this->line(-3300, '2027-05-01');
        $this->line(-3500, '2027-06-01');

        $this->attach->execute($this->user());

        self::assertSame(-3000, $this->reloadGym()->getReferenceAmountCents());
    }

    public function testAcceptingAPriceRiseMovesTheReferenceToTheNewPrice(): void
    {
        /** @var Transaction $dearer */
        $dearer = $this->getFixture('apr_gym_dearer');
        $this->attach->execute($this->user());

        $changed = $this->attach->attachByHand($dearer, $this->gym());
        $this->em->flush();

        // A rise is a break: averaging 30 € with 39,90 € is a price nobody pays.
        self::assertSame([$this->gym()], $changed);
        $stored = $this->reload($dearer);
        self::assertSame('2027-04-01', $stored->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertSame(RecurringLinkSource::Manual, $stored->getRecurringSource());
        self::assertSame(-3990, $this->reloadGym()->getReferenceAmountCents());
    }

    public function testAttachingByHandToASettledOccurrenceIsRefused(): void
    {
        /** @var Transaction $settled */
        $settled = $this->getFixture('feb_gym');
        $this->attach->execute($this->user());
        $intruder = $this->line(-3000, '2027-02-03');

        $this->expectException(RecurringAttachmentException::class);
        $this->expectExceptionMessage('already settled');

        try {
            $this->attach->attachByHand($intruder, $this->gym());
        } finally {
            self::assertNotNull($this->reload($settled)->getRecurringOperation());
        }
    }

    public function testAttachingByHandToADayThatIsNoDueDateIsRefused(): void
    {
        $line = $this->line(-3000, '2027-05-02');

        $this->expectException(RecurringAttachmentException::class);
        $this->expectExceptionMessage('the nearest is 2027-05-01');

        $this->attach->attachByHand($line, $this->gym(), new \DateTimeImmutable('2027-05-02'));
    }

    public function testAttachingByHandAcrossAccountsIsRefused(): void
    {
        $line = $this->line(-3000, '2027-05-01', 'savings');

        $this->expectException(RecurringAttachmentException::class);

        $this->attach->attachByHand($line, $this->gym());
    }

    public function testAnImportedStatementAttachesItsLines(): void
    {
        /** @var Account $checking */
        $checking = $this->getFixture('checking');

        self::getContainer()->get(ImportStatement::class)->execute($checking, [
            new StatementRow(new \DateTimeImmutable('2027-05-02'), 'PRLV CLUB FORME MAI', -3000, 'EUR', 1, counterpartyName: 'Club Forme'),
        ]);

        $this->em->clear();
        $imported = $this->em->getRepository(Transaction::class)->findOneBy(['label' => 'PRLV CLUB FORME MAI']);
        self::assertNotNull($imported);
        self::assertSame('2027-05-01', $imported->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertSame(RecurringLinkSource::Auto, $imported->getRecurringSource());
    }

    public function testARejectedDebitFreesItsOccurrenceForThePaymentPresentedAgain(): void
    {
        /** @var Transaction $debit */
        $debit = $this->getFixture('feb_gym');
        $this->attach->execute($this->user());

        $this->bus()->dispatch(new CreateTransactionCommand(
            userId: (string) $this->user()->getId(),
            accountId: (string) $this->gym()->getAccount()->getId(),
            amountCents: 3000,
            label: 'REJET PRLV CLUB FORME',
            bookedAt: '2027-02-04',
            status: 'spent',
            currency: 'EUR',
            isExceptional: false,
        ));

        $rejected = $this->reload($debit);
        self::assertSame(TransferKind::Rejected, $rejected->getTransferKind());
        self::assertNull($rejected->getRecurringOperation());

        $retry = $this->line(-3000, '2027-02-05');
        $this->attach->execute($this->user());

        self::assertSame('2027-02-01', $this->reload($retry)->getRecurringOccurrenceOn()?->format('Y-m-d'));
    }

    private function bus(): MessageBusInterface
    {
        /** @var MessageBusInterface $bus */
        $bus = self::getContainer()->get(MessageBusInterface::class);

        return $bus;
    }

    public function testALineIsAttachedAsItLands(): void
    {
        /** @var Account $checking */
        $checking = $this->getFixture('checking');
        $line = (new Transaction())
            ->setUser($this->user())
            ->setAccount($checking)
            ->setAmountCents(-3000)
            ->setBookedAt(new \DateTimeImmutable('2027-05-03'))
            ->setLabel('CB CLUB FORME')
            ->setCounterpartyName('Club Forme');

        $match = $this->attach->attachFor($line);

        self::assertNotNull($match);
        self::assertTrue($match->attached);
        self::assertSame('2027-05-01', $line->getRecurringOccurrenceOn()?->format('Y-m-d'));
        self::assertSame(CategorySource::Series, $line->getCategorySource());
    }
}
