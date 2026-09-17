<?php

namespace Maggie\Finance\Tests\Import;

use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Finance\Entity\Transaction;
use Maggie\Finance\Enum\CategorySource;
use Maggie\Finance\Import\CsvStatementParser;
use Maggie\Finance\UseCase\ImportStatement;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class ImportStatementTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
    }

    /** @return array{imported: int, skipped: int, categorized: int, first: ?string, last: ?string, totalCents: int} */
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

    public function testARehearsalReportsWithoutWritingAnything(): void
    {
        $this->loadFixtures('categorization_rule.yaml');

        $before = $this->countTransactions();
        $result = $this->import(self::STATEMENT, dryRun: true);

        self::assertSame(2, $result['imported']);
        self::assertSame($before, $this->countTransactions());
    }
}
