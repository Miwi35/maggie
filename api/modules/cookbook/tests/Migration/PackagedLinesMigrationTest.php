<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Migration;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\DBAL\Connection;
use Doctrine\DBAL\Schema\Schema;
use Doctrine\ORM\EntityManagerInterface;
use DoctrineMigrations\Version20261013150000;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Tests\Support\ChoosesMealGroceriesTrait;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Psr\Log\AbstractLogger;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * The lines of packaged products counted in packagings (MAG-299).
 *
 * Each case starts from what the list held before: pasta (400 g per meal) in a
 * 500 g pack, as grams on the line and on each meal's share, then runs the
 * migration's own statements against the test Postgres.
 */
class PackagedLinesMigrationTest extends KernelTestCase
{
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;
    use SecurityTokenTrait;
    use ChoosesMealGroceriesTrait;

    private Connection $connection;

    /** @var list<string> */
    private array $report = [];

    protected function setUp(): void
    {
        self::bootKernel();
        $this->connection = self::getContainer()->get('doctrine.dbal.default_connection');
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures(\dirname(__DIR__).'/MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();
    }

    public function testGramsOnALineAndOnEachMealShareBecomePackagings(): void
    {
        $this->pastaInGramsForTwoMeals();

        $this->runMigration();

        self::assertSame([['pack', 2.0]], $this->pastaLines());
        self::assertSame([[1.0, 'pack'], [1.0, 'pack']], $this->pastaShares());
        self::assertContains('  Pâtes: 800 g → 2 pack', $this->report);
    }

    public function testTheOpenLinesOfOneProductBecomeOneAndTheMealSharesFollow(): void
    {
        $this->pastaInGramsForTwoMeals(meals: 1);
        $this->aPastaLine(700, Unit::Gram, position: 99, source: GroceryItemSource::Manual);
        $this->aPastaLine(1, Unit::Pack, position: 100, source: GroceryItemSource::Manual);

        $this->runMigration();

        // 400 g → 1 pack (the meal's), 700 g → 2, 1 pack → 1: one line of 4.
        $lines = $this->pastaLines();
        self::assertCount(1, $lines);
        self::assertSame(4.0, $lines[0][1]);
        self::assertSame([[1.0, 'pack']], $this->pastaShares());
        self::assertSame('manual', $this->connection->fetchOne("SELECT source FROM grocery_item WHERE product_id = (SELECT id FROM product WHERE name = 'Pâtes')"));
        self::assertContains('MAG-299: 1 line(s) of packaged products converted to packagings, 2 merged into another, 1 meal share(s) converted.', $this->report);
    }

    public function testATickedLineIsConvertedButNeverMerged(): void
    {
        $this->pastaInGramsForTwoMeals(meals: 1);
        $this->aPastaLine(300, Unit::Gram, position: 99, source: GroceryItemSource::Manual, checked: true);

        $this->runMigration();

        $lines = $this->connection->fetchAllAssociative("SELECT quantity, unit, checked FROM grocery_item WHERE product_id = (SELECT id FROM product WHERE name = 'Pâtes') ORDER BY checked");
        self::assertCount(2, $lines);
        self::assertSame([1.0, 'pack'], [(float) $lines[0]['quantity'], $lines[0]['unit']]);
        self::assertSame([1.0, 'pack'], [(float) $lines[1]['quantity'], $lines[1]['unit']]);
        self::assertTrue((bool) $lines[1]['checked']);
    }

    public function testAProductWithoutPackagingIsLeftAlone(): void
    {
        $this->pastaInGramsForTwoMeals(meals: 1);

        $this->runMigration();

        self::assertSame(
            [['4', 'piece']],
            array_map(
                static fn (array $row) => [(string) (int) $row['quantity'], $row['unit']],
                $this->connection->fetchAllAssociative("SELECT quantity, unit FROM grocery_item WHERE product_id = (SELECT id FROM product WHERE name = 'Tomate')"),
            ),
        );
    }

    public function testTheReportComesFirstAndASecondRunChangesNothing(): void
    {
        $this->pastaInGramsForTwoMeals();

        $migration = $this->migration();
        $migration->up(new Schema());
        self::assertContains('  Pâtes: 800 g → 2 pack', $this->report, 'the report is known before anything is written');
        self::assertSame('g', $this->connection->fetchOne("SELECT unit FROM grocery_item WHERE product_id = (SELECT id FROM product WHERE name = 'Pâtes')"), 'reporting wrote nothing');

        $this->execute($migration);
        $after = $this->connection->fetchAllAssociative('SELECT * FROM grocery_item ORDER BY id');

        $again = $this->migration();
        $again->up(new Schema());

        self::assertSame([], $again->getSql(), 'nothing is left to convert');
        self::assertSame($after, $this->connection->fetchAllAssociative('SELECT * FROM grocery_item ORDER BY id'));
    }

    /** The list as the previous version left it: pasta in a 500 g pack, counted in grams. */
    private function pastaInGramsForTwoMeals(int $meals = 2): void
    {
        $pasta = $this->em()->find(Ingredient::class, $this->getFixture('pasta_product')->getId());
        $pasta->setPackagingUnit(Unit::Pack);
        $pasta->setPackagingSize(500);
        $pasta->setPackagingSizeUnit(Unit::Gram);
        $this->em()->flush();
        $this->em()->clear();

        $bus = self::getContainer()->get(MessageBusInterface::class);
        $userId = (string) $this->em()->getRepository(User::class)->findOneBy(['email' => 'cookbook-test@example.com'])->getId();
        foreach (array_slice(['+1 day', '+2 days'], 0, $meals) as $when) {
            $meal = $bus->dispatch(new CreateMealCommand(
                date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
                slot: 'dinner',
                recipeIds: [(string) $this->getFixture('pasta')->getId()],
                userId: $userId,
            ))->last(HandledStamp::class)->getResult();
            $this->chooseEveryIngredientOf((string) $meal->getId(), 'pasta', $userId);
        }

        $this->connection->executeStatement("UPDATE grocery_item SET quantity = :q, unit = 'g' WHERE product_id = (SELECT id FROM product WHERE name = 'Pâtes')", ['q' => 400 * $meals]);
        $this->connection->executeStatement("UPDATE meal_grocery_contribution SET quantity = 400, unit = 'g' WHERE grocery_item_id IN (SELECT id FROM grocery_item WHERE product_id = (SELECT id FROM product WHERE name = 'Pâtes'))");
        $this->em()->clear();
        $this->report = [];
    }

    private function aPastaLine(float $quantity, Unit $unit, int $position, GroceryItemSource $source, bool $checked = false): void
    {
        $this->em()->clear();
        $list = $this->em()->getRepository(GroceryList::class)->findOneBy([]);
        $item = new GroceryItem();
        $item->setProduct($this->em()->find(Ingredient::class, $this->getFixture('pasta_product')->getId()));
        $item->setQuantity($quantity);
        $item->setUnit($unit);
        $item->setSource($source);
        $item->setChecked($checked);
        $item->setPosition($position);
        $list->addItem($item);
        $this->em()->flush();
        $this->em()->clear();
    }

    /** @return list<array{string, float}> the unit and the quantity of each line, in the list's order */
    private function pastaLines(): array
    {
        return array_map(
            static fn (array $row) => [$row['unit'], (float) $row['quantity']],
            $this->connection->fetchAllAssociative("SELECT position, quantity, unit FROM grocery_item WHERE product_id = (SELECT id FROM product WHERE name = 'Pâtes') ORDER BY position"),
        );
    }

    /** @return list<array{float, string}> */
    private function pastaShares(): array
    {
        return array_map(
            static fn (array $row) => [(float) $row['quantity'], $row['unit']],
            $this->connection->fetchAllAssociative("SELECT c.quantity, c.unit FROM meal_grocery_contribution c JOIN grocery_item i ON i.id = c.grocery_item_id WHERE i.product_id = (SELECT id FROM product WHERE name = 'Pâtes') ORDER BY c.id"),
        );
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function migration(): Version20261013150000
    {
        // api/migrations is not on the autoloader — the migrations runner loads
        // the files itself, and this test runs *that* file.
        require_once \dirname(__DIR__, 4).'/migrations/Version20261013150000.php';

        $report = &$this->report;

        return new Version20261013150000($this->connection, new class($report) extends AbstractLogger {
            /** @param list<string> $lines */
            public function __construct(private array &$lines)
            {
            }

            public function log($level, \Stringable|string $message, array $context = []): void
            {
                $this->lines[] = (string) $message;
            }
        });
    }

    private function execute(Version20261013150000 $migration): void
    {
        foreach ($migration->getSql() as $query) {
            $this->connection->executeStatement($query->getStatement(), $query->getParameters(), $query->getTypes());
        }
    }

    private function runMigration(): void
    {
        $migration = $this->migration();
        $migration->up(new Schema());
        $this->execute($migration);
    }
}
