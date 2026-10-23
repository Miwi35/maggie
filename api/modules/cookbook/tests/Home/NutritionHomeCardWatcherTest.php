<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Tests\Home;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\ElasticsearchAssertionTrait;
use App\Tests\Support\FixtureLoaderTrait;
use App\Tests\Support\MercureAssertionTrait;
use Doctrine\ORM\EntityManagerInterface;
use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Message\DeleteMealCommand;
use Maggie\Grocery\Enum\ProductStockState;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Messenger\MessageBusInterface;

/** Mercure `home/nutrition` (MAG-564): published when what the card reads moves, to that user alone. */
class NutritionHomeCardWatcherTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;
    use MercureAssertionTrait;
    use ElasticsearchAssertionTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('NutritionHomeCardTest.yaml');
        self::ensureKernelShutdown();
        $this->client = self::createClient();
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->resetMercure();
        $this->resetAsyncTransport();
    }

    public function testCreatingAMealRefreshesTheCard(): void
    {
        $this->client->request('POST', '/api/meals', [], [], array_merge([
            'CONTENT_TYPE' => 'application/ld+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode([
            'date' => '2026-10-16',
            'slot' => 'lunch',
            'agenda' => '/api/agendas/'.$this->getFixture('test_agenda')->getId(),
            'recipes' => ['/api/recipes/'.$this->getFixture('pasta')->getId()],
        ], JSON_THROW_ON_ERROR));

        self::assertResponseStatusCodeSame(201);
        $this->assertRefreshedForTheOwner();
    }

    public function testChangingAMealRefreshesTheCard(): void
    {
        $this->patchMeal(['date' => '2026-10-17']);

        self::assertResponseIsSuccessful();
        $this->assertRefreshedForTheOwner();
    }

    public function testSwappingTheRecipesOfAMealRefreshesTheCard(): void
    {
        $this->patchMeal(['recipes' => ['/api/recipes/'.$this->getFixture('pasta')->getId()]]);

        self::assertResponseIsSuccessful();
        $this->assertRefreshedForTheOwner();
    }

    public function testDeletingAMealRefreshesTheCard(): void
    {
        self::getContainer()->get(MessageBusInterface::class)
            ->dispatch(new DeleteMealCommand((string) $this->getFixture('dinner_today')->getId()));

        $this->assertRefreshedForTheOwner();
    }

    public function testRunningOutOfAnIngredientRefreshesTheCard(): void
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->find(Ingredient::class, $this->getFixture('pasta_food')->getId())->setStockState(ProductStockState::Out);
        $em->flush();

        $this->assertRefreshedForTheOwner();
    }

    public function testAnUnrelatedWriteDoesNotRefreshTheCard(): void
    {
        $em = self::getContainer()->get(EntityManagerInterface::class);
        $em->find(Ingredient::class, $this->getFixture('pasta_food')->getId())->setName('Spaghetti');
        $em->flush();

        $this->assertNothingPublishedOn('/home/nutrition');
    }

    /** @param array<string, mixed> $body */
    private function patchMeal(array $body): void
    {
        $this->client->request('PATCH', '/api/meals/'.$this->getFixture('dinner_today')->getId(), [], [], array_merge([
            'CONTENT_TYPE' => 'application/merge-patch+json',
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()), json_encode($body, JSON_THROW_ON_ERROR));
    }

    private function assertRefreshedForTheOwner(): void
    {
        $owner = (string) $this->getFixture('test_user')->getId();

        $this->assertMercurePublishedOnce('/users/'.$owner.'/home/nutrition');
        self::assertSame([['action' => 'refresh', 'module' => 'nutrition']], $this->mercurePayloadsOn('/home/nutrition'));
        $this->assertNothingPublishedOn('/users/'.(string) $this->getFixture('other_user')->getId().'/home/nutrition');
    }
}
