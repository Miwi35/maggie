<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\LoanType;
use Maggie\Finance\Service\LoanConditions;
use Maggie\Finance\Service\LoanSchedule;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

class LoanScheduleTest extends TestCase
{
    private const FROM = '2026-10-01';

    private static function loan(LoanType $type, int $principal, int $payment, int $rate): Loan
    {
        return (new Loan())
            ->setName('Prêt')
            ->setType($type)
            ->setPrincipalRemainingCents($principal)
            ->setMonthlyPaymentCents($payment)
            ->setAnnualRateBasisPoints($rate);
    }

    /** @return list<array<string, int|string>> */
    private static function rows(Loan $loan, int $horizon = LoanSchedule::MAX_MONTHS): array
    {
        return (new LoanSchedule())->rows($loan, new \DateTimeImmutable(self::FROM), $horizon);
    }

    public function testAnInFineLoanPaysTheInterestThenTheCapitalAtTheLastPayment(): void
    {
        // 10 000 € at 3 % over 12 months: 11 payments of 25 €, then 10 025 €.
        $loan = self::loan(LoanType::InFine, 1000000, 2500, 300)->setDurationMonths(12);

        $rows = self::rows($loan);

        self::assertCount(12, $rows);
        foreach (\array_slice($rows, 0, 11) as $row) {
            self::assertSame(2500, $row['paymentCents']);
            self::assertSame(0, $row['capitalCents']);
            self::assertSame(1000000, $row['remainingCents']);
        }
        self::assertSame(1002500, $rows[11]['paymentCents']);
        self::assertSame(0, $rows[11]['remainingCents']);
        self::assertSame('2027-10', $rows[11]['month']);
    }

    public function testAnInFineLoanAlreadyRunningEndsAtItsDuration(): void
    {
        // First payment a year ago, 24 months: twelve left.
        $loan = self::loan(LoanType::InFine, 1000000, 2500, 300)
            ->setDurationMonths(24)
            ->setFirstPaymentOn(new \DateTimeImmutable('2025-11-05'));

        self::assertCount(12, self::rows($loan));
    }

    public function testATotalDeferralAddsTheInterestToTheCapital(): void
    {
        // 10 000 € at 3 %, six months of total deferral: 10 150,94 € owed at the 7th month.
        $loan = self::loan(LoanType::DeferredTotal, 1000000, 50000, 300)->setDeferralMonths(6);

        $rows = self::rows($loan);

        self::assertSame(1015094, $rows[5]['remainingCents']);
        foreach (\array_slice($rows, 0, 6) as $row) {
            self::assertSame(0, $row['paymentCents']);
        }
        // Then it is amortized on what the deferral grew.
        self::assertSame(50000, $rows[6]['paymentCents']);
        self::assertSame(1015094 - (50000 - intdiv(1015094 * 300, 120000)), $rows[6]['remainingCents']);
    }

    public function testAPartialDeferralPaysOnlyTheInterest(): void
    {
        $loan = self::loan(LoanType::DeferredPartial, 1000000, 50000, 300)->setDeferralMonths(3);

        $rows = self::rows($loan);

        self::assertSame([2500, 2500, 2500], array_column(\array_slice($rows, 0, 3), 'paymentCents'));
        self::assertSame(1000000, $rows[2]['remainingCents']);
        self::assertSame(47500, $rows[3]['capitalCents']);
    }

    public function testASteppedLoanPaysTheStepItIsIn(): void
    {
        $loan = self::loan(LoanType::Stepped, 1000000, 20000, 0)->setSteps([
            ['fromMonth' => 1, 'monthlyPaymentCents' => 20000],
            ['fromMonth' => 3, 'monthlyPaymentCents' => 50000],
        ]);

        $rows = self::rows($loan);

        self::assertSame([20000, 20000, 50000, 50000], array_column(\array_slice($rows, 0, 4), 'paymentCents'));
    }

