<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Entity\Project;
use Maggie\Finance\Entity\ProjectFundingSource;
use Maggie\Finance\Entity\ProjectInvestment;
use Maggie\Finance\Enum\FundingSourceKind;
use Maggie\Finance\Enum\ProjectDateMode;
use Maggie\Finance\Service\LoanSchedule;
use Maggie\Finance\Service\ProjectPlanner;
use PHPUnit\Framework\TestCase;

/**
 * The planning's rules (MAG-526) on in-memory projects. The current month is
 * 2026-10, M0; a net capacity of 500 €/month and a complete cushion unless
 * said otherwise.
 */
class ProjectPlannerTest extends TestCase
{
    private const COMPLETE = ['state' => 'complete', 'deficitCents' => 0, 'monthlyRechargeCents' => 0];

    private int $position = 0;

    protected function setUp(): void
    {
        $this->position = 0;
    }

    /** @param array{state: string, deficitCents: int, monthlyRechargeCents: int} $cushion */
    private function plan(array $projects, array $cushion = self::COMPLETE, int $capacity = 50000, int $reserve = 0, int $overspent = 0, bool $incomeKnown = true): array
    {
        return (new ProjectPlanner(new LoanSchedule()))->plan(
            new \DateTimeImmutable('2026-10-21 12:00:00'),
            $capacity,
            $incomeKnown,
            $cushion,
            $reserve,
            $projects,
            $overspent,
        );
    }

    private function project(string $name, int $cents, ProjectDateMode $mode = ProjectDateMode::None, ?string $on = null): Project
    {
        $project = (new Project())->setName($name)->setAmountCents($cents)->setPosition($this->position++)->setDateMode($mode);
        if (null !== $on) {
            $project->setTargetOn(new \DateTimeImmutable($on));
        }

        return $project;
    }

    private function source(Project $project, FundingSourceKind $kind, int $cents, ?Loan $loan = null): void
    {
        (new ProjectFundingSource())->setProject($project)->setKind($kind)->setAmountCents($cents)->setLoan($loan);
    }

    /** The month `$offset` months after 2026-10. */
    private static function m(int $offset): string
    {
        return (new \DateTimeImmutable('2026-10-01'))->modify(\sprintf('+%d months', $offset))->format('Y-m');
    }

    /** @return array<string, mixed> */
    private static function row(array $plan, string $name): array
    {
        foreach ($plan['projects'] as $row) {
            if ($name === $row['name']) {
                return $row;
            }
        }

        throw new \LogicException("No project {$name} in the planning.");
    }

    public function testTheShapeIs36MonthsFromTheCurrentOne(): void
    {
        $plan = $this->plan([]);

        self::assertSame('2026-10', $plan['startMonth']);
        self::assertSame(36, $plan['horizonMonths']);
        self::assertCount(36, $plan['months']);
        self::assertSame(self::m(35), $plan['months'][35]['month']);
        self::assertSame(50000, $plan['netCapacityCents']);
        self::assertTrue($plan['isIncomeKnown']);
        self::assertSame(['state' => 'complete', 'completeMonth' => null], $plan['cushion']);
        self::assertSame(['month' => '2026-10', 'capacityCents' => 50000, 'cushionCents' => 0, 'salaryCents' => 0, 'savingsCents' => 0, 'capitalCents' => 0, 'loanPaymentsCents' => 0, 'rentCents' => 0, 'resaleCents' => 0, 'isOverCapacity' => false], $plan['months'][0]);
    }

