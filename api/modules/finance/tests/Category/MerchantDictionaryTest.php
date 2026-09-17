<?php

namespace Maggie\Finance\Tests\Category;

use Maggie\Finance\Category\MerchantDictionary;
use PHPUnit\Framework\TestCase;

/**
 * What the dictionary is willing to guess, and what it refuses to.
 */
class MerchantDictionaryTest extends TestCase
{
    public function testFuelIsReadBeforeTheSupermarketThatSellsIt(): void
    {
        self::assertSame('Essence', MerchantDictionary::categoryFor('INTERMARCHE ESSENCE'));
        self::assertSame('Nourriture', MerchantDictionary::categoryFor('INTERMARCHE ANCORA V'));
    }

    public function testAnIncomeHeadingOnlyEverReadsMoneyArriving(): void
    {
        self::assertSame('Salaire', MerchantDictionary::categoryFor('SALAIRE JUILLET', isCredit: true));

        // The same word on a debit is a transfer someone sent, not wages.
        self::assertNull(MerchantDictionary::categoryFor('SALAIRE JUILLET'));
    }

    public function testARefundKeepsTheHeadingOfThePurchaseItCancels(): void
    {
        // Filing it as income would hide it; under groceries it cancels them.
        self::assertSame('Nourriture', MerchantDictionary::categoryFor('CARREFOUR DAC', isCredit: true));
    }

    public function testAMerchantWhoseBusinessIsNotObviousIsLeftUnanswered(): void
    {
        self::assertNull(MerchantDictionary::categoryFor('M. BALZANO VINCENT'));
        self::assertNull(MerchantDictionary::categoryFor('SAS JARDIS'));
    }
}
