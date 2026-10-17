<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Core\Entity\User;
use Maggie\Finance\Entity\Account;
use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\RecurrencePeriod;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\UseCase\ApplyEnvelopeAdjustments;
use Maggie\Finance\UseCase\GetUpcomingProjection;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The week and the month after the day read, projected with the envelopes as
 * they stand (MAG-463). Read on Friday 2026-10-16: the week is 10-19 → 10-25,
 * the month is November. Exemption: N/A — DB state, Mercure and Elasticsearch,
 * it writes nothing (the one write, the adjustment, is ApplyEnvelopeAdjustments').
 */
class GetUpcomingProjectionTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringProjectionsWorld;

    private const string TODAY = '2026-10-16';

    private GetUpcomingProjection $projection;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->projection = self::getContainer()->get(GetUpcomingProjection::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('upcoming_projection.yaml');
    }

    /** @return array<string, mixed> */
    private function read(string $as = 'test_user'): array
    {
        return $this->projection->execute($this->getFixture($as), new \DateTimeImmutable(self::TODAY));
    }

    /** @return array<string, mixed> */
    private function envelope(array $month, string $category): array
    {
        foreach ($month['envelopes'] as $line) {
            if ($line['categoryName'] === $category) {
                return $line;
            }
        }
        self::fail("No envelope line for {$category}");
    }

    private function plan(string $label, int $amountCents, string $bookedAt, string $categoryRef, TransactionStatus $status = TransactionStatus::Planned, TransferKind $kind = TransferKind::None): Transaction
    {
        $account = $this->getFixture('checking');
        $category = $this->getFixture($categoryRef);
        \assert($account instanceof Account && $category instanceof Category);

        $transaction = (new Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->qualify(Qualification::none($category), null)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel($label)
            ->setStatus($status)
            ->setTransferKind($kind);

        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);

        return $transaction;
    }

    public function testTheWeekAndTheMonthAfterTheDayRead(): void
    {
        $data = $this->read();

        self::assertSame('2026-10-16', $data['today']);
        self::assertSame('2026-10-19', $data['week']['from']);
        self::assertSame('2026-10-26', $data['week']['until']);
        self::assertCount(7, $data['week']['days']);
        self::assertSame('2026-11-01', $data['month']['from']);
        self::assertSame('2026-12-01', $data['month']['until']);
        self::assertSame('2026-11', $data['month']['month']);
        self::assertCount(30, $data['month']['days']);
    }

    public function testARecurringOperationExpectedNextMonthAppearsOnItsDate(): void
    {
        $this->series('Loyer', -90000, '2026-07-05');
        $this->series('Salaire ACME', 250000, '2026-07-25', categoryRef: 'salary');

        $month = $this->read()['month'];

        self::assertSame(
            [['2026-11-05', 'Loyer', -90000, 'recurring']],
            array_map(static fn (array $e): array => [$e['date'], $e['label'], $e['amountCents'], $e['source']], $month['expenses']),
        );
        self::assertSame(
            [['2026-11-25', 'Salaire ACME', 250000]],
            array_map(static fn (array $e): array => [$e['date'], $e['label'], $e['amountCents']], $month['incomes']),
        );
        self::assertSame(250000, $month['incomeCents']);
        self::assertSame(90000, $month['expenseCents']);

        $days = array_column($month['days'], null, 'date');
        self::assertSame(90000, $days['2026-11-05']['expenseCents']);
        self::assertSame(250000, $days['2026-11-25']['incomeCents']);
        self::assertSame(0, $days['2026-11-06']['expenseCents']);
    }

    public function testTheWeekGathersWhatIsPlannedInIt(): void
    {
        $this->plan('Cinéma', -3500, '2026-10-21', 'leisure');
        $this->plan('Remboursement', 1200, '2026-10-22', 'groceries', TransactionStatus::Committed);
        $this->plan('Hors semaine', -9900, '2026-10-26', 'leisure');
        $this->plan('Aujourd’hui', -800, '2026-10-16', 'leisure');
        $this->plan('Épargne', -20000, '2026-10-23', 'leisure', kind: TransferKind::Internal);

        $week = $this->read()['week'];

        self::assertSame(['Cinéma'], array_column($week['expenses'], 'label'));
        self::assertSame('planned', $week['expenses'][0]['source']);
        self::assertSame(['Remboursement'], array_column($week['incomes'], 'label'));
        self::assertSame(1200, $week['incomeCents']);
        self::assertSame(3500, $week['expenseCents']);
        self::assertSame(-2300, $week['netCents']);
    }

    public function testAnOccurrenceAlreadyAttachedToALineIsCountedOnce(): void
    {
        $rent = $this->series('Loyer', -90000, '2026-07-05');
        $this->settle($rent, '2026-11-05', -90000, TransactionStatus::Planned);

        $month = $this->read()['month'];

        self::assertCount(1, $month['expenses']);
        self::assertSame(90000, $month['expenseCents']);
    }

    public function testTheEnvelopesAreTheOnesWrittenForNextMonthOrRolledOver(): void
    {
        $month = $this->read()['month'];

        self::assertSame(['Courses', 'Logement', 'Loisirs'], array_column($month['envelopes'], 'categoryName'));

        $groceries = $this->envelope($month, 'Courses');
        self::assertNotNull($groceries['envelopeId']);
        self::assertSame(40000, $groceries['amountCents']);

        // No envelope for November: October's, rolled over, with no id yet.
        $leisure = $this->envelope($month, 'Loisirs');
        self::assertNull($leisure['envelopeId']);
        self::assertSame(20000, $leisure['amountCents']);

        self::assertSame(40000 + 20000 + 80000, $month['envelopesCents']);
    }

    public function testWhatIsCommittedInAnEnvelopeIsPartOfItAndOnlyOverflowsWhenBigger(): void
    {
        $this->series('Loyer', -90000, '2026-07-05');
        $this->plan('Marché', -12000, '2026-11-10', 'groceries');

        $month = $this->read()['month'];

        $housing = $this->envelope($month, 'Logement');
        self::assertSame(80000, $housing['amountCents']);
        self::assertSame(90000, $housing['committedCents']);
        self::assertSame(90000, $housing['projectedCents']);
        self::assertTrue($housing['isOvercommitted']);

        $groceries = $this->envelope($month, 'Courses');
        self::assertSame(12000, $groceries['committedCents']);
        self::assertSame(40000, $groceries['projectedCents']);
        self::assertFalse($groceries['isOvercommitted']);
    }

    public function testWhatIsCommittedOutsideEveryEnvelopeIsAddedOnTop(): void
    {
        $this->series('Assurance auto', -1200, '2026-07-12', categoryRef: 'insurance');

        $month = $this->read()['month'];

        self::assertSame(1200, $month['unbudgetedCents']);
        self::assertSame(140000 + 1200, $month['projectedExpenseCents']);
    }

    public function testTheRemainderIsTheExpectedIncomeLessTheProjectedSpending(): void
    {
        $this->series('Loyer', -90000, '2026-07-05');
        $this->series('Salaire ACME', 250000, '2026-07-25', categoryRef: 'salary');
        $this->series('Assurance auto', -1200, '2026-07-12', categoryRef: 'insurance');

        $month = $this->read()['month'];

        // Logement 900 (the rent overflows its 800), Courses 400, Loisirs 200, Assurance 12.
        self::assertSame(90000 + 40000 + 20000, $month['envelopesCents']);
        self::assertSame(151200, $month['projectedExpenseCents']);
        self::assertSame(250000 - 151200, $month['remainingCents']);
    }

    public function testAnEnvelopeAdjustedForNextMonthChangesTheRemainder(): void
    {
        $this->series('Salaire ACME', 250000, '2026-07-25', categoryRef: 'salary');
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);
        $before = $this->read()['month'];

        self::getContainer()->get(ApplyEnvelopeAdjustments::class)->execute($user, new \DateTimeImmutable('2026-10-01'), [
            ['categoryId' => (string) $this->getFixture('groceries')->getId(), 'amountCents' => 60000, 'seasonal' => false],
        ]);
        $this->em->clear();
        $after = $this->read()['month'];

        self::assertSame(40000, $this->envelope($before, 'Courses')['amountCents']);
        self::assertSame(60000, $this->envelope($after, 'Courses')['amountCents']);
        self::assertSame($before['remainingCents'] - 20000, $after['remainingCents']);
    }

    public function testAWeeklySeriesLandsOnEveryWeekOfTheMonth(): void
    {
        $this->series('Marché', -2000, '2026-10-03', categoryRef: 'groceries', period: RecurrencePeriod::Weekly);

        $month = $this->read()['month'];

        self::assertSame(['2026-11-07', '2026-11-14', '2026-11-21', '2026-11-28'], array_column($month['expenses'], 'date'));
        self::assertSame(8000, $this->envelope($month, 'Courses')['committedCents']);
    }

    public function testNothingOfAnotherUserEnters(): void
    {
        $this->series('Loyer', -90000, '2026-07-05');

        $other = $this->read('other_user');

        self::assertSame([], $other['month']['expenses']);
        self::assertSame([], $other['month']['envelopes']);
        self::assertSame(0, $other['month']['remainingCents']);
        self::assertSame([], $other['week']['expenses']);
    }

    public function testAYearEndReadsNextYear(): void
    {
        $data = $this->projection->execute($this->getFixture('test_user'), new \DateTimeImmutable('2026-12-30'));

        self::assertSame('2027-01', $data['month']['month']);
        self::assertSame('2027-01-04', $data['week']['from']);
    }
}
