<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Entity;

use Maggie\Finance\Entity\Category;
use Maggie\Finance\Entity\Qualification;
use Maggie\Finance\Entity\RecurringOperation;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\ObligationFlag;
use Maggie\Finance\Enum\QualificationKind;
use Maggie\Finance\Enum\RecurrencePeriod;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** The qualification and its copy onto a transaction (MAG-476). */
class QualificationTest extends TestCase
{
    /** @return iterable<string, array{ObligationFlag, bool, QualificationKind}> */
    public static function flags(): iterable
    {
        yield 'mandatory' => [ObligationFlag::Mandatory, true, QualificationKind::None];
        yield 'optional' => [ObligationFlag::Optional, false, QualificationKind::None];
        yield 'saving' => [ObligationFlag::Saving, false, QualificationKind::Saving];
        yield 'investment' => [ObligationFlag::Investment, false, QualificationKind::Investment];
        yield 'debt' => [ObligationFlag::Debt, true, QualificationKind::Repayment];
        yield 'income' => [ObligationFlag::Income, false, QualificationKind::None];
    }

    #[DataProvider('flags')]
    public function testTheFlagOfACategoryMeansAQualification(ObligationFlag $flag, bool $mandatory, QualificationKind $kind): void
    {
        $qualification = Qualification::fromObligation($flag);

        self::assertSame($mandatory, $qualification->isMandatory());
        self::assertSame($kind, $qualification->getKind());
    }

    public function testASeriesQualifiesALineWholeAndIsNamedForIt(): void
    {
        $category = (new Category())->setName('Crédits');
        $series = (new RecurringOperation())->setPeriod(RecurrencePeriod::Monthly)->requalify(new Qualification(true, QualificationKind::Repayment, $category));

        $line = (new Transaction())->qualify($series->getQualification(), $series);

        self::assertTrue($line->getQualification()->equals($series->getQualification()));
        self::assertSame($category, $line->getCategory());
        self::assertSame(CategorySource::Series, $line->getCategorySource());
        self::assertSame('series', $line->getQualifiedBy());
        self::assertSame($series, $line->getQualifiedBySeries());
        self::assertNull($line->getQualifiedByRule());
        self::assertNotNull($line->getCategorizedAt());
    }

    public function testARuleThenTheOwnerQualifyALine(): void
    {
        $category = (new Category())->setName('Transport');
        $rule = (new RecurringOperation())->requalify(new Qualification(true, QualificationKind::None, $category));
        $line = (new Transaction())->qualify($rule->getQualification(), $rule);
        self::assertSame('rule', $line->getQualifiedBy());
        self::assertSame($rule, $line->getQualifiedByRule());

        $line->qualify(new Qualification(false, QualificationKind::Saving, $category), null);

        self::assertSame('manual', $line->getQualifiedBy());
        self::assertTrue($line->isQualifiedByHand());
        self::assertNull($line->getQualifiedByRule());
        self::assertSame(QualificationKind::Saving, $line->getQualification()->getKind());
    }

    public function testAQualificationByHandThatSaysNothingLeavesTheLineUnqualified(): void
    {
        $line = (new Transaction())->qualify(Qualification::none(new Category()), null);

        $line->qualify(Qualification::none(), null);

        self::assertNull($line->getQualifiedBy());
        self::assertSame(CategorySource::None, $line->getCategorySource());
        self::assertNull($line->getCategorizedAt());
    }

    public function testAMandatoryLineWithoutCategoryIsStillQualifiedByHand(): void
    {
        $line = (new Transaction())->qualify(new Qualification(true, QualificationKind::None), null);

        self::assertSame('manual', $line->getQualifiedBy());
        self::assertNull($line->getCategory());
    }

    public function testAnOperationIsNeverQualifiedWithoutCategory(): void
    {
        $this->expectException(\DomainException::class);

        (new RecurringOperation())->requalify(new Qualification(true, QualificationKind::Loan));
    }

    public function testTheParametersTravelAsAFlatArray(): void
    {
        $qualification = new Qualification(true, QualificationKind::Investment);

        self::assertSame(['mandatory' => true, 'kind' => 'investment'], $qualification->toArray());
        self::assertTrue($qualification->equals(Qualification::fromArray($qualification->toArray())));
        self::assertSame(['qualification.mandatory' => 'qualification', 'qualification.kind' => 'qualification'], Qualification::changedPropertyMap());
    }
}
