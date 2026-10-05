<?php

declare(strict_types=1);

namespace Maggie\Finance\Tests\Import;

use Maggie\Finance\Import\CsvStatementParser;
use PHPUnit\Framework\TestCase;

/**
 * Banks agree on nothing: separator, date format, decimal mark, and whether a
 * debit is negative or lives in its own column. The parser has to read them
 * all without a profile per bank.
 */
class CsvStatementParserTest extends TestCase
{
    private CsvStatementParser $parser;

    protected function setUp(): void
    {
        $this->parser = new CsvStatementParser();
    }

    public function testItReadsASemicolonFrenchExportWithDebitAndCreditColumns(): void
    {
        $csv = <<<'CSV'
            Date;Libellé;Débit;Crédit
            02/09/2026;CARREFOUR MARKET;45,99;
            05/09/2026;VIREMENT SALAIRE;;3 500,00
            CSV;

        $parsed = $this->parser->parse($csv);

        self::assertSame([], $parsed['errors']);
        self::assertCount(2, $parsed['rows']);

        self::assertSame('2026-09-02', $parsed['rows'][0]->bookedAt->format('Y-m-d'));
        self::assertSame('CARREFOUR MARKET', $parsed['rows'][0]->label);
        self::assertSame(-4599, $parsed['rows'][0]->amountCents);

        self::assertSame(350000, $parsed['rows'][1]->amountCents);
    }

    public function testItReadsACommaSeparatedExportWithASignedAmount(): void
    {
        $csv = <<<'CSV'
            Type,Completed Date,Description,Amount,Currency
            CARD_PAYMENT,2026-09-04 18:22:01,Coop Zürich,-24.90,CHF
            TOPUP,2026-09-05 09:00:00,Salary,3200.00,CHF
            CSV;

        $parsed = $this->parser->parse($csv);

        self::assertSame([], $parsed['errors']);
        self::assertCount(2, $parsed['rows']);
        self::assertSame(-2490, $parsed['rows'][0]->amountCents);
        self::assertSame('CHF', $parsed['rows'][0]->currency);
        self::assertSame('2026-09-04', $parsed['rows'][0]->bookedAt->format('Y-m-d'));
        self::assertSame(320000, $parsed['rows'][1]->amountCents);
    }

    public function testItTellsGroupingSeparatorsFromDecimalMarks(): void
    {
        $csv = <<<'CSV'
            Date;Libellé;Montant
            01/09/2026;Mille deux cent trente-quatre virgule cinquante-six;-1 234,56
            02/09/2026;Same in English notation;-1,234.56
            03/09/2026;A thousand flat;-1,234
            04/09/2026;Cents only;-0,99
            CSV;

        $parsed = $this->parser->parse($csv);

        self::assertSame([], $parsed['errors']);
        self::assertSame(-123456, $parsed['rows'][0]->amountCents);
        self::assertSame(-123456, $parsed['rows'][1]->amountCents);
        // Three digits after a lone comma group thousands, they are not cents.
        self::assertSame(-123400, $parsed['rows'][2]->amountCents);
        self::assertSame(-99, $parsed['rows'][3]->amountCents);
    }

    public function testItSkipsThePreambleSomeBanksPutAboveTheHeader(): void
    {
        $csv = <<<'CSV'
            Relevé de compte
            Compte n° 0123456789
            Édité le 17/09/2026

            Date;Libellé;Montant
            02/09/2026;CARREFOUR;-45,99
            CSV;

        $parsed = $this->parser->parse($csv);

        self::assertSame([], $parsed['errors']);
        self::assertCount(1, $parsed['rows']);
        self::assertSame(-4599, $parsed['rows'][0]->amountCents);
    }

    public function testItReportsTheLineItCouldNotReadAndKeepsGoing(): void
    {
        $csv = <<<'CSV'
            Date;Libellé;Montant
            02/09/2026;CARREFOUR;-45,99
            pas une date;BROUILLON;-10,00
            04/09/2026;BOULANGERIE;-6,40
            CSV;

        $parsed = $this->parser->parse($csv);

        self::assertCount(2, $parsed['rows']);
        self::assertCount(1, $parsed['errors']);
        // In French and naming the line: the owner reads this in the import
        // report, next to the lines that did go through.
        self::assertStringContainsString('Ligne 3', $parsed['errors'][0]);
        self::assertStringContainsString('date illisible', $parsed['errors'][0]);
    }

    public function testAFileWithoutAUsableHeaderIsRefusedPlainly(): void
    {
        $parsed = $this->parser->parse("une ligne\nune autre\n");

        self::assertSame([], $parsed['rows']);
        self::assertStringContainsString("Aucune ligne d'en-tête", $parsed['errors'][0]);
    }

    public function testItSurvivesAByteOrderMarkAndTabSeparators(): void
    {
        $csv = "\u{FEFF}Date\tDescription\tAmount\n2026-09-02\tCOOP\t-24.90\n";

        $parsed = $this->parser->parse($csv, 'CHF');

        self::assertSame([], $parsed['errors']);
        self::assertSame(-2490, $parsed['rows'][0]->amountCents);
        self::assertSame('CHF', $parsed['rows'][0]->currency);
    }

    public function testAMovementWithoutALabelStillGetsOne(): void
    {
        $csv = "Date;Libellé;Montant\n02/09/2026;;-45,99\n";

        $parsed = $this->parser->parse($csv);

        self::assertSame('Sans libellé', $parsed['rows'][0]->label);
    }

    public function testTwoIdenticalMovementsShareAFingerprint(): void
    {
        $csv = <<<'CSV'
            Date;Libellé;Montant
            02/09/2026;CAFE;-3,50
            02/09/2026;CAFE;-3,50
            03/09/2026;CAFE;-3,50
            CSV;

        $rows = $this->parser->parse($csv)['rows'];

        self::assertSame($rows[0]->fingerprint(), $rows[1]->fingerprint());
        self::assertNotSame($rows[0]->fingerprint(), $rows[2]->fingerprint());
    }
}