    public function testProjectsAreBoughtInPriorityOrderOnTheCapacity(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000), $this->project('Japon', 300000)]);

        self::assertSame(self::m(2), self::row($plan, 'Vélo')['purchaseMonth']);
        self::assertSame(self::m(8), self::row($plan, 'Japon')['purchaseMonth']);
        // The surplus of the month Vélo is gathered in goes straight to Japon.
        self::assertSame(self::m(0), self::row($plan, 'Vélo')['savingFromMonth']);
        self::assertSame(self::m(2), self::row($plan, 'Japon')['savingFromMonth']);
        self::assertSame(50000, $plan['months'][2]['salaryCents']);
        self::assertFalse(self::row($plan, 'Vélo')['isBeyondHorizon']);
        self::assertSame(['Vélo', 'Japon'], array_column($plan['projects'], 'name'));
    }

    public function testNothingStartsWhileTheCushionIsBeingBuilt(): void
    {
        $plan = $this->plan(
            [$this->project('Vélo', 120000)],
            ['state' => 'building', 'deficitCents' => 100000, 'monthlyRechargeCents' => 20000],
        );

        self::assertSame(['state' => 'building', 'completeMonth' => self::m(1)], $plan['cushion']);
        self::assertSame([50000, 50000, 0], array_column(\array_slice($plan['months'], 0, 3), 'cushionCents'));
        self::assertSame([0, 0, 50000], array_column(\array_slice($plan['months'], 0, 3), 'capacityCents'));
        self::assertSame(self::m(4), self::row($plan, 'Vélo')['purchaseMonth']);
    }

    public function testAProjectPaidForByItsSourcesStillWaitsForTheCushion(): void
    {
        $trip = $this->project('Week-end', 20000);
        $this->source($trip, FundingSourceKind::Capital, 20000);

        $plan = $this->plan([$trip], ['state' => 'building', 'deficitCents' => 100000, 'monthlyRechargeCents' => 0]);

        self::assertSame(self::m(1), self::row($plan, 'Week-end')['purchaseMonth']);
        self::assertSame(20000, $plan['months'][1]['capitalCents']);
    }

    public function testACushionNeverFilledWithinTheHorizonHoldsEverythingBack(): void
    {
        $plan = $this->plan(
            [$this->project('Vélo', 120000)],
            ['state' => 'building', 'deficitCents' => 5000000, 'monthlyRechargeCents' => 0],
        );

        self::assertNull($plan['cushion']['completeMonth']);
        self::assertNull(self::row($plan, 'Vélo')['purchaseMonth']);
        self::assertTrue(self::row($plan, 'Vélo')['isBeyondHorizon']);
    }

    public function testARechargingCushionLowersTheCapacityForAWhile(): void
    {
        $plan = $this->plan([], ['state' => 'recharging', 'deficitCents' => 60000, 'monthlyRechargeCents' => 20000]);

        self::assertSame([30000, 30000, 30000, 50000], array_column(\array_slice($plan['months'], 0, 4), 'capacityCents'));
        self::assertSame([20000, 20000, 20000, 0], array_column(\array_slice($plan['months'], 0, 4), 'cushionCents'));
        self::assertSame(self::m(2), $plan['cushion']['completeMonth']);
    }

    public function testAFixedDateProjectKeepsItsDateAndGoesFirst(): void
    {
        $move = $this->project('Déménagement', 240000, ProjectDateMode::Fixed, '2027-03-15');
        $plan = $this->plan([$move, $this->project('Vélo', 120000)]);

        self::assertSame(self::m(5), self::row($plan, 'Déménagement')['purchaseMonth']);
        self::assertSame(self::m(7), self::row($plan, 'Vélo')['purchaseMonth']);
        // 2 400 € over M0 to M5 is 400 € a month; the 100 € left start the bike.
        self::assertSame([50000, 50000], [$plan['months'][0]['salaryCents'], $plan['months'][5]['salaryCents']]);
        self::assertFalse($plan['months'][0]['isOverCapacity']);
    }

    public function testAProjectDependingOnAFixedOneIsNotBoughtBeforeIt(): void
    {
        $move = $this->project('Déménagement', 240000, ProjectDateMode::Fixed, '2027-03-15');
        $sofa = $this->project('Canapé', 30000)->setDependsOn([$move]);

        $plan = $this->plan([$move, $sofa]);

        self::assertSame(self::m(5), self::row($plan, 'Canapé')['purchaseMonth']);
        // Gathered long before, though: the waiting is on the purchase only.
        self::assertSame(self::m(0), self::row($plan, 'Canapé')['savingFromMonth']);
    }

    public function testADependencyOnALowerPriorityProjectWaitsForItToo(): void
    {
        $bike = $this->project('Vélo', 120000);
        $helmet = $this->project('Casque', 10000)->setDependsOn([$bike]);
        $plan = $this->plan([$helmet, $bike]);

        self::assertSame(self::m(2), self::row($plan, 'Vélo')['purchaseMonth']);
        self::assertSame(self::m(2), self::row($plan, 'Casque')['purchaseMonth']);
    }

    public function testADateTooHeavyForTheCapacityIsKeptAndFlagged(): void
    {
        $plan = $this->plan([$this->project('Mariage', 600000, ProjectDateMode::Fixed, '2026-12-05')]);

        self::assertSame(self::m(2), self::row($plan, 'Mariage')['purchaseMonth']);
        foreach (\array_slice($plan['months'], 0, 3) as $month) {
            self::assertSame(200000, $month['salaryCents']);
            self::assertTrue($month['isOverCapacity']);
        }
        self::assertFalse($plan['months'][3]['isOverCapacity']);
    }

    public function testADeadlineMissedIsReportedWithItsDelay(): void
    {
        $plan = $this->plan([
            $this->project('Vélo', 120000),
            $this->project('Japon', 300000, ProjectDateMode::Deadline, '2027-04-30'),
        ]);

        $trip = self::row($plan, 'Japon');
        self::assertSame(self::m(8), $trip['purchaseMonth']);
        self::assertTrue($trip['isDeadlineMissed']);
        self::assertSame(2, $trip['delayMonths']);
        self::assertFalse(self::row($plan, 'Vélo')['isDeadlineMissed']);
    }

    public function testADeadlineMetIsNotMissed(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000, ProjectDateMode::Deadline, '2027-04-30')]);

        self::assertFalse(self::row($plan, 'Vélo')['isDeadlineMissed']);
        self::assertSame(0, self::row($plan, 'Vélo')['delayMonths']);
    }

    public function testAProjectBeyondTheHorizonHasNoPurchaseMonth(): void
    {
        $plan = $this->plan([$this->project('Maison', 5000000, ProjectDateMode::Deadline, '2028-01-31')]);

        $house = self::row($plan, 'Maison');
        self::assertNull($house['purchaseMonth']);
        self::assertTrue($house['isBeyondHorizon']);
        self::assertTrue($house['isDeadlineMissed']);
        self::assertSame(36 - 15, $house['delayMonths']);
        self::assertNull($house['loan']);
    }

    public function testSavingsOutsideTheCushionAreSetAsideByPriority(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000), $this->project('Japon', 300000)], reserve: 80000);

        $bike = self::row($plan, 'Vélo');
        self::assertSame(80000, $bike['gatheredCents']);
        self::assertSame(self::m(0), $bike['purchaseMonth']);
        self::assertSame(0, self::row($plan, 'Japon')['gatheredCents']);
        self::assertSame(80000, $plan['months'][0]['savingsCents']);
        // 400 € of M0 for the bike, the 100 € left start Japon.
        self::assertSame(50000, $plan['months'][0]['salaryCents']);
    }

    public function testASurplusOfSavingsOverflowsToTheNextProject(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000), $this->project('Japon', 300000)], reserve: 200000);

        self::assertSame([120000, 80000], array_column($plan['projects'], 'gatheredCents'));
    }

    public function testASavingsAndACapitalSourceCountTheMonthOfThePurchase(): void
    {
        $car = $this->project('Voiture', 300000);
        $this->source($car, FundingSourceKind::Savings, 100000);
        $this->source($car, FundingSourceKind::Capital, 50000);

        $plan = $this->plan([$car]);

        // 1 500 € left on the salary: M2.
        self::assertSame(self::m(2), self::row($plan, 'Voiture')['purchaseMonth']);
        self::assertSame([0, 0, 100000], array_column(\array_slice($plan['months'], 0, 3), 'savingsCents'));
        self::assertSame([0, 0, 50000], array_column(\array_slice($plan['months'], 0, 3), 'capitalCents'));
    }

    public function testAProjectedLoanLowersTheCapacityFromTheMonthAfterThePurchase(): void
    {
        // 10 000 € over 24 months at 4 %: 434,25 € a month.
        $loan = (new Loan())->setName('Voiture · emprunt')
            ->setPrincipalRemainingCents(1000000)
            ->setMonthlyPaymentCents(LoanSchedule::annuity(1000000, 0.04 / 12, 24))
            ->setAnnualRateBasisPoints(400)
            ->setDurationMonths(24)
            ->setProjected(true);
        $car = $this->project('Voiture', 1200000);
        $this->source($car, FundingSourceKind::Loan, 1000000, $loan);

        $plan = $this->plan([$car]);

        // 2 000 € on the salary: bought in M3, the loan runs M4 to M27.
        $row = self::row($plan, 'Voiture');
        self::assertSame(self::m(3), $row['purchaseMonth']);
        self::assertSame(['startMonth' => self::m(4), 'endMonth' => self::m(27), 'monthlyPaymentCents' => 43425], $row['loan']);
        self::assertSame([50000, 0], [$plan['months'][3]['capacityCents'], $plan['months'][3]['loanPaymentsCents']]);
        self::assertSame([50000 - 43425, 43425], [$plan['months'][4]['capacityCents'], $plan['months'][4]['loanPaymentsCents']]);
        // The last instalment absorbs the rounding.
        self::assertGreaterThan(0, $plan['months'][27]['loanPaymentsCents']);
        self::assertLessThanOrEqual(43425, $plan['months'][27]['loanPaymentsCents']);
        self::assertSame(0, $plan['months'][28]['loanPaymentsCents']);
        self::assertSame(50000, $plan['months'][28]['capacityCents']);
    }

    public function testALoanSlowsTheProjectsBoughtAfterIt(): void
    {
        $loan = (new Loan())->setName('Voiture · emprunt')
            ->setPrincipalRemainingCents(1000000)
            ->setMonthlyPaymentCents(43425)
            ->setAnnualRateBasisPoints(400)
            ->setDurationMonths(24)
            ->setProjected(true);
        $car = $this->project('Voiture', 1000000);
        $this->source($car, FundingSourceKind::Loan, 1000000, $loan);
        $bike = $this->project('Vélo', 120000);

        $plan = $this->plan([$car, $bike]);

        // The car is bought in M0 on its loan alone; the bike gets 500 € in M0,
        // then 65,75 € a month while the loan runs.
        self::assertSame(self::m(0), self::row($plan, 'Voiture')['purchaseMonth']);
        self::assertSame(self::m(0), self::row($plan, 'Vélo')['savingFromMonth']);
        self::assertSame(self::m(11), self::row($plan, 'Vélo')['purchaseMonth']);
    }

    public function testWhatWasOverspentThisMonthShiftsTheProjects(): void
    {
        $project = $this->project('Meuble', 150000);

        self::assertSame(self::m(2), self::row($this->plan([$project]), 'Meuble')['purchaseMonth']);

        $plan = $this->plan([$project], overspent: 10000);
        self::assertSame(self::m(3), self::row($plan, 'Meuble')['purchaseMonth']);
        self::assertSame([40000, 50000], [$plan['months'][0]['capacityCents'], $plan['months'][1]['capacityCents']]);
    }

    public function testWithoutAKnownIncomeThereIsNoCapacity(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000)], capacity: -20000, incomeKnown: false);

        self::assertFalse($plan['isIncomeKnown']);
        self::assertSame(0, $plan['netCapacityCents']);
        self::assertSame(0, $plan['months'][0]['capacityCents']);
        self::assertNull(self::row($plan, 'Vélo')['purchaseMonth']);
    }

    public function testANegativeCapacityGivesNothingToTheProjects(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000)], capacity: -20000);

        self::assertSame(0, $plan['months'][5]['capacityCents']);
        self::assertTrue(self::row($plan, 'Vélo')['isBeyondHorizon']);
    }

    public function testAFixedDateAlreadyPastIsBoughtThisMonth(): void
    {
        $plan = $this->plan([$this->project('Cadeau', 30000, ProjectDateMode::Fixed, '2026-08-10')]);

        self::assertSame(self::m(0), self::row($plan, 'Cadeau')['purchaseMonth']);
        self::assertSame(30000, $plan['months'][0]['salaryCents']);
        self::assertFalse($plan['months'][0]['isOverCapacity']);
    }

    public function testAFixedDateBeyondTheHorizonIsReportedBeyondIt(): void
    {
        $plan = $this->plan([$this->project('Retraite', 360000, ProjectDateMode::Fixed, '2031-01-01')]);

        $row = self::row($plan, 'Retraite');
        self::assertNull($row['purchaseMonth']);
        self::assertTrue($row['isBeyondHorizon']);
        // 3 600 € over 52 months: 69,23 €, the 4 cents of remainder on the first months.
        self::assertSame(6924, $plan['months'][0]['salaryCents']);
    }

    public function testTheContractedFieldsOfAProject(): void
    {
        $plan = $this->plan([$this->project('Vélo', 120000, ProjectDateMode::Deadline, '2027-04-30')]);

        $row = $plan['projects'][0];
        self::assertSame(
            ['projectId', 'name', 'position', 'wellbeing', 'amountCents', 'dateMode', 'targetOn', 'savingFromMonth', 'purchaseMonth', 'gatheredCents', 'loan', 'investment', 'resaleFromMonth', 'isBeyondHorizon', 'isDeadlineMissed', 'delayMonths', 'unmetDependencies'],
            array_keys($row),
        );
        self::assertSame(['medium', 'deadline', '2027-04-30', 120000], [$row['wellbeing'], $row['dateMode'], $row['targetOn'], $row['amountCents']]);
    }

    /** The Box: 18 000 €, 14 000 € borrowed at 3,9 % over 10 years (141,08 € a month), 160 € of rent. */
    private function box(?string $resaleMonth = null, int $resaleNet = 1000000, ?Project $target = null): Project
    {
        $loan = (new Loan())->setName('Box · emprunt')
            ->setPrincipalRemainingCents(1400000)
            ->setMonthlyPaymentCents(LoanSchedule::annuity(1400000, 0.039 / 12, 120))
            ->setAnnualRateBasisPoints(390)
            ->setDurationMonths(120)
            ->setProjected(true);
        $box = $this->project('Box', 1800000);
        $this->source($box, FundingSourceKind::Loan, 1400000, $loan);
        $investment = (new ProjectInvestment())->setProject($box)->setMonthlyRentCents(16000);
        $investment->planResale($resaleMonth, null === $resaleMonth ? null : $resaleNet, $target);
        $box->setInvestment($investment);

        return $box;
    }

    private function resaleSource(Project $target, Project $from, int $cents): void
    {
        (new ProjectFundingSource())->setProject($target)->setKind(FundingSourceKind::Resale)->setAmountCents($cents)->setSourceProject($from);
    }

    public function testTheBoxPaymentAndMonthlyBalanceAreFigured(): void
    {
        $box = $this->box();
        $investment = $box->getInvestment();

        self::assertNotNull($investment);
        self::assertSame(400000, $investment->getDownPaymentCents());
        self::assertSame(14108, $investment->getLoanPaymentCents());
        self::assertSame(1892, $investment->getMonthlyBalanceCents());
    }

    public function testTheRentCoversTheLoanFromTheMonthAfterThePurchase(): void
    {
        $plan = $this->plan([$this->box()]);

        // 4 000 € on the salary at 500 € a month: bought in M7, rent and loan from M8.
        $row = self::row($plan, 'Box');
        self::assertSame(self::m(7), $row['purchaseMonth']);
        self::assertSame(['startMonth' => self::m(8), 'endMonth' => null, 'monthlyPaymentCents' => 14108], $row['loan']);
        self::assertSame(
            ['monthlyRentCents' => 16000, 'monthlyBalanceCents' => 1892, 'rentFromMonth' => self::m(8), 'rentUntilMonth' => null, 'resaleMonth' => null, 'resaleNetCents' => null, 'resaleTargetProjectId' => null],
            $row['investment'],
        );
        self::assertSame([0, 16000, 16000], [$plan['months'][7]['rentCents'], $plan['months'][8]['rentCents'], $plan['months'][35]['rentCents']]);
        self::assertSame(14108, $plan['months'][8]['loanPaymentsCents']);
        // 500 € + 160 € of rent − 141,08 € of payment.
        self::assertSame(51892, $plan['months'][8]['capacityCents']);
    }

    public function testTheResaleStopsTheLoanAndTheRentAndFeedsTheTarget(): void
    {
        $car = $this->project('Voiture', 1200000);
        $box = $this->box('2028-06', 1000000, $car);
        $this->resaleSource($car, $box, 1000000);

        $plan = $this->plan([$box, $car]);

        // 2028-06 is M20: the box is no longer rented nor paid from then on.
        $row = self::row($plan, 'Box');
        self::assertSame(self::m(7), $row['purchaseMonth']);
        self::assertSame(['startMonth' => self::m(8), 'endMonth' => '2028-05', 'monthlyPaymentCents' => 14108], $row['loan']);
        self::assertSame('2028-06', $row['investment']['resaleMonth']);
        self::assertSame('2028-05', $row['investment']['rentUntilMonth']);
        self::assertSame((string) $car->getId(), $row['investment']['resaleTargetProjectId']);
        self::assertSame([16000, 14108, 0, 0], [$plan['months'][19]['rentCents'], $plan['months'][19]['loanPaymentsCents'], $plan['months'][20]['rentCents'], $plan['months'][20]['loanPaymentsCents']]);
        self::assertSame(1000000, $plan['months'][20]['resaleCents']);

        // The car's 2 000 € on the salary are gathered long before, but it waits for the money.
        $carRow = self::row($plan, 'Voiture');
        self::assertSame(self::m(20), $carRow['purchaseMonth']);
        self::assertSame('2028-06', $carRow['resaleFromMonth']);
        self::assertNull($row['resaleFromMonth']);
    }

    public function testTheResaleBringsForwardTheProjectItFeeds(): void
    {
        $withoutCar = $this->project('Voiture', 1200000);
        $alone = $this->plan([$this->box(), $withoutCar]);

        $this->position = 0;
        $car = $this->project('Voiture', 1200000);
        $box = $this->box('2028-06', 1000000, $car);
        $this->resaleSource($car, $box, 1000000);
        $funded = $this->plan([$box, $car]);

        self::assertGreaterThan(self::row($funded, 'Voiture')['purchaseMonth'], self::row($alone, 'Voiture')['purchaseMonth'] ?? '9999-99');
        self::assertSame(self::m(20), self::row($funded, 'Voiture')['purchaseMonth']);
    }

    public function testAResaleWithNoTargetSpeedsUpTheNextProject(): void
    {
        $box = $this->box('2028-06', 1000000);
        $bike = $this->project('Vélo', 900000);

        $plan = $this->plan([$box, $bike]);

        // Without the resale the bike would wait for 9 000 € at ~519 €; with it, it is bought the month of the resale.
        self::assertSame(self::m(20), self::row($plan, 'Vélo')['purchaseMonth']);
        self::assertNull(self::row($plan, 'Vélo')['resaleFromMonth']);
    }

    public function testWhatTheTargetDoesNotTakeSpeedsUpTheNextProject(): void
    {
        $plans = [];
        foreach ([600000, 1000000] as $net) {
            $this->position = 0;
            $car = $this->project('Voiture', 600000);
            $box = $this->box('2028-06', $net, $car);
            $this->resaleSource($car, $box, 600000);
            $bike = $this->project('Vélo', 1300000);
            $plans[$net] = $this->plan([$box, $car, $bike]);
        }

        // The car only needed 6 000 €: the 4 000 € left are cash of the resale month.
        self::assertSame(self::m(20), self::row($plans[1000000], 'Voiture')['purchaseMonth']);
        self::assertSame(self::m(25), self::row($plans[1000000], 'Vélo')['purchaseMonth']);
        self::assertGreaterThan(self::row($plans[1000000], 'Vélo')['purchaseMonth'], self::row($plans[600000], 'Vélo')['purchaseMonth']);
    }

    public function testAResaleBeforeThePurchaseHappensTheMonthAfterIt(): void
    {
        $plan = $this->plan([$this->box('2026-10', 1000000)]);

        $row = self::row($plan, 'Box');
        self::assertSame(self::m(7), $row['purchaseMonth']);
        self::assertSame(self::m(8), $row['investment']['resaleMonth']);
        self::assertSame(0, $plan['months'][8]['rentCents']);
        self::assertSame(1000000, $plan['months'][8]['resaleCents']);
    }

    public function testNothingIsResoldWhileTheBoxIsNotBought(): void
    {
        $plan = $this->plan([$this->box('2027-01', 1000000)], capacity: 0);

        $row = self::row($plan, 'Box');
        self::assertTrue($row['isBeyondHorizon']);
        self::assertNull($row['investment']['resaleMonth']);
        self::assertSame(0, array_sum(array_column($plan['months'], 'resaleCents')));
    }
}
