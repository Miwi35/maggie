<?php

namespace Maggie\Cookbook\Tests\Api;

use App\Tests\Support\AuthenticatedTestTrait;
use App\Tests\Support\FixtureLoaderTrait;
use Maggie\Cookbook\Entity\Recipe;
use Symfony\Bundle\FrameworkBundle\KernelBrowser;
use Symfony\Bundle\FrameworkBundle\Test\WebTestCase;
use Symfony\Component\Clock\Clock;
use Symfony\Component\Clock\MockClock;
use Symfony\Component\Clock\NativeClock;

/** A recipe's ingredients say whether they are in season (MAG-494). */
class RecipeSeasonApiTest extends WebTestCase
{
    use FixtureLoaderTrait;
    use AuthenticatedTestTrait;

    private KernelBrowser $client;

    protected function setUp(): void
    {
        $this->client = self::createClient();
        $this->loadFixtures('RecipeApiTest.yaml');
        self::getContainer()->get('doctrine.orm.entity_manager')->clear();
    }

    protected function tearDown(): void
    {
        Clock::set(new NativeClock());
        parent::tearDown();
    }

    /** @return array<string, ?bool> inSeason by ingredient name */
    private function inSeasonByIngredient(string $now): array
    {
        Clock::set(new MockClock($now));
        $this->authenticateAsUser($this->getFixture('test_user'));

        $this->client->request('GET', '/api/recipes/'.$this->getFixture('pasta')->getId(), [], [], array_merge([
            'HTTP_ACCEPT' => 'application/ld+json',
        ], $this->authHeaders()));
        self::assertResponseIsSuccessful();
        $recipe = json_decode((string) $this->client->getResponse()->getContent(), true, 512, JSON_THROW_ON_ERROR);

        $byName = [];
        foreach ($recipe['ingredients'] as $line) {
            self::assertSame($line['inSeason'] ?? null, $line['ingredient']['inSeason'] ?? null, 'The line says what its ingredient says');
            $byName[$line['ingredientName']] = $line['inSeason'] ?? null;
        }

        return $byName;
    }

    public function testRequiresAuthentication(): void
    {
        $this->client->request('GET', '/api/recipes/'.$this->getFixture('pasta')->getId(), [], [], ['HTTP_ACCEPT' => 'application/ld+json']);

        self::assertResponseStatusCodeSame(401);
    }

    public function testATomatoIsOutOfSeasonInJanuary(): void
    {
        $byName = $this->inSeasonByIngredient('2027-01-15 12:00:00 UTC');

        self::assertFalse($byName['Tomate']);
        self::assertNull($byName['Pâtes'], 'Pasta has no season, so no badge');
    }

    public function testATomatoIsInSeasonInJuly(): void
    {
        self::assertTrue($this->inSeasonByIngredient('2026-07-15 12:00:00 UTC')['Tomate']);
    }

    public function testTheRecipeMercurePayloadCarriesInSeason(): void
    {
        Clock::set(new MockClock('2027-01-15 12:00:00 UTC'));
        $recipe = self::getContainer()->get('doctrine.orm.entity_manager')->find(Recipe::class, $this->getFixture('pasta')->getId());

        $lines = array_column($recipe->toMercurePayload()['ingredients'], null, 'ingredientName');

        self::assertFalse($lines['Tomate']['inSeason']);
        self::assertSame([6, 7, 8, 9], $lines['Tomate']['ingredient']['seasonMonths']);
        self::assertNull($lines['Pâtes']['inSeason']);
    }
}
