<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Core\Entity\User;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;
use Symfony\Component\Messenger\Stamp\HandledStamp;

/** `GET /api/meals/{id}/grocery_preview` (MAG-295). */
class MealGroceryPreviewControllerTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const FIXTURES = __DIR__.'/../Service/fixtures/MealGroceryChoiceTest.yaml';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures(self::FIXTURES);
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
    }

    public function testAnonymousIsRefused(): void
    {
        $this->client->request('GET', '/api/meals/'.$this->getFixture('other_meal')->getId().'/grocery_preview');

        self::assertResponseStatusCodeSame(401);
    }

    public function testAnotherUsersMealIsNotFound(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/meals/'.$this->getFixture('other_meal')->getId().'/grocery_preview', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testAnIdThatIsNotOneIsNotFound(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/meals/not-a-ulid/grocery_preview', [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    public function testThePreviewAnnouncesPackagingsStockAndSuggestions(): void
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $mealId = $this->planCurry();

        $this->client->request('GET', '/api/meals/'.$mealId.'/grocery_preview', [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertSame($mealId, $data['mealId']);
        self::assertNull($data['groceryChoiceMadeAt']);

        $byName = array_column($data['ingredients'], null, 'name');
        self::assertSame((string) $this->getFixture('rice')->getId(), $byName['Riz']['ingredientId']);
        self::assertSame(['quantity' => 1, 'unit' => 'pack'], $byName['Riz']['toBuy'], '300 g with a 500 g pack is 1 pack');
        self::assertTrue($byName['Riz']['converted']);
        self::assertSame('out', $byName['Riz']['stockState']);
        self::assertTrue($byName['Riz']['suggested']);
        self::assertFalse($byName['Légumes pour couscous']['suggested']);
        self::assertSame(['quantity' => 1, 'unit' => 'jar'], $byName['Légumes pour couscous']['toBuy']);
        self::assertNull($byName['Oignon']['packaging']);
        self::assertSame('piece', $byName['Oignon']['toBuy']['unit'], 'no packaging: the recipe unit');
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
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();

        return (string) $envelope->last(HandledStamp::class)->getResult()->getId();
    }
}
