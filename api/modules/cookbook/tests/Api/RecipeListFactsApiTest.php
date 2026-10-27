<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;

/**
 * MAG-629: what the recipe table shows beside each recipe and the recipe itself does
 * not carry — its last meal and what one serving brings.
 */
class RecipeListFactsApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private const ENDPOINT = '/api/recipe-list-facts';

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('RecipeListFactsApiTest.yaml');
        // The fixtures set the lines' recipe, not the recipe's lines: read them back from the database.
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
    }

    /** @return array<string, array{lastMealDate: string|null, kcalPerServing: int|null}> */
    private function facts(): array
    {
        $this->authenticateAsUser($this->getFixture('test_user'));
        $this->client->request('GET', self::ENDPOINT, [], [], $this->authHeaders());
        self::assertResponseIsSuccessful();

        return json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR)['recipes'];
    }

    public function testRequiresAuthentication(): void
    {
        $this->client->request('GET', self::ENDPOINT);

        self::assertResponseStatusCodeSame(401);
    }

    public function testListsOnlyTheUsersRecipes(): void
    {
        $facts = $this->facts();

        self::assertCount(3, $facts);
        self::assertArrayNotHasKey((string) $this->getFixture('other_recipe')->getId(), $facts);
    }

    public function testLastMealIsTheLatestMealAlreadyEaten(): void
    {
        $facts = $this->facts();

        // 2020-10-24 beats 2020-01-14, and the meal planned in 2099 is not eaten yet.
        self::assertSame('2020-10-24', $facts[(string) $this->getFixture('couscous')->getId()]['lastMealDate']);
        self::assertSame('2020-10-24', $facts[(string) $this->getFixture('pasta')->getId()]['lastMealDate']);
    }

    public function testARecipeNeverServedHasNoLastMeal(): void
    {
        $facts = $this->facts();

        self::assertNull($facts[(string) $this->getFixture('mystery')->getId()]['lastMealDate']);
    }

    public function testKcalPerServingCountsWeightsAndVolumesAndLeavesPiecesOut(): void
    {
        $facts = $this->facts();

        self::assertSame(175, $facts[(string) $this->getFixture('pasta')->getId()]['kcalPerServing']);
        self::assertSame(898, $facts[(string) $this->getFixture('couscous')->getId()]['kcalPerServing']);
    }

    public function testNoKcalWhenNoProductKnowsItsNutrition(): void
    {
        $facts = $this->facts();

        self::assertNull($facts[(string) $this->getFixture('mystery')->getId()]['kcalPerServing']);
    }
}
