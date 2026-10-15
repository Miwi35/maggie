<?php

namespace Maggie\Finance\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Enum\TransactionStatus;
use Maggie\Finance\UseCase\GetAnnualPlan;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * The debits the confirmed recurring series still expect over the planned
 * year enter the annual plan by category, in `recurringCents` (MAG-307):
 * `decidedCents` includes them, each occurrence is counted once, and the rule
 * « decided, otherwise what the year behind cost » is untouched.
 *
 * The rent is 900 € on the 5th, anchored on 2026-10-05; the year planned is 2027.
 */
class GetAnnualPlanRecurringTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use RecurringProjectionsWorld;

    private GetAnnualPlan $plan;
    private EntityManagerInterface $em;

    protected function setUp(): void
    {
        self::bootKernel();

        $this->plan = self::getContainer()->get(GetAnnualPlan::class);
        $this->em = self::getContainer()->get('doctrine.orm.entity_manager');

        $this->loadFixtures('recurring_projections.yaml');
    }

    /** @return array<string, mixed> */
    private function row(string $categoryRef, string $as = 'test_user'): array
    {
        $plan = $this->plan->execute($this->getFixture($as), 2027);
        $id = (string) $this->getFixture($categoryRef)->getId();

        foreach ($plan['categories'] as $category) {
            if ($category['categoryId'] === $id) {
                return $category;
            }
        }

        self::fail("The plan has no row for {$categoryRef}");
    }

    public function testEveryOccurrenceOfTheYearIsCountedOncePerCategory(): void
    {
        $this->series('Loyer', -90000, '2026-10-05');

        $housing = $this->row('housing');

        self::assertSame(12 * 90000, $housing['recurringCents']);
        self::assertSame(0, $housing['plannedCents']);
        self::assertSame(12 * 90000, $housing['decidedCents']);
        self::assertSame(12 * 90000, $housing['suggestedCents']);
        self::assertSame('Logement', $housing['categoryName']);
    }

    public function testAnAttachedOccurrenceIsCountedOnlyByItsLine(): void
    {
        $rent = $this->series('Loyer', -90000, '2026-10-05');
        // February entered in advance, January already booked.
        $this->settle($rent, '2027-02-05', -90000, TransactionStatus::Planned);
        $this->settle($rent, '2027-01-05', -90000, TransactionStatus::Spent);

        $housing = $this->row('housing');

        self::assertSame(10 * 90000, $housing['recurringCents']);
        self::assertSame(90000, $housing['plannedCents']);
        self::assertSame(11 * 90000, $housing['decidedCents'], 'the booked January is history, the planned February is counted once');
    }

    public function testDecidedAddsPlannedCommittedAndRecurring(): void
    {
        $rent = $this->series('Loyer', -90000, '2026-10-05');
        $this->settle($rent, '2027-02-05', -90000, TransactionStatus::Committed);
        $this->settle($rent, '2027-03-05', -90000, TransactionStatus::Planned);

        $housing = $this->row('housing');

        self::assertSame(90000, $housing['committedCents']);
        self::assertSame(90000, $housing['plannedCents']);
        self::assertSame(10 * 90000, $housing['recurringCents']);
        self::assertSame(
            $housing['plannedCents'] + $housing['committedCents'] + $housing['recurringCents'],
            $housing['decidedCents'],
        );
        self::assertSame(12 * 90000, $housing['suggestedCents']);
    }

    public function testAnEndedSeriesAndAnIncomeSeriesAreNotInTheSpending(): void
    {
        $this->series('Ancien loyer', -90000, '2026-10-05', endsOn: '2027-03-31');
        $this->series('Loyer perçu', 40000, '2026-10-05', categoryRef: 'rentes');

        self::assertSame(3 * 90000, $this->row('housing')['recurringCents']);

        $plan = $this->plan->execute($this->getFixture('test_user'), 2027);
        self::assertNotContains((string) $this->getFixture('rentes')->getId(), array_column($plan['categories'], 'categoryId'));
    }

    public function testTheYearBehindStillSuggestsWhenNothingIsDecided(): void
    {
        $this->spend('leisure', -12000, '2026-06-10');
        $this->series('Loyer', -90000, '2026-10-05');

        $leisure = $this->row('leisure');

        self::assertSame(0, $leisure['recurringCents']);
        self::assertSame(0, $leisure['decidedCents']);
        self::assertSame(12000, $leisure['suggestedCents']);
    }

    public function testTheTotalsFollow(): void
    {
        $this->series('Loyer', -90000, '2026-10-05');

        $plan = $this->plan->execute($this->getFixture('test_user'), 2027);

        self::assertSame(12 * 90000, $plan['totalDecidedCents']);
        self::assertSame(12 * 90000, $plan['totalSuggestedCents']);
    }

    public function testAnotherUsersSeriesIsNotInThePlan(): void
    {
        $this->series('Loyer de l\'autre', -90000, '2026-10-05', 'other_checking', 'other_housing');

        $plan = $this->plan->execute($this->getFixture('test_user'), 2027);

        self::assertSame([], $plan['categories']);
        self::assertSame(0, $plan['totalDecidedCents']);
    }

    private function spend(string $categoryRef, int $amountCents, string $bookedAt): void
    {
        $account = $this->getFixture('checking');
        $transaction = (new \Maggie\Finance\Entity\Transaction())
            ->setUser($account->getUser())
            ->setAccount($account)
            ->qualify(Qualification::none($this->getFixture($categoryRef)), null)
            ->setAmountCents($amountCents)
            ->setBookedAt(new \DateTimeImmutable($bookedAt))
            ->setLabel('Dépense');
        $this->em->persist($transaction);
        $this->flushWithoutTransactionEffects($this->em);
    }
}
