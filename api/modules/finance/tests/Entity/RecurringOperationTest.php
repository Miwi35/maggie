<?php

namespace Maggie\Finance\Tests\Entity;

use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Enum\AmountDirection;
use Maggie\Finance\Enum\RecurrencePeriod;
use PHPUnit\Framework\TestCase;

/**
 * What a series costs, which amounts it expects and when it runs.
 */
class RecurringOperationTest extends TestCase
{
    private function series(int $amountCents, RecurrencePeriod $period = RecurrencePeriod::Monthly): RecurringOperation
    {
        return (new RecurringOperation())
            ->setLabel('Série')
            ->setReferenceAmountCents($amountCents)
            ->setPeriod($period)
            ->setAnchorOn(new \DateTimeImmutable('2027-01-12'));
    }

    public function testMonthlyAndYearlyCostForTheFourPeriods(): void
    {
        $cases = [
            // period, reference, monthly, yearly
            [RecurrencePeriod::Weekly, -1000, -4333, -52000],
            [RecurrencePeriod::Monthly, -1349, -1349, -16188],
            [RecurrencePeriod::Quarterly, -9000, -3000, -36000],
            [RecurrencePeriod::Yearly, -12000, -1000, -12000],
            [RecurrencePeriod::Yearly, 100, 8, 100],
        ];

        foreach ($cases as [$period, $reference, $monthly, $yearly]) {
            $series = $this->series($reference, $period);

            self::assertSame($monthly, $series->getMonthlyCostCents(), $period->value);
            self::assertSame($yearly, $series->getYearlyCostCents(), $period->value);
        }
    }

    public function testAnAmountWithinTheToleranceAndOfTheSameSignIsAccepted(): void
    {
        $series = $this->series(-3000)->setAmountTolerancePercent(20);

        self::assertTrue($series->acceptsAmount(-3000));
        self::assertTrue($series->acceptsAmount(-3600));
        self::assertTrue($series->acceptsAmount(-2400));
        self::assertFalse($series->acceptsAmount(-3601));
        self::assertFalse($series->acceptsAmount(-2399));
        self::assertFalse($series->acceptsAmount(3000), 'a refund is not the expense');
        self::assertFalse($series->acceptsAmount(0));
    }

    public function testAZeroToleranceAcceptsOnlyTheExactAmount(): void
    {
        $series = $this->series(250000)->setAmountTolerancePercent(0);

        self::assertTrue($series->acceptsAmount(250000));
        self::assertFalse($series->acceptsAmount(250001));
    }

    public function testTheSeriesRunsFromItsAnchorToItsEndBothIncluded(): void
    {
        $series = $this->series(-1349)->setEndsOn(new \DateTimeImmutable('2027-06-12'));

        self::assertFalse($series->isActiveOn(new \DateTimeImmutable('2027-01-11')));
        self::assertTrue($series->isActiveOn(new \DateTimeImmutable('2027-01-12 18:00')));
        self::assertTrue($series->isActiveOn(new \DateTimeImmutable('2027-06-12 23:59')));
        self::assertFalse($series->isActiveOn(new \DateTimeImmutable('2027-06-13')));
        self::assertTrue($series->setEndsOn(null)->isActiveOn(new \DateTimeImmutable('2040-01-01')));
    }

    public function testTheCounterpartyIsFoldedIntoAKey(): void
    {
        $series = $this->series(-1349)->setCounterpartyName('  Flixo   S.A.S. ');

        self::assertSame('Flixo S.A.S.', $series->getCounterpartyName());
        self::assertSame('flixo sas', $series->getCounterpartyKey());
        self::assertNull($series->setCounterpartyName('  ')->getCounterpartyKey());
    }

    public function testItIsRecognisedByItsCounterpartyInTheDirectionOfItsAmount(): void
    {
        $expense = $this->series(-1349)->setCounterpartyName('Flixo')->setLabelPattern('  ')->matchCriteria();

        self::assertSame('flixo', $expense->counterpartyKey);
        self::assertNull($expense->labelPattern);
        self::assertSame(AmountDirection::Debit, $expense->direction);
        self::assertNull($expense->minAmountCents, 'the tolerance is a condition of attaching, not of recognising');

        self::assertSame(AmountDirection::Credit, $this->series(250000)->matchCriteria()->direction);
    }
}
