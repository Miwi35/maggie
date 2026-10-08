<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Meal;
use Maggie\Cookbook\Entity\MealGroceryContribution;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Core\Entity\User;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use PHPUnit\Framework\Attributes\DataProvider;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** `POST /api/meals/{id}/grocery_items` (MAG-295). */
class MealGroceryItemsControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private const FIXTURES = __DIR__.'/../Service/fixtures/MealGroceryChoiceTest.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(self::FIXTURES);
        $this->em()->clear();
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testAnonymousIsRefused(): void
    {
        $this->post((string) $this->getFixture('other_meal')->getId(), ['ingredients' => []], authenticated: false);

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersMealIsNotFound(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->post((string) $this->getFixture('other_meal')->getId(), ['ingredients' => [['ingredientId' => (string) $this->getFixture('other_rice')->getId()]]]);

        self::assertResponseStatusCodeSame(404);
        $this->em()->clear();
        self::assertNull($this->em()->find(Meal::class, $this->getFixture('other_meal')->getId())?->getGroceryChoiceMadeAt());
    }

    public function testAnIngredientOfNoRecipeOfTheMealIsRefused(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $mealId = $this->planCurry();

        $this->post($mealId, ['ingredients' => [['ingredientId' => (string) $this->getFixture('fish')->getId()]]]);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->meal($mealId)->getGroceryChoiceMadeAt());
        self::assertSame(['Légumes pour couscous 1 jar', 'Oignon 2 piece', 'Riz 300 g'], $this->list());
    }

    /** @return iterable<string, array{mixed}> */
    public static function badBodies(): iterable
    {
        yield 'no ingredients' => [[]];
        yield 'ingredients not a list' => [['ingredients' => 'rice']];
        yield 'an entry without ingredientId' => [['ingredients' => [['quantity' => 1]]]];
        yield 'a negative quantity' => [['ingredients' => [['ingredientId' => '@rice', 'quantity' => -1]]]];
        yield 'a quantity that is not a number' => [['ingredients' => [['ingredientId' => '@rice', 'quantity' => 'two']]]];
    }

    #[DataProvider('badBodies')]
    public function testABadBodyIsRefused(mixed $body): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $mealId = $this->planCurry();

        if (\is_array($body) && \is_array($body['ingredients'] ?? null)) {
            foreach ($body['ingredients'] as $i => $entry) {
                if ('@rice' === ($entry['ingredientId'] ?? null)) {
                    $body['ingredients'][$i]['ingredientId'] = (string) $this->getFixture('rice')->getId();
                }
            }
        }

        $this->post($mealId, $body);

        self::assertResponseStatusCodeSame(400);
        self::assertNull($this->meal($mealId)->getGroceryChoiceMadeAt());
    }

    public function testTheChosenIngredientsGoOnTheListInPackagingsAndTheChoiceIsStamped(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $mealId = $this->planCurry();
        $this->resetMercure();
        $this->resetAsyncTransport();

        $this->post($mealId, ['ingredients' => [['ingredientId' => (string) $this->getFixture('rice')->getId()]]]);

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertNotNull($data['groceryChoiceMadeAt']);

        // One « Riz — 1 paquet », no « Riz — 300 g » beside it, nothing for
        // the vegetables, and one contribution holding the pack.
        self::assertSame(['Riz 1 pack'], $this->list());
        $contributions = $this->em()->getRepository(MealGroceryContribution::class)->findAll();
        self::assertCount(1, $contributions);
        self::assertSame(1.0, $contributions[0]->getQuantity());
        self::assertSame($mealId, (string) $contributions[0]->getMeal()->getId());
        self::assertNotNull($this->meal($mealId)->getGroceryChoiceMadeAt());

        $this->assertMercureUpdatePublished('/api/grocery_lists/');
        $this->assertMercureUpdatePublished('/api/meals/'.$mealId);
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
        $this->assertElasticsearchIndexDispatchedFor(Meal::class, $mealId);
    }

    public function testAQuantityCanBeForced(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $mealId = $this->planCurry();

        $this->post($mealId, ['ingredients' => [['ingredientId' => (string) $this->getFixture('rice')->getId(), 'quantity' => 2]]]);

        self::assertResponseIsSuccessful();
        self::assertSame(['Riz 2 pack'], $this->list());
    }

    public function testAClientCannotStampTheChoiceItself(): void
    {
        // Stamping it would switch the derivation off without choosing.
        $this->authenticateAsUser($this->getFixture('test_user'));
        $mealId = $this->planCurry();

        $this->client->request('PATCH', '/api/meals/'.$mealId, [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode(['groceryChoiceMadeAt' => '2026-10-08T10:00:00+02:00'], \JSON_THROW_ON_ERROR));

        self::assertResponseIsSuccessful();
        self::assertNull($this->meal($mealId)->getGroceryChoiceMadeAt());
    }

    private function post(string $mealId, $body, bool $authenticated = true): void
    {
        $this->client->request('POST', '/api/meals/'.$mealId.'/grocery_items', [], [], array_merge(
            ['CONTENT_TYPE' => 'application/json', 'HTTP_ACCEPT' => 'application/json'],
            $authenticated ? $this->authHeaders() : [],
        ), json_encode($body, \JSON_THROW_ON_ERROR));
    }

    private function planCurry(): string
    {
        $user = $this->getFixture('test_user');
        \assert($user instanceof User);

        $envelope = self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable('+1 day', new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture('curry')->getId()],
            userId: (string) $user->getId(),
        ));
        $this->em()->clear();

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }

    /** @return list<string> */
    private function list(): array
    {
        $this->em()->clear();

        $lines = [];
        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            $lines[] = $item->getLabel().' '.$item->getQuantity().' '.$item->getUnit()?->value;
        }
        sort($lines);

        return $lines;
    }

    private function meal(string $id): Meal
    {
        $this->em()->clear();

        return $this->em()->find(Meal::class, $id) ?? throw new \LogicException("No meal {$id}.");
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
