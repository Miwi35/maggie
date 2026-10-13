<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Enum\GroceryItemSource;
use Maggie\Grocery\Enum\Unit;
use Symfony\Bundle\FrameworkBundle\Console\Application;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Tester\CommandTester;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * `maggie:grocery:consolidate-lines` — the lists written before a product had
 * one line become one line per product, totals worked out again (MAG-380).
 */
final class ConsolidateGroceryLinesCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;
    use SecurityTokenTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures(__DIR__.'/../MessageHandler/fixtures/MealGrocerySyncTest.yaml');
        $this->loginFixtureUser();
        $this->em()->clear();

        $this->tester = new CommandTester((new Application(self::$kernel))->find('maggie:grocery:consolidate-lines'));
    }

    public function testDryRunListsTheMergesAndTouchesNothing(): void
    {
        $this->legacyPastaOnThreeLines();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->tester->execute(['--dry-run' => true]);

        $this->tester->assertCommandIsSuccessful();
        self::assertStringContainsString('Pâtes: 3 line(s)', $this->tester->getDisplay());
        self::assertStringContainsString('would be merged or recounted', $this->tester->getDisplay());
        self::assertCount(3, $this->pastaLines());
        $this->assertMercureUpdateCount(0);
    }

    public function testTheLinesOfAProductBecomeOneAndTheTotalIsCountedOnce(): void
    {
        $this->legacyPastaOnThreeLines();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        $this->tester->assertCommandIsSuccessful();
        $lines = $this->pastaLines();
        self::assertCount(1, $lines);
        // 300 g + 500 g = 800 g: two packs on the meals' total, plus the pack written by hand.
        self::assertSame(3.0, $lines[0]->getQuantity());
        self::assertSame(Unit::Pack, $lines[0]->getUnit());
        self::assertSame([['quantity' => 800.0, 'unit' => 'g']], $lines[0]->getNeeded());
        $this->em()->clear();
        self::assertCount(2, $this->em()->getRepository(MealGroceryContribution::class)->findAll());
        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testRunningItAgainFindsNothingLeftToDo(): void
    {
        $this->legacyPastaOnThreeLines();
        $this->tester->execute([]);
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->tester->execute([]);

        self::assertStringContainsString('Every product already has one line.', $this->tester->getDisplay());
        $this->assertMercureUpdateCount(0);
        self::assertSame(3.0, $this->pastaLines()[0]->getQuantity());
    }

    public function testATickedLineStaysAsItIs(): void
    {
        $this->legacyPastaOnThreeLines();
        $ticked = $this->pastaLines()[2];
        $ticked->setChecked(true);
        $this->em()->flush();
        $this->em()->clear();

        $this->tester->execute([]);

        $lines = $this->pastaLines();
        self::assertCount(2, $lines);
        self::assertSame(1, \count(array_filter($lines, static fn (GroceryItem $i) => $i->isChecked())));
    }

    /**
     * The old shape: one line per meal share (1 pack each, needs unknown), and
     * a third line written by hand — 1 pack of the same pasta.
     */
    private function legacyPastaOnThreeLines(): void
    {
        $pasta = $this->em()->find(Ingredient::class, $this->getFixture('pasta_product')->getId());
        $pasta->setPackagingUnit(Unit::Pack)->setPackagingSize(500)->setPackagingSizeUnit(Unit::Gram);
        $this->em()->flush();
        $this->em()->clear();

        $light = $this->planMeal('light_pasta', '+1 day');
        $big = $this->planMeal('big_pasta', '+2 days');
        unset($light);

        $first = $this->pastaLines()[0];
        $list = $first->getGroceryList();
        $first->setNeeded(null);
        $first->setQuantity(1.0);

        $second = $this->line($list, $first, GroceryItemSource::Recipe);
        foreach ($this->em()->getRepository(MealGroceryContribution::class)->findAll() as $contribution) {
            if (500.0 === $contribution->getQuantity() && (string) $contribution->getMeal()->getId() === $big) {
                $contribution->setGroceryItem($second);
            }
        }

        $this->line($list, $first, GroceryItemSource::Manual);
        $this->em()->flush();
        $this->em()->clear();

        self::assertCount(3, $this->pastaLines());
    }

    private function line(GroceryList $list, GroceryItem $like, GroceryItemSource $source): GroceryItem
    {
        $item = new GroceryItem();
        $item->setProduct($like->getProduct());
        $item->setQuantity(1.0);
        $item->setUnit(Unit::Pack);
        $item->setSource($source);
        $item->setPosition($like->getPosition() + 1);
        $list->addItem($item);
        $this->em()->persist($item);
        $this->em()->flush();

        return $item;
    }

    /** @return list<GroceryItem> */
    private function pastaLines(): array
    {
        $this->em()->clear();

        $lines = array_values(array_filter(
            $this->em()->getRepository(GroceryItem::class)->findBy([], ['position' => 'ASC']),
            static fn (GroceryItem $item) => 'Pâtes' === $item->getLabel(),
        ));

        return $lines;
    }

    private function planMeal(string $recipeRef, string $when): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));
        $this->em()->clear();

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }

    private function bus(): MessageBusInterface
    {
        return self::getContainer()->get(MessageBusInterface::class);
    }
}
