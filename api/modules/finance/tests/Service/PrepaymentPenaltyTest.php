<?php

namespace Maggie\Finance\Tests\Service;

use Maggie\Finance\Entity\Loan;
use Maggie\Finance\Enum\PrepaymentPenaltyMode;
use Maggie\Finance\Service\PrepaymentPenalty;
use Maggie\Finance\UseCase\SimulateEarlyRepayment;
use PHPUnit\Framework\TestCase;

class PrepaymentPenaltyTest extends TestCase
{
    private static function loan(PrepaymentPenaltyMode $mode, int $principal = 9800000, int $rate = 180): Loan
    {
        return (new Loan())
            ->setName('Prêt immo')
            ->setPrincipalRemainingCents($principal)
            ->setMonthlyPaymentCents(62500)
            ->setAnnualRateBasisPoints($rate)
            ->setPrepaymentPenaltyMode($mode);
    }

    public function testTheLegalCapIsTheLesserOfSixMonthsOfInterestAndThreePercent(): void
    {
        // 98 000 € at 1,80 %, repaid in full: min(882 €, 2 940 €).
        self::assertSame(88200, (new PrepaymentPenalty())->penaltyCents(self::loan(PrepaymentPenaltyMode::LegalCap), 9800000, 180));
    }

    public function testTheLegalCapStopsAtThreePercentOfTheCapitalOwed(): void
    {
        // At 8 %, six months of interest (4 %) exceed 3 % of the capital.
        self::assertSame(300000, (new PrepaymentPenalty())->penaltyCents(self::loan(PrepaymentPenaltyMode::LegalCap, 10000000, 800), 10000000, 120));
    }

    public function testTheRealGainIsTheInterestLeftMinusThePenalty(): void
    {
        // 6 200 € of interest left, 882 € of penalty: 5 318 €.
        self::assertSame(531800, SimulateEarlyRepayment::realGainCents(620000, 88200));
    }

    public function testAFixedPenaltyIsTheAmountTheOfferStates(): void
    {
        $loan = self::loan(PrepaymentPenaltyMode::Fixed)->setPrepaymentPenaltyFixedCents(150000);

        self::assertSame(150000, (new PrepaymentPenalty())->penaltyCents($loan, 9800000, 180));
    }

    public function testAnExemptLoanCostsNothing(): void
    {
        self::assertSame(0, (new PrepaymentPenalty())->penaltyCents(self::loan(PrepaymentPenaltyMode::Exempt), 9800000, 180));
    }

    public function testAConsumerLoanCostsOnePercentAboveTwelveMonthsLeftHalfAPercentBelow(): void
    {
        $loan = self::loan(PrepaymentPenaltyMode::Consumer, 1000000, 500);

        self::assertSame(10000, (new PrepaymentPenalty())->penaltyCents($loan, 1000000, 24));
        self::assertSame(5000, (new PrepaymentPenalty())->penaltyCents($loan, 1000000, 10));
        self::assertSame(5000, (new PrepaymentPenalty())->penaltyCents($loan, 1000000, 12));
    }
}
