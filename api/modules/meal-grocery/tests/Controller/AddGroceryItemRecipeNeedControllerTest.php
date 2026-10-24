<?php

declare(strict_types=1);

namespace Maggie\MealGrocery\Tests\Controller;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Message\CreateMealCommand;
use Maggie\Grocery\Entity\GroceryItem;
use Maggie\Grocery\Entity\GroceryList;
use Maggie\Grocery\Entity\Product;
use Maggie\Grocery\Enum\Unit;
use Maggie\MealGrocery\Entity\MealGroceryContribution;
use Maggie\MealGrocery\Service\MealGrocerySync;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/**
 * Adding a product with the week's meals in mind (MAG-573): « Riz basmati » in
 * 500 g packs, restocked by 2, and meals this week asking 800 g (the curry's
 * 300 g and the big rice's 500 g).
 */
class AddGroceryItemRecipeNeedControllerTest extends WebTestCase
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
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Riz', 'user' => $this->getFixture('test_user')])->setRestockQuantity(2);
        $this->em()->flush();

        $this->plan('curry', '+1 day');
        $this->plan('big_rice', '+3 days');
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testWithoutAQuantityTheRestockIsTheDefaultAndCoversTheWeek(): void
    {
        $data = $this->add(['label' => 'Riz']);

        self::assertResponseIsSuccessful();
        self::assertEquals(2.0, $data['quantity']);
        self::assertEquals(1000.0, $data['desiredQuantity']);
        self::assertSame('g', $data['desiredUnit']);
        self::assertEquals(['quantity' => 800.0, 'unit' => 'g'], $data['recipeNeed']);
        self::assertTrue($data['coversWeek']);
        self::assertNull($data['packagingsToCover']);

        $rice = $this->rice();
        self::assertEquals(2.0, $rice->getQuantity());
        self::assertSame('pack', $rice->getUnit()?->value);
        self::assertSame(1000.0, $rice->getDesiredQuantity());
        self::assertSame('g', $rice->getDesiredUnit()?->value);
        self::assertEquals([['quantity' => 800.0, 'unit' => 'g']], $rice->getNeeded());

        $this->assertMercureUpdatePublished('/grocery_lists/');
        $this->assertElasticsearchIndexDispatched(GroceryList::class);
    }

    public function testTheNewLineHoldsTheWeeksMealsSoALaterSyncDoesNotCountThemTwice(): void
    {
        $this->add(['label' => 'Riz']);
        $item = $this->rice();
        $contributions = $this->em()->getRepository(MealGroceryContribution::class)->findBy(['groceryItem' => $item]);
        self::assertCount(2, $contributions, 'one share per meal of the week');

        $curry = $contributions[0]->getMeal();
        $rice = $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Riz', 'user' => $this->getFixture('test_user')]);
        self::getContainer()->get(MealGrocerySync::class)->syncChoice($curry, [
            ['ingredient' => $rice, 'unit' => Unit::Gram, 'quantity' => $contributions[0]->getQuantity()],
        ]);
        $this->em()->flush();
        $this->em()->clear();

        self::assertEquals([['quantity' => 800.0, 'unit' => 'g']], $this->rice()->getNeeded(), 'the meal moved by its own share, not added again');
        self::assertEquals(2.0, $this->rice()->getQuantity());
    }

    public function testAQuantityIsRoundedUpToPackagingsAndStaysWanted(): void
    {
        $data = $this->add(['label' => 'Riz', 'quantity' => 1.4, 'unit' => 'kg']);

        self::assertResponseIsSuccessful();
        self::assertEquals(3.0, $data['quantity']);
        self::assertEquals(1400.0, $data['desiredQuantity']);
        self::assertTrue($data['coversWeek']);

        $rice = $this->rice();
        self::assertEquals(3.0, $rice->getQuantity());
        self::assertEquals(1400.0, $rice->getDesiredQuantity());
        self::assertEquals([['quantity' => 800.0, 'unit' => 'g']], $rice->getNeeded());
    }

    public function testPlusKeepsTheWantedQuantityAndMinusBringsItDownToWhatThePacksHold(): void
    {
        $this->add(['label' => 'Riz', 'quantity' => 1.4, 'unit' => 'kg']);
        $id = (string) $this->rice()->getId();

        $this->patch($id, ['packagings' => 4]);
        self::assertResponseIsSuccessful();
        self::assertSame(4.0, $this->rice()->getQuantity());
        self::assertEquals(1400.0, $this->rice()->getDesiredQuantity(), 'a 4th pack does not change what was asked');

        $this->patch($id, ['packagings' => 3]);
        self::assertSame(3.0, $this->rice()->getQuantity());
        self::assertEquals(1400.0, $this->rice()->getDesiredQuantity());

        $this->patch($id, ['packagings' => 2]);
        self::assertResponseIsSuccessful();
        self::assertSame(2.0, $this->rice()->getQuantity());
        self::assertEquals(1000.0, $this->rice()->getDesiredQuantity(), 'two packs hold 1 kg: never an error on a list gesture');
        self::assertEquals([['quantity' => 800.0, 'unit' => 'g']], $this->rice()->getNeeded(), 'the need of the meals is left as it was');
    }

    public function testChangingTheWantedQuantityRecomputesThePackagings(): void
    {
        $this->add(['label' => 'Riz']);

        $this->patch((string) $this->rice()->getId(), ['quantity' => 1.4, 'unit' => 'kg']);

        self::assertResponseIsSuccessful();
        self::assertSame(3.0, $this->rice()->getQuantity());
        self::assertEquals(1400.0, $this->rice()->getDesiredQuantity());
    }

    public function testAWeekTheRestockDoesNotCoverIsAddedAndSaidSo(): void
    {
        $this->plan('rice_bowl', '+2 days');

        $data = $this->add(['label' => 'Riz']);

        self::assertResponseIsSuccessful();
        self::assertEquals(2.0, $data['quantity']);
        self::assertFalse($data['coversWeek']);
        self::assertEquals(['quantity' => 1200.0, 'unit' => 'g'], $data['recipeNeed']);
        self::assertSame(3, $data['packagingsToCover']);
        self::assertEquals(2.0, $this->rice()->getQuantity(), 'the item is created anyway');
    }

    public function testWithoutRestockTheNeedRoundedUpToPackagingsIsTheDefault(): void
    {
        $this->em()->getRepository(Product::class)->findOneBy(['name' => 'Riz', 'user' => $this->getFixture('test_user')])->setRestockQuantity(null);
        $this->em()->flush();
        $this->em()->clear();
        $this->plan('rice_bowl', '+2 days');

        $data = $this->add(['label' => 'Riz']);

        self::assertEquals(3.0, $data['quantity'], '1,2 kg in 500 g packs');
        self::assertTrue($data['coversWeek']);
        self::assertEquals(1500.0, $data['desiredQuantity']);
    }

    public function testPackagingsAloneAreTheCountAndWhatTheyHoldIsWanted(): void
    {
        $data = $this->add(['label' => 'Riz', 'packagings' => 2]);

        self::assertResponseIsSuccessful();
        self::assertEquals(2.0, $data['quantity']);
        self::assertEquals(1000.0, $data['desiredQuantity']);
    }

    public function testAProductWithoutPackagingKeepsTheCurrentBehaviour(): void
    {
        $data = $this->add(['label' => 'Oignon', 'quantity' => 3, 'unit' => 'piece']);

        self::assertResponseIsSuccessful();
        self::assertEquals(3.0, $data['quantity']);
        self::assertNull($data['desiredQuantity']);
        self::assertEquals(['quantity' => 2.0, 'unit' => 'piece'], $data['recipeNeed']);
        self::assertTrue($data['coversWeek']);
    }

    public function testAProductWithoutPackagingAndNeededByMealsSaysWhenItFallsShort(): void
    {
        $this->plan('curry', '+2 days');

        $data = $this->add(['label' => 'Oignon', 'quantity' => 1, 'unit' => 'piece']);

        self::assertEquals(['quantity' => 4.0, 'unit' => 'piece'], $data['recipeNeed'], '2 onions a curry, two curries');
        self::assertFalse($data['coversWeek']);
        self::assertNull($data['packagingsToCover']);
    }

    /** @return iterable<string, array{array<string, mixed>}> */
    public static function refusals(): iterable
    {
        yield 'quantity and packagings together' => [['label' => 'Riz', 'quantity' => 1, 'unit' => 'kg', 'packagings' => 2]];
        yield 'packagings on a product without packaging' => [['label' => 'Oignon', 'packagings' => 2]];
        yield 'packagings on a free label' => [['label' => 'Courgettes', 'packagings' => 2]];
        yield 'zero packagings' => [['label' => 'Riz', 'packagings' => 0]];
        yield 'negative packagings' => [['label' => 'Riz', 'packagings' => -1]];
        yield 'zero quantity' => [['label' => 'Riz', 'quantity' => 0, 'unit' => 'kg']];
    }

    /** @param array<string, mixed> $body */
    #[\PHPUnit\Framework\Attributes\DataProvider('refusals')]
    public function testARefusedAdditionChangesNothing(array $body): void
    {
        $this->add(['label' => 'Riz']);
        $before = $this->rice()->quantityState();

        $this->add($body);

        self::assertResponseStatusCodeSame(400);
        $error = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertArrayHasKey('error', $error);
        if ('Riz' === $body['label']) {
            self::assertEquals($before, $error['item'], 'the error recalls the line as it stands');
        }
        self::assertCount(1, $this->em()->getRepository(GroceryItem::class)->findAll(), 'the list is unchanged');
        self::assertEquals($before, $this->rice()->quantityState());
    }

    public function testAnonymousIsRefused(): void
    {
        $this->client->request('GET', '/api/grocery/recipe-need/'.$this->getFixture('rice')->getId());

        self::assertResponseStatusCodeSame(401);
    }

    public function testTheRecipeNeedOfAProductIsReadBeforeAddingIt(): void
    {
        $this->client->request('GET', '/api/grocery/recipe-need/'.$this->getFixture('rice')->getId(), [], [], $this->authHeaders());

        self::assertResponseIsSuccessful();
        $data = json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
        self::assertSame(7, $data['days']);
        self::assertEquals(['quantity' => 800.0, 'unit' => 'g'], $data['recipeNeed']);
        self::assertSame(['Riz au curry', 'Riz pour la tablée'], array_column($data['meals'], 'name'));
    }

    public function testAnotherUsersProductIsNotFound(): void
    {
        $this->client->request('GET', '/api/grocery/recipe-need/'.$this->getFixture('other_rice')->getId(), [], [], $this->authHeaders());

        self::assertResponseStatusCodeSame(404);
    }

    /**
     * @param array<string, mixed> $body
     *
     * @return array<string, mixed>
     */
    private function add(array $body): array
    {
        $this->client->request('POST', '/api/grocery/add-item', [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), json_encode($body, \JSON_THROW_ON_ERROR));
        $this->em()->clear();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, \JSON_THROW_ON_ERROR);
    }

    /** @param array<string, mixed> $body */
    private function patch(string $id, array $body): void
    {
        $this->client->request('PATCH', '/api/grocery/edit-item/'.$id, [], [], array_merge(['CONTENT_TYPE' => 'application/json'], $this->authHeaders()), json_encode($body, \JSON_THROW_ON_ERROR));
        $this->em()->clear();
    }

    private function rice(): GroceryItem
    {
        foreach ($this->em()->getRepository(GroceryItem::class)->findAll() as $item) {
            if ('Riz' === $item->getLabel()) {
                return $item;
            }
        }

        throw new \LogicException('No rice on the list.');
    }

    private function plan(string $recipeRef, string $when): void
    {
        self::getContainer()->get(MessageBusInterface::class)->dispatch(new CreateMealCommand(
            date: (new \DateTimeImmutable($when, new \DateTimeZone('Europe/Paris')))->format('Y-m-d'),
            slot: 'dinner',
            recipeIds: [(string) $this->getFixture($recipeRef)->getId()],
            userId: (string) $this->getFixture('test_user')->getId(),
        ));
        $this->em()->clear();
    }

    private function em(): EntityManagerInterface
    {
        return self::getContainer()->get('doctrine.orm.entity_manager');
    }
}