    public function testAVariableRateIsHeldUnderItsCap(): void
    {
        $loan = self::loan(LoanType::AmortizingVariable, 1200000, 50000, 600)
            ->setReferenceIndex('Euribor 3M')
            ->setMarginBasisPoints(100)
            ->setRateCapBasisPoints(400);

        self::assertSame(400, $loan->currentRateBasisPoints());
        self::assertSame(4000, self::rows($loan)[0]['interestCents']);

        $loan->setRateCapBasisPoints(null)->setRateFloorBasisPoints(700);
        self::assertSame(700, $loan->currentRateBasisPoints());
    }

    public function testTheInsuranceIsPaidWithEveryPayment(): void
    {
        $loan = self::loan(LoanType::AmortizingFixed, 240000, 20000, 0)
            ->setInitialAmountCents(1200000)
            ->setInsuranceRateBasisPoints(30);

        // 0,30 % a year of 12 000 €: 3 € a month.
        self::assertSame(300, $loan->insuranceMonthlyCentsDue());
        self::assertSame(20300, self::rows($loan)[0]['paymentCents']);

        $loan->setInsuranceMonthlyCents(1500);
        self::assertSame(1500, $loan->insuranceMonthlyCentsDue());
    }

    public function testALoanNotDrawnYetOwesNothingBeforeItsFirstPayment(): void
    {
        $loan = self::loan(LoanType::AmortizingFixed, 120000, 40000, 0)
            ->setFirstPaymentOn(new \DateTimeImmutable('2027-01-10'));

        $rows = self::rows($loan);

        self::assertSame([0, 0, 40000, 40000, 40000], array_column($rows, 'paymentCents'));
        self::assertSame('2027-01', $rows[2]['month']);
    }

    /**
     * The fixed schedule is the debt timeline's, to the cent: the loop it
     * replaced, kept here as the reference.
     *
     * @return iterable<string, array{int, int, int}>
     */
    public static function fixedLoans(): iterable
    {
        yield 'no interest' => [240000, 20000, 0];
        yield 'a car at 3.20 %' => [480000, 21000, 320];
        yield 'a mortgage at 1.80 %' => [9800000, 62500, 180];
        yield 'a payment barely above the interest' => [1000000, 4200, 500];
    }

    #[DataProvider('fixedLoans')]
    public function testAFixedLoanKeepsTheScheduleTheTimelineAlwaysHad(int $principal, int $payment, int $rate): void
    {
        $loan = self::loan(LoanType::AmortizingFixed, $principal, $payment, $rate);
        $rows = self::rows($loan, 60);

        [$months, $interest, $remaining] = [0, 0, $principal];
        while ($remaining > 0 && $months < 60) {
            $monthInterest = intdiv($remaining * $rate, 120000);
            $remaining = max(0, $remaining - ($payment - $monthInterest));
            $interest += $monthInterest;
            ++$months;
        }

        self::assertCount($months, $rows);
        self::assertSame($interest, array_sum(array_column($rows, 'interestCents')));
        self::assertSame($remaining, $rows[array_key_last($rows)]['remainingCents']);
    }

    public function testAPaymentThatNoLongerCoversTheInterestStopsTheSchedule(): void
    {
        self::assertSame([], self::rows(self::loan(LoanType::AmortizingFixed, 1000000, 2000, 500)));
    }

    public function testTheMonthlyPaymentOfAnOffer(): void
    {
        $schedule = new LoanSchedule();

        // 100 000 € at 3 % over 20 years: 554,60 €.
        self::assertSame(55460, $schedule->monthlyPaymentFor(new LoanConditions(LoanType::AmortizingFixed, 10000000, 300, 240)));
        // Without interest, the capital split over the months.
        self::assertSame(10000, $schedule->monthlyPaymentFor(new LoanConditions(LoanType::AmortizingFixed, 120000, 0, 12)));
        // An in fine pays the interest.
        self::assertSame(2500, $schedule->monthlyPaymentFor(new LoanConditions(LoanType::InFine, 1000000, 300, 12)));
        // A total deferral amortizes what it grew over the months left.
        self::assertSame(
            LoanSchedule::annuity(1015094, 300 / 120000, 18),
            $schedule->monthlyPaymentFor(new LoanConditions(LoanType::DeferredTotal, 1000000, 300, 24, 6)),
        );
    }
}
