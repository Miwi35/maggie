<?php

namespace Maggie\Finance\Tests\Import;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Enum\TransferKind;
use Maggie\Finance\Import\CsvStatementParser;
use Maggie\Finance\Import\MerchantExtractor;
use Maggie\Finance\Import\StatementRow;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ImportStatementTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    /** @return array{imported: int, skipped: int, categorized: int, first: ?string, last: ?string, totalCents: int, rows: list<array{line: int, bookedAt: string, label: string, amountCents: int, currency: string, duplicate: bool, categoryName: ?string}>} */
    private function import(string $csv, bool $dryRun = false): array
    {
        $parser = self::getContainer()->get(CsvStatementParser::class);
        $importStatement = self::getContainer()->get(ImportStatement::class);

        return $importStatement->execute(
            $this->getFixture('checking'),
            $parser->parse($csv)['rows'],
            $dryRun,
        );
    }

    private function countTransactions(): int
    {
        return \count(
            self::getContainer()->get('doctrine.orm.entity_manager')
                ->getRepository(Transaction::class)
                ->findAll(),
        );
    }

    private const STATEMENT = <<<'CSV'
        Date;Libellé;Montant
        02/09/2026;CARREFOUR MARKET;-45,99
        04/09/2026;BOULANGERIE;-6,40
        CSV;

    public function testItFilesEveryMovementOnTheAccount(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $before = $this->countTransactions();
        $result = $this->import(self::STATEMENT);

        self::assertSame(2, $result['imported']);
        self::assertSame(0, $result['skipped']);
        self::assertSame('2026-09-02', $result['first']);
        self::assertSame('2026-09-04', $result['last']);
        self::assertSame(-5239, $result['totalCents']);
        self::assertSame($before + 2, $this->countTransactions());
    }

    public function testARejectionArrivingWithTheStatementIsPairedWithThePaymentItGivesBack(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $this->import(<<<'CSV'
            Date;Libellé;Montant
            05/10/2026;PRELEVEMENT ELECTRICITE DE FRANCE;-206,00
            06/10/2026;REJET PRLV ELECTRICITE DE FRANCE;206,00
            CSV);

        $lines = self::getContainer()->get('doctrine.orm.entity_manager')
            ->getRepository(Transaction::class)
            ->findBy(['account' => $this->getFixture('checking')], ['bookedAt' => 'ASC']);
        $lines = array_values(array_filter($lines, static fn (Transaction $t) => str_contains($t->getLabel(), 'ELECTRICITE')));

        self::assertCount(2, $lines);
        [$debit, $credit] = $lines;
        self::assertSame(TransferKind::Rejected, $debit->getTransferKind());
        self::assertSame(TransferKind::Rejected, $credit->getTransferKind());
        self::assertSame((string) $credit->getId(), (string) $debit->getCounterpart()?->getId());
        $this->assertMercureUpdatePublished((string) $debit->getId());
        $this->assertElasticsearchIndexDispatchedFor(Transaction::class, (string) $debit->getId());
    }

    public function testTheRulesAlreadyWrittenApplyToTheImportedHistory(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $result = $this->import(self::STATEMENT);

        // The fixture carries a CARREFOUR rule; the bakery matches nothing.
        self::assertSame(1, $result['categorized']);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $imported = $em->getRepository(Transaction::class)->findOneBy(['label' => 'CARREFOUR MARKET']);
        self::assertSame(CategorySource::Rule, $imported->getCategorySource());
        self::assertSame('Alimentation', $imported->getCategory()->getName());
    }

    public function testImportingTheSameFileTwiceChangesNothingTheSecondTime(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $this->import(self::STATEMENT);
        $afterFirst = $this->countTransactions();

        $result = $this->import(self::STATEMENT);

        self::assertSame(0, $result['imported']);
        self::assertSame(2, $result['skipped']);
        self::assertSame($afterFirst, $this->countTransactions());
    }

    public function testAnOverlappingExportOnlyBringsWhatIsNew(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $this->import(self::STATEMENT);

        $overlapping = <<<'CSV'
            Date;Libellé;Montant
            04/09/2026;BOULANGERIE;-6,40
            06/09/2026;PHARMACIE;-12,30
            CSV;

        $result = $this->import($overlapping);

        self::assertSame(1, $result['imported']);
        self::assertSame(1, $result['skipped']);
    }

    public function testTwoIdenticalMovementsOnTheSameDayAreBothRealMovements(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $twoCoffees = <<<'CSV'
            Date;Libellé;Montant
            02/09/2026;CAFE DE LA GARE;-3,50
            02/09/2026;CAFE DE LA GARE;-3,50
            CSV;

        $result = $this->import($twoCoffees);

        // Same date, same label, same amount — and yet two coffees were bought.
        self::assertSame(2, $result['imported']);

        // Re-importing that file still recognises both.
        $again = $this->import($twoCoffees);
        self::assertSame(0, $again['imported']);
        self::assertSame(2, $again['skipped']);
    }

    public function testACsvLineKeepsItsMerchantAsCounterpartyWhateverTheDate(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $this->import(<<<'CSV'
            Date;Libellé;Montant
            12/10/2026;PRLV SEPA NETFLIX.COM 12/10;-13,49
            12/11/2026;PRLV SEPA NETFLIX.COM 12/11;-13,49
            CSV);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $october = $em->getRepository(Transaction::class)->findOneBy(['label' => 'PRLV SEPA NETFLIX.COM 12/10']);
        $november = $em->getRepository(Transaction::class)->findOneBy(['label' => 'PRLV SEPA NETFLIX.COM 12/11']);

        self::assertSame('PRLV SEPA NETFLIX.COM', $october->getCounterpartyName());
        self::assertSame('prlv sepa netflixcom', $october->getCounterpartyKey());
        self::assertSame($october->getCounterpartyKey(), $november->getCounterpartyKey());
        self::assertSame(MerchantExtractor::key('PRLV SEPA NETFLIX.COM'), $october->getCounterpartyKey());
    }

    public function testALabelNamingNoOneLeavesTheCounterpartyEmpty(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $this->import(<<<'CSV'
            Date;Libellé;Montant
            12/10/2026;12345678;-5,00
            CSV);

        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $line = $em->getRepository(Transaction::class)->findOneBy(['label' => '12345678']);

        self::assertNull($line->getCounterpartyName());
        self::assertNull($line->getCounterpartyKey());
    }

    public function testARehearsalLeavesStoredLinesWithoutCounterpartyAlone(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $parser = self::getContainer()->get(CsvStatementParser::class);
        $rows = $parser->parse("Date;Libellé;Montant\n01/09/2026;CARREFOUR MARKET 4412;-45,99")['rows'];
        $named = [new StatementRow($rows[0]->bookedAt, $rows[0]->label, $rows[0]->amountCents, $rows[0]->currency, 2, 'CARREFOUR MARKET')];

        $result = self::getContainer()->get(ImportStatement::class)->execute($this->getFixture('checking'), $named, true);

        self::assertSame(1, $result['skipped']);
        self::assertSame(0, $result['counterpartiesCompleted']);
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        $em->clear();
        self::assertNull($em->getRepository(Transaction::class)->findOneBy(['label' => 'CARREFOUR MARKET 4412'])->getCounterpartyName());
    }

    public function testARehearsalReportsWithoutWritingAnything(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $before = $this->countTransactions();
        $result = $this->import(self::STATEMENT, dryRun: true);

        self::assertSame(2, $result['imported']);
        self::assertSame($before, $this->countTransactions());

        // A rehearsal that told the open screens about movements it did not
        // write would be a lie in two places at once.
        self::assertSame([], $this->getMercureHub()->getUpdates());
        self::assertSame([], $this->getAsyncTransport()->getSent());
    }

    /**
     * The report, line by line.
     *
     * The rehearsal is read by someone about to confirm it, and "1 doublon
     * écarté" does not say which movement was dropped, nor under which
     * heading the rest is about to be filed.
     */
    public function testTheReportSaysWhatBecomesOfEachLine(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        // 01/09 CARREFOUR MARKET 4412 at -45,99 € is already on the account
        // (fixture `uncategorized_carrefour`), so the first line is a
        // duplicate while the second is new — and the rule claims it.
        $statement = <<<'CSV'
            Date;Libellé;Montant
            01/09/2026;CARREFOUR MARKET 4412;-45,99
            07/09/2026;CARREFOUR CITY;-8,10
            CSV;

        $result = $this->import($statement, dryRun: true);

        self::assertSame(1, $result['skipped']);
        self::assertSame(1, $result['imported']);

        [$duplicate, $fresh] = $result['rows'];

        self::assertSame(2, $duplicate['line']);
        self::assertSame('CARREFOUR MARKET 4412', $duplicate['label']);
        self::assertTrue($duplicate['duplicate']);
        self::assertNull($duplicate['categoryName'], 'a dropped line is filed nowhere');

        self::assertSame(3, $fresh['line']);
        self::assertSame('2026-09-07', $fresh['bookedAt']);
        self::assertSame(-810, $fresh['amountCents']);
        self::assertSame('EUR', $fresh['currency']);
        self::assertFalse($fresh['duplicate']);
        self::assertSame('Alimentation', $fresh['categoryName']);
    }

    public function testAnImportReachesTheOpenScreensAndTheIndex(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $this->import(self::STATEMENT);

        $this->assertMercureUpdatePublished('/transactions/');
        $this->assertElasticsearchIndexDispatched(Transaction::class);
    }
}
