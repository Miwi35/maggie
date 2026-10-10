<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\MessageHandler;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use App\Tests\Support\SecurityTokenTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Calendar\Entity\Agenda;
use Maggie\Calendar\Mcp\Tool\DeleteEventTool;
use Maggie\Calendar\Mcp\Tool\ManageAgendasTool;
use Maggie\Calendar\Mcp\Tool\UpdateEventTool;
use Maggie\Calendar\Message\DeleteAgendaCommand;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/**
 * A meal removed or moved through the agenda keeps the grocery list in step (MAG-368).
 *
 * The agenda's doors — `DELETE /api/events/{id}`, `delete_event`, deleting the
 * agenda, `update_event` — know nothing of the list: the database cascade
 * erased the contributions and the lines kept their quantities, so the
 * ingredients of a cancelled dinner stayed to buy.
 */
class MealLeavesTheAgendaTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use SecurityTokenTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const GROCERY_TOPIC = '/api/grocery_lists/';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->resetMercure();
        $this->resetAsyncTransport();
        $this->loadFixtures('MealGrocerySyncTest.yaml');
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->loginFixtureUser();
        $this->em()->clear();
    }

    public function testDeletingAMealThroughTheEventsApiTakesItsIngredientsOffTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        $this->resetMercure();

        $this->client->request('DELETE', '/api/events/'.$mealId, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testDeletingAMealWithTheDeleteEventToolTakesItsIngredientsOffTheList(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->resetMercure();

        $data = json_decode(self::getContainer()->get(DeleteEventTool::class)($mealId), true, 512, JSON_THROW_ON_ERROR);

        self::assertTrue($data['success']);
        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testDeletingTheMealsAgendaTakesTheIngredientsOfEveryMealOffTheList(): void
    {
        $this->planMeal('pasta');
        $this->planMealOn('gratin', '+2 days');
        self::assertSame(['Parmesan' => 80.0, 'Pâtes' => 400.0, 'Tomate' => 6.0], $this->list());
        $this->resetMercure();

        $this->bus()->dispatch(new DeleteAgendaCommand(agendaId: (string) $this->getFixture('repas_agenda')->getId()));

        self::assertNull($this->em()->getRepository(Agenda::class)->find($this->getFixture('repas_agenda')->getId()));
        self::assertSame([], $this->list());
        self::assertSame(0, $this->contributionCount());
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testDeletingTheMealsAgendaWithTheManageAgendasToolDoesTheSame(): void
    {
        $this->planMeal('pasta');

        $data = json_decode(
            self::getContainer()->get(ManageAgendasTool::class)(action: 'delete', agendaId: (string) $this->getFixture('repas_agenda')->getId()),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayNotHasKey('error', $data);
        self::assertSame([], $this->list());
    }

    public function testDeletingOneMealThroughTheAgendaLeavesTheShareOfTheOtherOnTheList(): void
    {
        $this->planMeal('pasta');
        $gratinId = $this->planMealOn('gratin', '+2 days');

        $this->client->request('DELETE', '/api/events/'.$gratinId, [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(204);
        self::assertSame(['Pâtes' => 400.0, 'Tomate' => 4.0], $this->list());
        self::assertSame(2, $this->contributionCount());
    }

    public function testALineAlreadyInTheBasketSurvivesAMealDeletedThroughTheAgenda(): void
    {
        $mealId = $this->planMeal('pasta');
        $this->check('Tomate');

        $this->client->request('DELETE', '/api/events/'.$mealId, [], [], $this->authHeaders());

        self::assertSame(['Tomate' => 4.0], $this->list());
    }

    public function testMovingAMealWithTheUpdateEventToolRecomputesWhenItsLinesAreToBeBought(): void
    {
        $mealId = $this->planMealOn('fish_dish', '+10 days');
        self::assertSame($this->day('+8 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
        $this->resetMercure();

        $moved = $this->day('+13 days');
        $data = json_decode(
            self::getContainer()->get(UpdateEventTool::class)(id: $mealId, start_date: $moved, end_date: $moved, all_day: true),
            true,
            512,
            JSON_THROW_ON_ERROR,
        );

        self::assertArrayNotHasKey('error', $data);
        self::assertSame($this->day('+11 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
        $this->assertMercureUpdatePublished(self::GROCERY_TOPIC);
    }

    public function testMovingAMealWithAPatchOnTheEventsApiRecomputesWhenItsLinesAreToBeBought(): void
    {
        $mealId = $this->planMealOn('fish_dish', '+10 days');

        $this->client->request('PATCH', '/api/events/'.$mealId, [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'startAt' => $this->day('+13 days').'T12:00:00+00:00',
        ], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame($this->day('+11 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
    }

    public function testRenamingAMealThroughTheAgendaLeavesTheListAlone(): void
    {
        $mealId = $this->planMealOn('fish_dish', '+10 days');
        $this->resetMercure();

        $this->client->request('PATCH', '/api/events/'.$mealId, [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['summary' => 'Poisson du jeudi'], JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertSame($this->day('+8 days'), $this->item('Cabillaud')->getBuyAfter()?->format('Y-m-d'));
        self::assertSame(0, $this->groceryUpdateCount());
    }

    private function planMeal(string $recipeRef): string
    {
        return $this->planMealOn($recipeRef, '+1 day');
    }

    private function planMealOn(string $recipeRef, string $when): string
    {
        $envelope = $this->bus()->dispatch(new CreateMealCommand(
            date: $this->day($when),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->user()->getId(),
        ));

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    private function day(string $when): string
    {
        return (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d');
    }

    private function check(string $label): void
    {
        foreach ($this->groceryList()->getItems() as $item) {
            if ($item->getLabel() === $label) {
                $item->setChecked(true);
            }
        }

        $this->em()->flush();
    }

    /** @return array<string, float|null> */
    private function list(): array
    {
        $this->em()->clear();

        $items = [];
        foreach ($this->groceryList()->getItems() as $item) {
            $items[$item->getLabel()] = $item->getQuantity();
        }
        ksort($items);

        return $items;
    }

    private function item(string $label): GroceryItem
    {
        $this->em()->clear();

        foreach ($this->groceryList()->getItems() as $item) {
            if ($item->getLabel() === $label) {
                return $item;
            }
        }

        throw new \LogicException("No grocery line labelled {$label}.");
    }

    private function groceryList(): GroceryList
    {
        return $this->em()->getRepository(GroceryList::class)->findOneBy([])
            ?? throw new \LogicException('No grocery list was created.');
    }

    private function groceryUpdateCount(): int
    {
        $count = 0;

        foreach ($this->getMercureHub()->getUpdates() as $update) {
            foreach ($update->getTopics() as $topic) {
                if (str_contains($topic, self::GROCERY_TOPIC)) {
                    ++$count;
                    break;
                }
            }
        }

        return $count;
    }

    private function contributionCount(): int
    {
        return \count($this->em()->getRepository(MealGroceryContribution::class)->findAll());
    }

    private function user(): User
    {
        return $this->em()->getRepository(User::class)->findOneBy(['email' => 'cookbook-test@example.com'])
            ?? throw new \LogicException('No user.');
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
