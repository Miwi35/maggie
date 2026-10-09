<?php

declare(strict_types=1);

namespace Maggie\Grocery\Tests\Command;

use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Grocery\Command\AddDueRecurringGroceryItemsCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\RecurringGroceryItem;
use Maggie\Grocery\Enum\GroceryItemSource;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Console\Application;
use Symfony\Component\Console\Tester\CommandTester;

/**
 * `maggie:grocery:add-due-recurring-items` — the only thing that brings a
 * recurring purchase back without anyone asking Maggie to generate the list (MAG-369).
 */
final class AddDueRecurringGroceryItemsCommandTest extends KernelTestCase
{
    use ElasticsearchAssertionTrait;
    use FixtureLoaderTrait;
    use MercureAssertionTrait;

    private CommandTester $tester;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('AddDueRecurringGroceryItemsCommandTest.yaml');
        $this->resetMercure();
        $this->resetAsyncTransport();

        $application = new Application();
        $application->add(self::getContainer()->get(AddDueRecurringGroceryItemsCommand::class));

        $this->tester = new CommandTester($application->find('maggie:grocery:add-due-recurring-items'));
    }

    private function em(): EntityManagerInterface
    {
        $em = self::getContainer()->get('doctrine.orm.entity_manager');
        \assert($em instanceof EntityManagerInterface);

        return $em;
    }

    private function today(): string
    {
        return (new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    private function daysAgo(int $days): string
    {
        return (new \DateTimeImmutable('today', new \DateTimeZone('Europe/Paris')))->modify("-{$days} days")->format('Y-m-d');
    }

    private function recurring(string $label): RecurringGroceryItem
    {
        $this->em()->clear();

        foreach ($this->em()->getRepository(RecurringGroceryItem::class)->findAll() as $recurring) {
            if ($recurring->getLabel() === $label) {
                return $recurring;
            }
        }

        throw new \LogicException("No recurring item labelled {$label}.");
    }

    /** @return array<string, float|null> label => quantity of the user's lines */
    private function listOf(string $email): array
    {
        $this->em()->clear();
        $lines = [];

        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            if ($item->getGroceryList()->getUser()->getEmail() === $email) {
                $lines[$item->getLabel()] = $item->getQuantity();
            }
        }
        ksort($lines);

        return $lines;
    }

    public function testAWeeklyItemLastAddedEightDaysAgoIsOnTheListAndItsClockRestarts(): void
    {
        $this->tester->execute([]);

        self::assertSame(['Lait' => 1.0, 'Riz' => 2.0], $this->listOf('recurring-test@example.com'));
        self::assertSame($this->today(), $this->recurring('Riz')->getLastAddedAt()?->format('Y-m-d'));
        self::assertSame($this->today(), $this->recurring('Lait')->getLastAddedAt()?->format('Y-m-d'));
    }

    public function testAWeeklyItemLastAddedExactlySevenDaysAgoIsDueToday(): void
    {
        // Reloaded from the database, where the date comes back as midnight UTC.
        $this->recurring('Riz')->setLastAddedAt(new \DateTimeImmutable($this->daysAgo(7), new \DateTimeZone('UTC')));
        $this->em()->flush();

        $this->tester->execute([]);

        self::assertArrayHasKey('Riz', $this->listOf('recurring-test@example.com'));
    }

    public function testTheLinesComeFromTheRecurringSourceInTheProductsStore(): void
    {
        $this->tester->execute([]);

        $this->em()->clear();
        $lines = [];
        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            $lines[$item->getLabel()] = $item;
        }

        self::assertSame(GroceryItemSource::Recurring, $lines['Riz']->getSource());
        self::assertSame('Supermarché', $lines['Riz']->getStore()?->getName());
        self::assertSame(GroceryItemSource::Recurring, $lines['Lait']->getSource());
    }

    public function testAnItemWhosePeriodHasNotRunOutIsLeftAlone(): void
    {
        $this->tester->execute([]);

        self::assertArrayNotHasKey('Café', $this->listOf('recurring-test@example.com'));
        self::assertSame(
            (new \DateTimeImmutable('-3 days'))->format('Y-m-d'),
            $this->recurring('Café')->getLastAddedAt()?->format('Y-m-d'),
        );
    }

    public function testEachOwnerGetsTheirItemsOnTheirOwnList(): void
    {
        $this->tester->execute([]);

        self::assertSame(['Pain' => 1.0], $this->listOf('recurring-other@example.com'));
        self::assertArrayNotHasKey('Pain', $this->listOf('recurring-test@example.com'));
    }

    public function testRunningItAgainTheSameDayAddsNothing(): void
    {
        $this->tester->execute([]);
        $this->tester->execute([]);

        self::assertSame(['Lait' => 1.0, 'Riz' => 2.0], $this->listOf('recurring-test@example.com'));
        self::assertSame(['Pain' => 1.0], $this->listOf('recurring-other@example.com'));
        self::assertStringContainsString('No recurring item is due.', $this->tester->getDisplay());
    }

    public function testAnItemAlreadyWaitingOnTheListIsNotAddedAgainButItsClockRestarts(): void
    {
        $this->tester->execute([]);
        $this->em()->clear();

        // The rice is due again a week later while the first bag is still unbought.
        $rice = $this->recurring('Riz');
        $rice->setLastAddedAt(new \DateTimeImmutable('-8 days'));
        $this->em()->flush();

        $this->tester->execute([]);

        self::assertSame(['Lait' => 1.0, 'Riz' => 2.0], $this->listOf('recurring-test@example.com'));
        self::assertSame($this->today(), $this->recurring('Riz')->getLastAddedAt()?->format('Y-m-d'));
    }

    public function testTheListIsPublishedAndReindexed(): void
    {
        $this->tester->execute([]);

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertMercureUpdatePublished('/recurring_grocery_items/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatched(RecurringGroceryItem::class);
    }

    public function testNothingIsPublishedWhenNothingIsDue(): void
    {
        $this->tester->execute([]);
        $this->resetMercure();

        $this->tester->execute([]);

        $this->assertMercureUpdateCount(0);
    }
}
