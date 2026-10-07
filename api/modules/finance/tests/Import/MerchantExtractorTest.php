<?php

namespace Maggie\Finance\Tests\Import;

use Maggie\Finance\Import\MerchantExtractor;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/**
 * Every label here is a real one, copied from a French statement. What the
 * extractor has to survive is not a format but a lack of one.
 */
class MerchantExtractorTest extends TestCase
{
    /**
     * @return iterable<string, array{string, ?string}>
     */
    public static function labels(): iterable
    {
        yield 'card payment' => [
            'PAIEMENT PAR CARTE X9633 MP*CARREFOUR DAC VL 04/08',
            'CARREFOUR DAC VL',
        ];
        yield 'fuel at a supermarket station' => [
            'PAIEMENT PAR CARTE X9633 INTERMARCHE ESSENCE 30/07',
            'INTERMARCHE ESSENCE',
        ];
        yield 'card payment through SumUp' => [
            'PAIEMENT PAR CARTE X9633 SumUp *Le Jardin Mod 27/06',
            'Le Jardin Mod',
        ];
        yield 'utility with a customer number' => [
            'PRELEVEMENT ELECTRICITE DE FRANCE CADARE MEVEN Numero de client : 6003209210 - Numero de compte : 4036264049',
            'ELECTRICITE DE FRANCE CADARE',
        ];
        yield 'direct debit with a leading reference' => [
            'PRELEVEMENT 0219534 CRCAM D ILLE ET VILAINE CREDIT AGRICOLE ASSURANCE AUTOMOBILE -ECHEANCE 08/2026',
            'CRCAM D ILLE ET',
        ];
        // The machine's code names nothing a person recognises: what the line
        // says is that money left as cash.
        yield 'cash withdrawal' => [
            'RETRAIT AU DISTRIBUTEUR X9633 CC BEST 1. 12/08 17H00',
            'RETRAIT AU DISTRIBUTEUR',
        ];
        yield 'cash withdrawal at a shop' => [
            'RETRAIT AU DISTRIBUTEUR SUPER U JANZE 22/07 15H59',
            'RETRAIT AU DISTRIBUTEUR',
        ];
        yield 'transfer in' => [
            'VIREMENT EN VOTRE FAVEUR DE M. BALZANO VINCENT',
            'M. BALZANO VINCENT',
        ];
        yield 'transfer out' => [
            'VIREMENT EMIS WEB LAURE ASSELIN',
            'LAURE ASSELIN',
        ];
        yield 'rejected debit' => [
            'REJET PRLV EURO-ASSURANCE',
            'EURO-ASSURANCE',
        ];
        yield 'loan instalment' => [
            'REMBOURSEMENT DE PRET 10001238937 ECHEANCE 10/07/26',
            'REMBOURSEMENT DE PRET',
        ];
        yield 'a label already clean' => ['Sas Jardis', 'Sas Jardis'];
        yield 'nothing but an operation word' => ['PAIEMENT PAR CARTE X9633', null];
        yield 'too short to be a pattern' => ['AB', null];
    }

    #[DataProvider('labels')]
    public function testItKeepsTheMerchantAndDropsTheBookkeeping(string $label, ?string $expected): void
    {
        self::assertSame($expected, MerchantExtractor::extract($label));
    }

    public function testEveryWithdrawalGroupsUnderTheOneThingItMeans(): void
    {
        $labels = [
            'RETRAIT AU DISTRIBUTEUR X9633 CC BEST 1. 12/08 17H00',
            'RETRAIT AU DISTRIBUTEUR X9633 06601 RENNES H 11/07 19H22',
            'RETRAIT AU DISTRIBUTEUR SUPER U JANZE 16/07 18H35',
        ];

        $keys = array_map(
            static fn (string $label) => MerchantExtractor::key((string) MerchantExtractor::extract($label)),
            $labels,
        );

        // Three machines, one habit. Filing the last one under the supermarket
        // it stands in would be wrong: no groceries were bought.
        self::assertCount(1, array_unique($keys));
    }

    public function testTwoVisitsToTheSameShopGroupTogether(): void
    {
        $first = MerchantExtractor::extract('PAIEMENT PAR CARTE X9633 ESS24 24 INTER JANZ 27/06');
        $second = MerchantExtractor::extract('PAIEMENT PAR CARTE X9633 ESS24 24 INTER JANZ 20/08');

        // Same shop, two dates: without grouping, no rule could ever be written.
        self::assertSame(MerchantExtractor::key((string) $first), MerchantExtractor::key((string) $second));
    }

    public function testAccentsAndCaseDoNotSplitAMerchantInTwo(): void
    {
        self::assertSame(
            MerchantExtractor::key('Café Étoile'),
            MerchantExtractor::key('CAFE ETOILE'),
        );
    }

    public function testThePlaceholderOfAnEmptyLabelNamesNoOne(): void
    {
        self::assertNull(MerchantExtractor::extract('Sans libellé'));
    }
}
