<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Service;

use App\Tests\Support\FixtureLoaderTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Cookbook\Service\RecipeNeeds;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\Product;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/** What the planned meals need of a product over a window of days (MAG-573). */
class RecipeNeedsTest extends KernelTestCase
{
    use FixtureLoaderTrait;

    protected function setUp(): void
    {
        self::bootKernel();
        $this->loadFixtures('MealGroceryChoiceTest.yaml');
        $this->em()->clear();
    }

    public function testTheMealsOfTheWeekAddUpInBaseUnits(): void
    {
        $this->plan('curry', 'today');
        $this->plan('big_rice', '+6 days');

        $need = $this->need();

        self::assertEquals([['quantity' => 800.0, 'unit' => 'g']], $need['needs']);
        self::assertSame(['Riz au curry', 'Riz pour la tablée'], array_column($need['meals'], 'name'));
        self::assertSame([300.0, 500.0], array_column($need['meals'], 'quantity'));
    }

    public function testAMealBeyondTheWindowOrAlreadyPastIsIgnored(): void
    {
        $this->plan('curry', '+1 day');
        $this->plan('big_rice', '+7 days');
        $this->plan('rice_bowl', '-1 day');

        self::assertEquals([['quantity' => 300.0, 'unit' => 'g']], $this->need()['needs']);
    }

    public function testTheWindowIsAParameter(): void
    {
        $this->plan('curry', 'today');
        $this->plan('big_rice', '+1 day');
        $this->plan('rice_bowl', '+2 days');

        self::assertEquals([['quantity' => 800.0, 'unit' => 'g']], $this->need(2)['needs'], 'today and tomorrow');
        self::assertEquals([['quantity' => 1200.0, 'unit' => 'g']], $this->need(3)['needs']);
    }

    public function testTheQuantityIsScaledToTheMealsGuests(): void
    {
        // The curry serves 2: for 4 guests the rice doubles.
        $this->plan('curry', '+1 day', guests: 4);

        self::assertEquals([['quantity' => 600.0, 'unit' => 'g']], $this->need()['needs']);
    }

    public function testAProductNoMealUsesNeedsNothing(): void
    {
        $this->plan('curry', '+1 day');
        $onion = $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Cabillaud']);

        $need = $this->service()->forProduct($this->user(), $onion);

        self::assertSame([], $need['needs']);
        self::assertSame([], $need['meals']);
    }

    /** @return array{needs: list<array{quantity: float, unit: ?string}>, meals: list<array<string, mixed>>} */
    private function need(?int $days = null): array
    {
        $rice = $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Riz', 'user' => $this->user()]);

        return null === $days ? $this->service()->forProduct($this->user(), $rice) : $this->service()->forProduct($this->user(), $rice, $days);
    }

    private function plan(string $recipeRef, string $when, ?int $guests = null): void
    {
        $this->bus()->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
            guests: $guests,
        ));
        $this->em()->clear();
    }

    private function user(): User
    {
        return $this->em()->getRepository(User::class)->findOneBy(['email' => 'meal-choice@example.com'])
            ?? throw new \LogicException('No user.');
    }

    private function service(): RecipeNeeds
    {
        return self::getContainer()->get(RecipeNeeds::class);
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
