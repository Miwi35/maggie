<?php

namespace Maggie\Grocery\Tests\UseCase;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Maggie\Grocery\UseCase\AddRecurringGroceryItem;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

class AddRecurringGroceryItemTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../Command/fixtures/AddDueRecurringGroceryItemsCommandTest.yaml');
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function add(string $fixture): ?GroceryList
    {
        $item = $this->em()->find(RecurringGroceryItem::class, $this->getFixture($fixture)->getId());

        return self::getContainer()->get(AddRecurringGroceryItem::class)->execute($item);
    }

    /** @return list<GroceryItem> */
    private function lines(): array
    {
        $this->em()->clear();

        return array_values($this->em()->getRepository(GroceryItem::class)->findBy([], ['position' => 'ASC']));
    }

    private function today(): string
    {
        return (new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    private function lastAddedAt(string $fixture): ?string
    {
        $this->em()->clear();

        return $this->em()->find(RecurringGroceryItem::class, $this->getFixture($fixture)->getId())?->getLastAddedAt()?->format('Y-m-d');
    }

    public function testADueProductItemGetsItsQuantityUnitAndStoreOnTheList(): void
    {
        self::assertNotNull($this->add('recurring_rice'));

        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame(2.0, $lines[0]->getQuantity());
        self::assertSame(Unit::Pack, $lines[0]->getUnit());
        self::assertSame(GroceryItemSource::Recurring, $lines[0]->getSource());
        self::assertSame('Supermarché', $lines[0]->getStore()?->getName());
        self::assertSame($this->today(), $this->lastAddedAt('recurring_rice'));
    }

    public function testADueItemWithOnlyWordsGetsALineWithThoseWords(): void
    {
        self::assertNotNull($this->add('recurring_milk'));

        $lines = $this->lines();
        self::assertCount(1, $lines);
        self::assertSame('Lait', $lines[0]->getLabel());
        self::assertNull($lines[0]->getProduct());
        self::assertSame(Unit::Liter, $lines[0]->getUnit());
        self::assertSame($this->today(), $this->lastAddedAt('recurring_milk'));
    }

    public function testAnItemThatIsNotDueIsLeftAlone(): void
    {
        $before = $this->lastAddedAt('recurring_coffee');

        self::assertNull($this->add('recurring_coffee'));

        self::assertSame([], $this->lines());
        self::assertSame($before, $this->lastAddedAt('recurring_coffee'));
    }

    public function testAddingTwiceTheSameDayAddsOnce(): void
    {
        $this->add('recurring_rice');
        self::assertNull($this->add('recurring_rice'));

        self::assertCount(1, $this->lines());
    }

    public function testAnOpenLineOfTheSameWordsIsEnoughAndTheClockRestarts(): void
    {
        $this->add('recurring_milk');
        $this->em()->clear();
        $milk = $this->em()->find(RecurringGroceryItem::class, $this->getFixture('recurring_milk')->getId());
        $milk->setLastAddedAt(new \DateTimeImmutable('-8 days'));
        $this->em()->flush();

        self::assertNull($this->add('recurring_milk'));

        self::assertCount(1, $this->lines());
        self::assertSame($this->today(), $this->lastAddedAt('recurring_milk'));
    }

    public function testATickedLineDoesNotStandInTheWay(): void
    {
        $this->add('recurring_milk');
        $this->em()->clear();
        $line = $this->em()->getRepository(GroceryItem::class)->findAll()[0];
        $line->setChecked(true);
        $milk = $this->em()->find(RecurringGroceryItem::class, $this->getFixture('recurring_milk')->getId());
        $milk->setLastAddedAt(new \DateTimeImmutable('-8 days'));
        $this->em()->flush();

        self::assertNotNull($this->add('recurring_milk'));

        self::assertCount(2, $this->lines());
    }
}
