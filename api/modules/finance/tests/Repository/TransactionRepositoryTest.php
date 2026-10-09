<?php

namespace Maggie\Finance\Tests\Repository;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferSource;
use Maggie\Finance\Repository\TransactionRepository;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * An internal transfer counts nowhere — one case per aggregate.
 *
 * `sumConsumedBetween` alone is read by the lifestyle measured, the saving
 * capacity, the monthly review and the day-over-year comparison of the score:
 * one wrong query makes four figures wrong. The raw lists and the import's
 * de-duplication keep seeing it: it is still a line of the bank statement.
 */
class TransactionRepositoryTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    private const string FROM = '2026-09-01';
    private const string UNTIL = '2026-10-01';

    private TransactionRepository $repository;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');
        $this->repository = $this->em->getRepository(Transaction::class);

        $this->loadFixtures('transfer_exclusion.yaml');
    }

    private function user(): User
    {
        /** @var User $user */
        $user = $this->getFixture('test_user');

        return $user;
    }

    private function category(string $ref): Category
    {
        /** @var Category $category */
        $category = $this->getFixture($ref);

        return $category;
    }

    private function add(
        string $accountRef,
        int $amountCents,
        string $bookedAt,
        string $label = 'Mouvement',
        ?string $categoryRef = null,
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

        if (null !== $categoryRef) {
            $transaction->setCategory($this->category($categoryRef));
        }

        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);

        return $transaction;
    }

    /**
     * The movement of the owner's report: 3 000 € leaving the Livret, the same
     * 3 000 € landing on the Courant, marked as the one transfer it is.
     *
     * @return array{Transaction, Transaction}
     */
    private function addPairedTransfer(
        int $amountCents = 300000,
        ?string $categoryRef = null,
        TransactionStatus $status = TransactionStatus::Spent,
    ): array {
        $out = $this->add('savings', -$amountCents, '2026-09-12', 'Virement vers Courant', $categoryRef, $status);
        $in = $this->add('checking', $amountCents, '2026-09-13', 'Virement du Livret', $categoryRef, $status);

        $out->markAsInternalTransfer($in, TransferSource::Auto);
        $this->em->flush();

        return [$out, $in];
    }

    private function from(): \DateTimeImmutable
    {
        return new \DateTimeImmutable(self::FROM);
    }

    private function until(): \DateTimeImmutable
    {
        return new \DateTimeImmutable(self::UNTIL);
    }

    public function testTheOwnersCaseLifestyleCountsTheExpenseAndNotTheTransfer(): void
    {
        $this->addPairedTransfer();
        $this->add('checking', -8000, '2026-09-14', 'Supermarché');

        self::assertSame(8000, $this->repository->sumConsumedBetween($this->user(), $this->from(), $this->until()));
    }

    public function testATransferLeavesTheMonthlyFlows(): void
    {
        $this->addPairedTransfer();
        $this->add('checking', -8000, '2026-09-14', 'Supermarché');
        $this->add('checking', 495000, '2026-09-25', 'Salaire');

        $flows = $this->repository->sumMonthlyFlowsBetween($this->user(), $this->from(), $this->until());

        self::assertSame(['incomeCents' => 495000, 'expenseCents' => 8000], $flows['2026-09']);
    }

    public function testATransferLeavesTheSpendingByCategory(): void
    {
        $this->addPairedTransfer(categoryRef: 'leisure');
        $this->add('checking', -8000, '2026-09-14', 'Cinéma', 'leisure');

        $spending = $this->repository->sumSpendingByCategoryBetween($this->user(), $this->from(), $this->until());

        self::assertCount(1, $spending);
        self::assertSame(8000, $spending[0]['spentCents']);
    }

    public function testATransferLeavesTheEnvelopeOfItsCategory(): void
    {
        $this->addPairedTransfer(categoryRef: 'leisure');
        $this->add('checking', -8000, '2026-09-14', 'Cinéma', 'leisure');

        $byStatus = $this->repository->sumByStatusForCategoryBetween(
            $this->user(),
            $this->category('leisure'),
            $this->from(),
            $this->until(),
        );

        self::assertSame(8000, $byStatus[TransactionStatus::Spent->value]);
        self::assertSame(8000, $this->repository->sumSpentForCategoryBetween(
            $this->user(),
            $this->category('leisure'),
            $this->from(),
            $this->until(),
        ));
    }

    public function testATransferLeavesTheMonthlyReviewQueue(): void
    {
        $this->addPairedTransfer();
        $this->add('checking', -8000, '2026-09-14', 'Supermarché');

        $reviewable = $this->repository->findReviewableBetween($this->user(), $this->from(), $this->until());

        self::assertSame(['Supermarché'], array_map(static fn (Transaction $t) => $t->getLabel(), $reviewable));
    }

    public function testATransferLeavesTheNotableDebits(): void
    {
        $this->addPairedTransfer(categoryRef: 'leisure');
        $this->add('checking', -20000, '2026-09-14', 'Week-end', 'leisure');

        $notable = $this->repository->findNotableDebitsBetween($this->user(), $this->from(), $this->until(), 10000);

        self::assertSame(['Week-end'], array_map(static fn (Transaction $t) => $t->getLabel(), $notable));
    }

    public function testATransferLeavesThePlans(): void
    {
        $this->addPairedTransfer(categoryRef: 'leisure', status: TransactionStatus::Committed);
        $this->add('checking', -20000, '2026-09-14', 'Vacances', 'leisure', TransactionStatus::Planned);

        $plans = $this->repository->findPlansBetween($this->user(), $this->from(), $this->until());

        self::assertSame(['Vacances'], array_map(static fn (Transaction $t) => $t->getLabel(), $plans));
    }

    public function testATransferLeavesTheRentes(): void
    {
        [$out, $in] = $this->addPairedTransfer(categoryRef: 'rente');
        $this->add('checking', 45000, '2026-09-05', 'Loyer de septembre', 'rente');

        $income = $this->repository->sumPassiveIncomeByCategoryBetween($this->user(), $this->from(), $this->until());

        self::assertCount(1, $income);
        self::assertSame(45000, $income[0]['incomeCents']);
        self::assertTrue($in->isInternalTransfer(), 'the credit of the transfer is the one that would inflate the rente');
        self::assertTrue($out->isInternalTransfer());
    }

    public function testARawListStillShowsATransfer(): void
    {
        [$out, $in] = $this->addPairedTransfer();

        $labels = array_map(
            static fn (Transaction $t) => $t->getLabel(),
            $this->repository->findByUser($this->user()),
        );

        self::assertContains($out->getLabel(), $labels);
        self::assertContains($in->getLabel(), $labels);
    }

    public function testTheImportStillSeesATransferAsAnAlreadyStoredLine(): void
    {
        [$out] = $this->addPairedTransfer();

        /** @var Account $savings */
        $savings = $this->getFixture('savings');

        self::assertSame(1, $this->repository->countMatching(
            $savings,
            $out->getBookedAt(),
            $out->getAmountCents(),
            $out->getLabel(),
        ));
    }

    public function testACandidateQueryKeepsTheWindowTheAccountsAndTheAmount(): void
    {
        $out = $this->add('savings', -300000, '2026-09-12', 'Virement sortant');
        $exact = $this->add('checking', 300000, '2026-09-13', 'Crédit exact');
        $this->add('checking', 300000, '2026-09-17', 'Trop loin');
        $this->add('savings', 300000, '2026-09-13', 'Même compte');
        $this->add('checking', 299900, '2026-09-13', 'Montant différent');

        $candidates = $this->repository->findTransferCandidates($out, 4);

        self::assertSame(
            [$exact->getLabel()],
            array_map(static fn (Transaction $t) => $t->getLabel(), $candidates),
        );
    }

    public function testThePairedAndTheHandJudgedLinesLeaveTheCandidates(): void
    {
        [$out] = $this->addPairedTransfer();
        $free = $this->add('checking', 300000, '2026-09-13', 'Crédit libre');
        $sealed = $this->add('checking', 300000, '2026-09-12', 'Jugé à la main');
        $sealed->setTransferSource(TransferSource::Manual);
        $this->em->flush();

        $another = $this->add('savings', -300000, '2026-09-12', 'Autre virement sortant');

        $candidates = array_map(
            static fn (Transaction $t) => $t->getLabel(),
            $this->repository->findTransferCandidates($another, 4),
        );

        self::assertSame([$free->getLabel()], $candidates);
        self::assertTrue($out->isInternalTransfer());
    }

    public function testTheUnpairedScanLeavesOutWhatIsPairedJudgedOrNotConsumedYet(): void
    {
        [$out, $in] = $this->addPairedTransfer();
        $ordinary = $this->add('checking', -8000, '2026-09-14', 'Supermarché');
        $planned = $this->add('checking', -5000, '2026-09-20', 'Prévu', null, TransactionStatus::Planned);
        $sealed = $this->add('checking', -6000, '2026-09-21', 'Jugé à la main');
        $sealed->setTransferSource(TransferSource::Manual);
        $this->em->flush();

        $labels = array_map(
            static fn (Transaction $t) => $t->getLabel(),
            $this->repository->findUnpairedForUser($this->user()),
        );

        self::assertSame([$ordinary->getLabel()], $labels);
        self::assertNotContains($out->getLabel(), $labels);
        self::assertNotContains($in->getLabel(), $labels);
        self::assertNotContains($planned->getLabel(), $labels);
        self::assertNotContains($sealed->getLabel(), $labels);
    }

    public function testTheUnpairedScanStopsAtTheDayItIsGiven(): void
    {
        $old = $this->add('checking', -8000, '2026-09-14', 'Vieux');
        $recent = $this->add('checking', -9000, (new \DateTimeImmutable('midnight -1 day'))->format('Y-m-d'), 'Récent');

        $labels = array_map(
            static fn (Transaction $t) => $t->getLabel(),
            $this->repository->findUnpairedForUser($this->user(), new \DateTimeImmutable('midnight -7 days')),
        );

        self::assertSame([$recent->getLabel()], $labels);
        self::assertNotContains($old->getLabel(), $labels);
    }
}
